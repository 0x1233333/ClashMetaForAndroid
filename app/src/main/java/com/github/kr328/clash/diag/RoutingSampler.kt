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

    @Volatile private var running = true
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var activeConn: HttpURLConnection? = null
    @Volatile private var heavy: Heavy? = null
    private var lastWeightsAt = 0L
    private var lastWeights: Map<String, List<DiagJson.Ranked>>? = null
    @Volatile private var lastHeavyAt = 0L
    private val stalledGeneration = AtomicLong(0)

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
        heavy = null
        scope = null
    }

    private suspend fun runLoop() {
        while (running && coroutineContext.isActive) {
            val started = SystemClock.elapsedRealtime()
            try {
                sampleOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("diag: sample round failed: ${e.message}")
            }
            if (!running || !coroutineContext.isActive) break
            val wait = INTERVAL_MS - (SystemClock.elapsedRealtime() - started)
            if (wait > 0) delay(wait)
        }
    }

    private fun sampleOnce() {
        if (!running) return
        val refreshed = maybeRefreshHeavy()
        try {
            if (!running) return
            val body = httpGet("$CONTROLLER/connections")
            if (body == null) {
                if (running) Log.w("diag: skip round, /connections unavailable")
                return
            }
            if (!running) return
            val conns = try {
                DiagJson.parseConnections(body)
            } catch (e: Exception) {
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
                    // dormant(长时间无增长)与 nodata(从未见增长)不写入:
                    // 它们既无传输活动也无判定价值,写下来只会淹没有效样本(实测占约 60%)。
                    // 追踪表在上方已更新,这里跳过不影响后续判定。
                    if (state == StallJudge.DORMANT || state == StallJudge.NODATA) continue
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
        // 实测:全量快照 8.4KB/次,每分钟写 = 12MB/天,比连接记录还大。
        // 连接记录本身已带 alts_top3(每分钟刷新的内存快照,细粒度够用),这里只做"参考底稿":
        //   ① 最快 5 分钟一次 ② 排名完全没变就跳过
        val now = SystemClock.elapsedRealtime()
        if (now - lastWeightsAt < WEIGHTS_SNAPSHOT_MS) return
        if (snap.weights == lastWeights) return
        lastWeightsAt = now
        lastWeights = snap.weights
        weightStore.appendAll(
            listOf(
                DiagJson.weightsRecord(
                    ts = DiagJson.formatTs(System.currentTimeMillis()),
                    groups = snap.weights,
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
        if (!ranked.isNullOrEmpty()) {
            for (item in ranked) {
                val proxy = snap.proxies[item.node]
                cands.add(Cand(item.node, item.weight, proxy?.lastDelayMs, proxy?.alive))
            }
        } else {
            val info = snap.proxies[group] ?: return Match(null, null, null)
            for (member in info.members) {
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
        for ((name, info) in snap.proxies) {
            if (info.members.isEmpty()) continue
            if (node in info.members) return name
        }
        return null
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
            }
        }

        @JvmStatic
        fun stop() {
            val sampler = synchronized(gate) {
                current.also { current = null }
            }
            sampler?.shutdown()
        }

        fun stalledGeneration(): Long {
            return synchronized(gate) { current?.stalledGeneration?.get() ?: 0L }
        }
    }
}
