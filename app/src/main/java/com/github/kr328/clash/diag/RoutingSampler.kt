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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * 隧道运行期间每 10 秒采一次内核连接表,写成 routing.jsonl。
 * 权重全量每 60 秒另写 weights.jsonl,连接记录只留 alts_top3。
 * 由 [start] 拉起协程,由 [stop] 取消;停止后没有残留定时器。
 */
class RoutingSampler private constructor(
    private val appContext: Context,
) {
    private data class Track(
        val down: Long,
        val up: Long,
        val lastChangeTs: Long,
    )

    private data class Heavy(
        val weights: Map<String, List<DiagJson.Ranked>>,
        val proxies: Map<String, DiagJson.ProxyInfo>,
    )

    private val routingStore = DiagStore(appContext, "routing.jsonl")
    private val weightStore = DiagStore(appContext, "weights.jsonl")
    private val tracks = HashMap<String, Track>()
    private val trackLock = Any()

    @Volatile private var running = true
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var activeConn: HttpURLConnection? = null
    @Volatile private var heavy: Heavy? = null
    private var lastWeightsAt = 0L
    private var lastWeights: Map<String, List<DiagJson.Ranked>>? = null
    @Volatile private var lastHeavyAt = 0L

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

            synchronized(trackLock) {
                if (!running) return
                val seen = HashSet<String>(conns.size)
                for (conn in conns) {
                    if (conn.id.isEmpty()) continue
                    seen.add(conn.id)
                    val prev = tracks[conn.id]
                    val grew = prev != null && (conn.download > prev.down || conn.upload > prev.up)
                    val lastChange = when {
                        prev == null -> nowMono
                        grew -> nowMono
                        else -> prev.lastChangeTs
                    }
                    tracks[conn.id] = Track(conn.download, conn.upload, lastChange)
                    // 第一次见到这条连接没有历史可比,idle 记 0;之后只在字节增加时清零。
                    val idleS = if (prev == null) 0.0 else (nowMono - lastChange) / 1000.0
                    val node = DiagJson.nodeName(conn.chains)
                    val (chosen, alts) = matchAlternatives(node, conn.chains, snap)
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
                            stalled = idleS > STALL_IDLE_S,
                            netType = netType,
                            screen = screen,
                            chosenWeight = chosen,
                            altsTop3 = alts,
                        )
                    )
                }
                // 这一轮快照里已经消失的连接,从追踪表删掉,避免表只增不减。
                val iterator = tracks.keys.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next() !in seen) iterator.remove()
                }
            }

            if (running) routingStore.appendAll(lines)
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

    /**
     * 权重和延迟拿不到就返回 null,调用方省略字段,不填假数。
     * alts_top3 不含当前选中的节点,按 weight 降序最多 3 条。
     */
    private fun matchAlternatives(
        node: String,
        chains: List<String>,
        snap: Heavy?,
    ): Pair<Double?, JSONArray?> {
        if (snap == null || node.isEmpty()) return null to null
        val group = pickGroup(chains, node, snap) ?: return null to null
        val ranked = snap.weights[group].orEmpty()
        if (ranked.isEmpty()) return null to null
        var chosen: Double? = null
        val others = ArrayList<DiagJson.Ranked>(ranked.size)
        for (item in ranked) {
            if (item.node == node) {
                chosen = item.weight
                continue
            }
            others.add(item)
        }
        others.sortByDescending { it.weight }
        if (others.isEmpty()) return chosen to null
        val alts = JSONArray()
        val limit = minOf(3, others.size)
        for (i in 0 until limit) {
            val item = others[i]
            val proxy = snap.proxies[item.node]
            val obj = JSONObject()
            obj.put("node", item.node)
            obj.put("weight", item.weight)
            if (proxy?.lastDelayMs != null) obj.put("last_delay_ms", proxy.lastDelayMs)
            if (proxy?.alive != null) obj.put("alive", proxy.alive)
            alts.put(obj)
        }
        return chosen to alts
    }

    private fun pickGroup(chains: List<String>, node: String, snap: Heavy): String? {
        for (i in 1 until chains.size) {
            val name = chains[i]
            if (snap.weights.containsKey(name)) return name
        }
        for ((name, info) in snap.proxies) {
            if (!info.type.equals("Smart", ignoreCase = true)) continue
            if (node in info.members && snap.weights.containsKey(name)) return name
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
        private const val STALL_IDLE_S = 5.0
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
    }
}
