package com.github.kr328.clash.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * 隧道运行期间每 10 秒采一次内核连接表,写成 routing.jsonl。
 * 权重全量每 60 秒另写 weights.jsonl,连接记录只留 alts_top3。
 * 每条记录带连接活跃状态(state / idle_rounds / grew),判定规则见 [StallJudge]:
 * 阈值必须大于采样间隔、只判定有传输史的连接、长时间无增长单列 dormant。
 * 由 [start] 拉起协程,由 [stop] 取消;停止后没有残留定时器。
 */
class RoutingSampler private constructor(
    private val appContext: Context,
) {
    private data class Track(
        val down: Long,
        val up: Long,
        val lastChangeTs: Long,
        val everGrew: Boolean,
        val idleRounds: Int,
    )

    private data class Heavy(
        val weights: Map<String, List<DiagJson.Ranked>>,
        val proxies: Map<String, DiagJson.ProxyInfo>,
    )

    private val routingStore = DiagStore(
        appContext,
        "routing.jsonl",
        metaLine = DiagJson.ROUTING_META,
    )
    private val weightStore = DiagStore(
        appContext,
        "weights.jsonl",
        metaLine = DiagJson.WEIGHTS_META,
    )
    private val tracks = HashMap<String, Track>()
    private val trackLock = Any()

    /** 每轮把 App/内核/组/采样器/文件 的整体状态写一行 state.jsonl,见 [StateCollector]。 */
    private val stateCollector = StateCollector(appContext)
    private var rounds = 0L
    private var failedRounds = 0L
    private var droppedRounds = 0L
    private var heavyRefreshes = 0L
    private var connRows = 0L
    private var stalledRows = 0L
    private var avgRoundMs = 0L
    private var maxRoundMs = 0L
    private var lastOkAt = 0L
    private var lastStateAt = 0L

    @Volatile private var running = true
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var activeConn: HttpURLConnection? = null
    @Volatile private var heavy: Heavy? = null
    private var lastWeightsAt = 0L
    private var lastWeights: Map<String, List<DiagJson.Ranked>>? = null
    @Volatile private var lastHeavyAt = 0L
    private val stalledGeneration = AtomicLong(0)
    private var memberIndex: Map<String, String>? = null
    private var memberIndexSource: Map<String, DiagJson.ProxyInfo>? = null

    private fun launch() {
        val created = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = created
        created.launch { runLoop() }
    }

    private fun shutdown() {
        running = false
        activeConn?.disconnect()
        scope?.cancel()
        synchronized(trackLock) { tracks.clear() }
        // 停止前补一条状态快照:下一次要排查"为什么那个时间段没数据",得有收尾那一刻的状态
        try { maybeCaptureState(force = true, trigger = "stop") } catch (e: Exception) {
            Log.w("diag: stop-state failed: ${e.javaClass.simpleName}")
        }
        heavy = null
        scope = null
    }

    private suspend fun runLoop() {
        // 降优先级:采集是后台活,不该和前台解码/滚动抢 CPU
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        } catch (e: Exception) {
            Log.w("diag: set thread priority failed: ${e.javaClass.simpleName}")
        }
        while (running && coroutineContext.isActive) {
            val started = SystemClock.elapsedRealtime()
            try {
                sampleOnce()
                rounds++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedRounds++
                Log.w("diag: sample round failed: ${e.message}")
            }
            // 每轮耗时记账:state.jsonl 里的 cpu_ms_per_min / avg_round_ms 就是靠它
            val spent = SystemClock.elapsedRealtime() - started
            avgRoundMs = if (avgRoundMs == 0L) spent else (avgRoundMs * 4 + spent) / 5
            if (spent > maxRoundMs) maxRoundMs = spent
            maybeCaptureState()
            if (!running || !coroutineContext.isActive) break
            val wait = INTERVAL_MS - spent
            if (wait > 0) delay(wait)
        }
    }

    /** 状态快照默认 30 分钟一次,状态没变就不落盘(StateCollector 自己比对)。 */
    private fun maybeCaptureState(force: Boolean = false, trigger: String? = null) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastStateAt != 0L && now - lastStateAt < STATE_INTERVAL_MS) return
        lastStateAt = now
        val stats = StateCollector.SamplerStats(
            rounds = rounds,
            failedRounds = failedRounds,
            lastOkAgeS = if (lastOkAt == 0L) -1 else (System.currentTimeMillis() - lastOkAt) / 1000,
            avgRoundMs = avgRoundMs,
            maxRoundMs = maxRoundMs,
            heavyRefreshes = heavyRefreshes,
            connRows = connRows,
            stalledRows = stalledRows,
            droppedRounds = droppedRounds,
        )
        // 判据是「第几轮」而不是「是否成功取到过连接表」:
        // 第一轮常常正好撞在内核加载配置的那几秒,/connections 拿不到会算成 dropped_rounds,
        // 但 rounds 已经 +1,于是启动瞬间被记成 periodic —— 下游看到 api_up=false + periodic
        // 就会当成"运行中掉线"。实测开机后那条快照就是这么来的(groups=[]、vpn_active=false、
        // rounds=1、dropped_rounds=1),而同一分钟的权重快照里各组仍有 110/33/36 个成员。
        // 反过来也不能用 lastOkAt:成功的一轮会先把它置上,于是第一轮成功也标成 periodic,
        // start 只剩「第一轮失败」才会出现,语义正好相反。
        // 收尾那条快照要标 stop:否则停采时内核可能正好在重启,api_up=false 又被当成「运行中掉线」
        val why = trigger ?: if (rounds <= 1L) "start" else "periodic"
        stateCollector.capture(why, stats, force)
    }

    private fun sampleOnce() {
        if (!running) return
        val refreshed = maybeRefreshHeavy()
        if (refreshed) heavyRefreshes++
        try {
            if (!running) return
            val body = httpGet("$CONTROLLER/connections")
            if (body == null) {
                droppedRounds++
                if (running) Log.w("diag: skip round, /connections unavailable")
                return
            }
            if (!running) return
            val conns = try {
                DiagJson.parseConnections(body)
            } catch (e: Exception) {
                droppedRounds++
                Log.w("diag: bad /connections json (${e.javaClass.simpleName})")
                return
            }

            val nowWall = System.currentTimeMillis()
            val nowMono = SystemClock.elapsedRealtime()
            val ts = DiagJson.formatTs(nowWall)
            val netType = readNetType()
            val screen = readScreen()
            val snap = heavy
            val lines = ArrayList<String>(conns.size)
            var addedStall = 0

            synchronized(trackLock) {
                if (!running) return
                val seen = HashSet<String>(conns.size)
                for (conn in conns) {
                    if (conn.id.isEmpty()) continue
                    seen.add(conn.id)
                    val prev = tracks[conn.id]
                    val grew = prev != null && (conn.download > prev.down || conn.upload > prev.up)
                    val idleRounds = when {
                        prev == null -> 0
                        grew -> 0
                        else -> prev.idleRounds + 1
                    }
                    val everGrew = grew || (prev?.everGrew ?: false)
                    val lastChange = if (prev == null || grew) nowMono else prev.lastChangeTs
                    tracks[conn.id] = Track(
                        down = conn.download,
                        up = conn.upload,
                        lastChangeTs = lastChange,
                        everGrew = everGrew,
                        idleRounds = idleRounds,
                    )
                    // 第一次见到这条连接没有历史可比,idle 记 0;之后只在字节增加时清零。
                    val idleS = if (prev == null) 0.0 else (nowMono - lastChange) / 1000.0
                    // 判定以"连续无增长轮数"为准(见 StallJudge);idle_s 只作参考值上报。
                    val state = StallJudge.verdict(grew, everGrew, idleRounds)
                    val stalled = state == StallJudge.STALLED
                    if (stalled) addedStall++
                    // 降采样而非丢弃:
                    //   nodata(本次观测内从未见增长)照常写入 —— 它是连接刚出现的唯一记录,丢掉会让整段时间没有数据;
                    //   dormant(长时间无增长)每 6 轮(60 秒)留一条心跳,既压住体积又保证采集不中断。
                    // 之前一律 continue 会让长时间以闲置连接为主时选路文件完全停更,
                    // 进而导致游标停在文件末尾、后续上传永远为空(实测因此断了数天数据流)。
                    if (state == StallJudge.DORMANT && idleRounds % 6 != 0) continue
                    val node = DiagJson.nodeName(conn.chains)
                    val match = matchAlternatives(node, conn.chains, snap)
                    lines.add(
                        DiagJson.record(
                            ts = ts,
                            connId = conn.id,
                            node = node,
                            targetClass = DiagJson.targetClass(
                                conn.host,
                                conn.smartTarget,
                                conn.wildcardTarget,
                                conn.remoteDestination,
                                conn.destinationIPASN,
                                conn.destinationGeoIP,
                            ),
                            net = conn.network,
                            inbound = conn.inboundName,
                            downBytes = conn.download,
                            upBytes = conn.upload,
                            downRate = conn.maxDownloadRate,
                            upRate = conn.maxUploadRate,
                            ageS = DiagJson.ageSeconds(conn.start, nowWall),
                            idleS = DiagJson.round1(idleS),
                            idleRounds = idleRounds,
                            grew = grew,
                            state = state,
                            stalled = stalled,
                            netType = netType,
                            screen = screen,
                            chosenWeight = match.chosenWeight,
                            chosenDelayMs = match.chosenDelayMs,
                            altsTop3 = match.alts,
                            chain = DiagJson.chainArray(conn.chains),
                        )
                    )
                }
                // 这一轮快照里已经消失的连接,从追踪表删掉,避免表只增不减。
                val iterator = tracks.keys.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next() !in seen) iterator.remove()
                }
            }

            if (running) {
                routingStore.appendAll(lines)
                connRows += lines.size
                stalledRows += addedStall
                lastOkAt = System.currentTimeMillis()
                if (addedStall > 0) stalledGeneration.addAndGet(addedStall.toLong())
            }
        } finally {
            // 连接表失败也要把这一轮 60 秒权重快照落盘。
            if (refreshed && running) writeWeightsSnapshot()
        }
    }

    private fun maybeRefreshHeavy(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastHeavyAt != 0L && now - lastHeavyAt < HEAVY_MS) return false
        // 失败也占满 60 秒,避免连接表正常、权重接口失败时每 10 秒打一次重请求。
        lastHeavyAt = now
        if (!running) return false

        val proxyBody = httpGet("$CONTROLLER/proxies")
        val proxies = if (proxyBody == null) {
            null
        } else {
            try {
                DiagJson.parseProxies(proxyBody)
            } catch (e: Exception) {
                Log.w("diag: bad /proxies json (${e.javaClass.simpleName})")
                null
            }
        }
        val previous = heavy
        val proxyMap = proxies ?: previous?.proxies ?: emptyMap()
        val groups = proxyMap.filter { it.value.type.equals("Smart", ignoreCase = true) }.keys
        val weights = LinkedHashMap(previous?.weights ?: emptyMap())
        for (group in groups) {
            if (!running) return false
            val encoded = URLEncoder.encode(group, "UTF-8").replace("+", "%20")
            val body = httpGet("$CONTROLLER/group/$encoded/weights") ?: continue
            try {
                weights[group] = DiagJson.parseGroupWeights(body)
            } catch (e: Exception) {
                Log.w("diag: bad /group weights json (${e.javaClass.simpleName})")
            }
        }
        if (!running) return false
        if (proxies != null || weights.isNotEmpty()) {
            heavy = Heavy(weights, proxyMap)
        }
        return heavy?.weights?.isNotEmpty() == true
    }

    private fun writeWeightsSnapshot() {
        val snap = heavy ?: return
        if (snap.weights.isEmpty()) return
        // 启动/重启瞬间内核还没解析出组内成员,此时 /group/x/weights 返回空数组。
        // 实测这种"全组同一次快照都为空"的情形被下游当成"策略组内没有任何节点"反复报 bug,
        // 而其中两个组正常时各有 110 / 33 个成员。因此:
        //   ① 全部组都空 → 整条快照丢掉,不落盘
        //   ② 单个组这次空、上次非空 → 视为未就绪,沿用上次的成员列表
        //   ③ 历史上一直是空的组(真·空组)照原样写空,保留真实信号
        val rawAllEmpty = snap.weights.values.all { it.isEmpty() }
        if (rawAllEmpty) return
        val merged = LinkedHashMap<String, List<DiagJson.Ranked>>(snap.weights.size)
        for ((group, list) in snap.weights) {
            if (list.isNotEmpty()) {
                merged[group] = list
                continue
            }
            val prev = lastWeights?.get(group)
            merged[group] = if (!prev.isNullOrEmpty()) prev else list
        }
        // 实测:全量快照 8.4KB/次,每分钟写 = 12MB/天,比连接记录还大。
        // 连接记录本身已带 alts_top3(每分钟刷新的内存快照,细粒度够用),这里只做"参考底稿":
        //   ① 最快 5 分钟一次 ② 排名完全没变就跳过
        val now = SystemClock.elapsedRealtime()
        if (now - lastWeightsAt < WEIGHTS_SNAPSHOT_MS) return
        if (merged == lastWeights) return
        lastWeightsAt = now
        lastWeights = merged
        weightStore.appendAll(
            listOf(
                DiagJson.weightsRecord(
                    ts = DiagJson.formatTs(System.currentTimeMillis()),
                    groups = merged,
                    proxies = snap.proxies,
                )
            )
        )
    }

    private data class Cand(
        val node: String,
        val weight: Double?,
        val delay: Long?,
        val alive: Boolean?,
    )

    private data class Match(
        val chosenWeight: Double?,
        val chosenDelayMs: Int?,
        val alts: JSONArray?,
    )

    /**
     * 候选与已选节点的权重/延迟。权重接口只覆盖 Smart 组,其余组(Select/UrlTest 等)
     * 用成员列表 + /proxies 的延迟折算,保证"是否本可更优"对所有流量都能判定。
     * 拿不到就返回 null,调用方省略字段,不填假数。alts 不含当前选中的节点。
     */
    private fun matchAlternatives(
        node: String,
        chains: List<String>,
        snap: Heavy?,
    ): Match {
        if (snap == null || node.isEmpty()) return Match(null, null, null)
        val group = pickGroup(chains, node, snap) ?: return Match(null, null, null)
        val ranked = snap.weights[group]
        val cands = ArrayList<Cand>()
        // 候选只能是"真节点":/proxies 里带 members 的是策略组(Select/UrlTest/Smart 等),
        // 它们会作为成员出现在父组的 members 或 weights 里。实测一度有 19.2% 的 alts 条目是组名,
        // 导致"实选 vs 候选最优"永远对不上,误选分析整套失效。
        fun isGroup(name: String): Boolean =
            (snap.proxies[name]?.members?.isNotEmpty() == true)
        if (!ranked.isNullOrEmpty()) {
            for (item in ranked) {
                if (isGroup(item.node)) continue
                val proxy = snap.proxies[item.node]
                cands.add(Cand(item.node, item.weight, proxy?.lastDelayMs, proxy?.alive))
            }
        } else {
            val info = snap.proxies[group] ?: return Match(null, null, null)
            for (member in info.members) {
                if (isGroup(member)) continue
                val proxy = snap.proxies[member] ?: continue
                cands.add(Cand(member, null, proxy.lastDelayMs, proxy.alive))
            }
        }
        if (cands.isEmpty()) return Match(null, null, null)

        var chosenWeight: Double? = null
        var chosenDelay: Int? = null
        val others = ArrayList<Cand>(cands.size)
        for (cand in cands) {
            if (cand.node == node) {
                chosenWeight = cand.weight
                cand.delay?.takeIf { it > 0 }?.let { chosenDelay = it.toInt() }
                continue
            }
            others.add(cand)
        }
        // Smart 组按权重降序;无权重接口的组按延迟升序(未探测/超时的排最后)
        others.sortWith(
            compareByDescending<Cand> { it.weight ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.delay?.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }
        )
        if (others.isEmpty()) return Match(chosenWeight, chosenDelay, null)
        val alts = JSONArray()
        val limit = minOf(3, others.size)
        for (i in 0 until limit) {
            val item = others[i]
            val obj = JSONObject()
            obj.put("node", item.node)
            if (item.weight != null) obj.put("weight", item.weight)
            if (item.delay != null && item.delay > 0) obj.put("last_delay_ms", item.delay)
            if (item.alive != null) obj.put("alive", item.alive)
            alts.put(obj)
        }
        return Match(chosenWeight, chosenDelay, alts)
    }

    /**
     * 找出该连接所属的策略组。组名优先从 chains 里取,只在 /proxies 里存在且非空才算数;
     * 兜底按成员归属反查。不再限定 Smart 组 —— 非 Smart 组没有权重接口,但照样有候选和延迟。
     */
    private fun pickGroup(chains: List<String>, node: String, snap: Heavy): String? {
        for (i in 1 until chains.size) {
            val name = chains[i]
            val info = snap.proxies[name] ?: continue
            if (info.members.isEmpty()) continue
            return name
        }
        // 成员归属反查:走预建索引(node -> 组名),避免对每条连接做 O(组数 × 成员数) 扫描。
        // 索引随快照刷新重建,快照没换就复用。
        if (memberIndexSource !== snap.proxies) {
            val idx = HashMap<String, String>(snap.proxies.size * 4)
            for ((name, info) in snap.proxies) {
                if (info.members.isEmpty()) continue
                for (member in info.members) idx.putIfAbsent(member, name)
            }
            memberIndex = idx
            memberIndexSource = snap.proxies
        }
        return memberIndex?.get(node)
    }

    private fun readNetType(): String {
        return try {
            val cm = appContext.getSystemService<ConnectivityManager>() ?: return "none"
            var vpnKind: String? = null
            val networks = cm.allNetworks
            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val kind = transportKind(caps) ?: continue
                val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                if (!vpn) return kind
                if (vpnKind == null) vpnKind = kind
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                if (caps != null) transportKind(caps)?.let { return it }
            }
            vpnKind ?: "none"
        } catch (e: Exception) {
            Log.w("diag: net type failed: ${e.message}")
            "none"
        }
    }

    private fun transportKind(caps: NetworkCapabilities): String? = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> null
    }

    private fun readScreen(): String {
        return try {
            val pm = appContext.getSystemService<PowerManager>() ?: return "unknown"
            if (pm.isInteractive) "on" else "off"
        } catch (e: Exception) {
            Log.w("diag: screen state failed: ${e.message}")
            "unknown"
        }
    }

    private fun httpGet(url: String): String? {
        if (!running) return null
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            if (running) Log.w("diag: $url failed: ${e.message}")
            return null
        }
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        activeConn = conn
        return try {
            if (!running) return null
            val code = conn.responseCode
            if (!running) {
                null
            } else if (code !in 200..299) {
                Log.w("diag: $url -> HTTP $code")
                null
            } else {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (running) Log.w("diag: $url failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
            if (activeConn === conn) activeConn = null
        }
    }

    companion object {
        private const val CONTROLLER = "http://127.0.0.1:9090"
        private const val INTERVAL_MS = 10_000L
        private const val WEIGHTS_SNAPSHOT_MS = 5 * 60_000L
        private const val HEAVY_MS = 60_000L
        /** 状态快照间隔。30 分钟一次足够定位原因,又几乎不耗 CPU。 */
        private const val STATE_INTERVAL_MS = 30 * 60_000L
        private const val HTTP_TIMEOUT_MS = 3_000
        private val gate = Any()

        @Volatile
        private var current: RoutingSampler? = null

        @JvmStatic
        fun start(context: Context) {
            synchronized(gate) {
                if (current != null) return
                val sampler = RoutingSampler(context.applicationContext)
                current = sampler
                sampler.launch()
                // 内存采集跟隧道同生命周期:隧道起来才写 mem.jsonl,停下就一条都不写
                try {
                    MemSampler.start(context.applicationContext)
                } catch (e: Exception) {
                    Log.w("diag: mem sampler start failed: ${e.javaClass.simpleName}")
                }
            }
        }

        @JvmStatic
        fun stop() {
            val sampler = synchronized(gate) {
                current.also { current = null }
            }
            try {
                MemSampler.stop()
            } catch (e: Exception) {
                Log.w("diag: mem sampler stop failed: ${e.javaClass.simpleName}")
            }
            sampler?.shutdown()
        }

        fun stalledGeneration(): Long {
            return synchronized(gate) { current?.stalledGeneration?.get() ?: 0L }
        }
    }
}
