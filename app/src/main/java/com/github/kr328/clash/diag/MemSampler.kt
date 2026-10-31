package com.github.kr328.clash.diag

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 隧道运行期间每 60 秒采一次内存,写成 mem.jsonl;
 * 打包/上传/重载等关键时刻由调用方 [markEvent] 写 mem_events.jsonl。
 *
 * 目的只有一个:出问题时能直接看出「什么时候涨内存、涨到多少、当时在干什么」。
 * 所以这里只读两个东西:
 *   ① 两个进程的 /proc/<pid>/status(VmRSS/VmHWM/RssAnon/VmSwap),一次 1~2KB;
 *   ② 内核 /memstats 与 /connections 的条数 —— 都是本机 127.0.0.1,超时就当取不到。
 * 不读 /proc/net 下的内容,不遍历其它进程(pid 解析有缓存,只在进程重启后重扫一次)。
 *
 * 生命周期跟隧道绑:由 [RoutingSampler.start] / [RoutingSampler.stop] 拉起和停止。
 * 所有采集失败都只写 null,绝不向上抛 —— 采集出问题不能影响隧道和上传。
 */
object MemSampler {
    private const val INTERVAL_MS = 60_000L

    /** 采集文件上限。mem.jsonl 一条约 300B × 1440 条/天 ≈ 0.4MB/天,32MB 足够几十天。 */
    private const val CAP_BYTES = 32L * 1024L * 1024L

    /** 超过这个 RSS 就额外记一条 peak(带 smaps_rollup 前 20 行)。 */
    private const val PEAK_RSS_KB = 900L * 1024L

    private const val CONTROLLER = "http://127.0.0.1:9090"
    private const val MEM_TIMEOUT_MS = 2_000
    private const val CONN_TIMEOUT_MS = 3_000
    private const val SMAPS_MAX_LINES = 20

    /** 读 /proc/<pid>/status 里的一行,单位 KB。 */
    private data class ProcMem(
        val rssKb: Long?,
        val hwmKb: Long?,
        val anonKb: Long?,
        val swapKb: Long?,
    )

    private data class MemPair(
        val app: ProcMem?,
        val bg: ProcMem?,
    )

    private val gate = Any()

    /**
     * 两个文件共用一把锁:事件可能来自上传线程(pack/upload 前后),
     * 采样来自采集协程,同一时刻只允许一条在追加。DiagStore 自己也有一把锁,这里只是把它套在外面。
     */
    private val writeLock = Any()

    @Volatile private var running = false
    @Volatile private var appContext: Context? = null
    @Volatile private var packageName: String? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var memStore: DiagStore? = null
    @Volatile private var eventStore: DiagStore? = null

    /** 上一条事件里的 rss_kb,用来算 delta_rss_kb。 */
    @Volatile private var lastEventRssKb: Long? = null

    /** 已写入 mem.jsonl 的条数:只用于日志节流与"是否真的在写"的自证。 */
    @Volatile private var sampleCount = 0

    /** 解析出来的对端 pid,缓存;进程重启(/proc/<pid> 消失)后重扫一次。 */
    @Volatile private var appPid = 0
    @Volatile private var bgPid = 0

    /** 本进程是不是主进程(包名进程)。在 [start] 里定一次。 */
    @Volatile private var selfIsMain = false

    // ── 生命周期 ────────────────────────────────────────────────────────────

    @JvmStatic
    fun start(context: Context) {
        val app = context.applicationContext
        if (!DiagConfig.ENABLED) return

        synchronized(gate) {
            if (running) return
            appContext = app
            packageName = app.packageName
            selfIsMain = readCmdline(android.os.Process.myPid()) == app.packageName
            appPid = 0
            bgPid = 0
            lastEventRssKb = null
            // DiagStore 构造时会自愈超限文件;构造失败不影响隧道,只是这一路不采
            try {
                memStore = DiagStore(app, "mem.jsonl", maxBytes = CAP_BYTES, metaLine = DiagJson.MEM_META)
                eventStore = DiagStore(app, "mem_events.jsonl", maxBytes = CAP_BYTES, metaLine = DiagJson.MEM_EVENTS_META)
            } catch (e: Exception) {
                Log.w("mem: store init failed: ${e.javaClass.simpleName}")
            }
            running = true
            // 起来了就打一条:没有日志时无法区分"没启动"和"启动了但没写"
            Log.w("diag: mem sampler started interval=${INTERVAL_MS}ms pid=${android.os.Process.myPid()} selfIsMain=$selfIsMain")
            val created = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = created
            created.launch { runLoop() }
        }
    }

    @JvmStatic
    fun stop() {
        var created: CoroutineScope? = null
        synchronized(gate) {
            running = false
            created = scope
            scope = null
        }
        try {
            created?.cancel()
        } catch (e: Exception) {
            Log.w("mem: stop failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun runLoop() {
        // 降优先级:采集是后台活,不该和前台解码/滚动抢 CPU
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        } catch (e: Exception) {
            Log.w("mem: set thread priority failed: ${e.javaClass.simpleName}")
        }
        while (running && coroutineContext.isActive) {
            val started = SystemClock.elapsedRealtime()
            try {
                sampleOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("mem: sample failed: ${e.javaClass.simpleName}")
            }
            val spent = SystemClock.elapsedRealtime() - started
            try {
                delay((INTERVAL_MS - spent).coerceAtLeast(5_000L))
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    // ── 采样 ────────────────────────────────────────────────────────────────

    private fun sampleOnce() {
        val context = appContext ?: return
        val store = memStore ?: return

        val pair = readPair()
        // 隧道没起时内核 API 必然连不上, 直接跳过, 省两次超时等待
        val up = vpnUp(context)
        val core = if (up) readCoreMem() else null
        val conns = if (up) readConnCount() else null

        val rec = JSONObject()
        try {
            rec.put("ts", DiagJson.formatTs(System.currentTimeMillis()))
            // 这一行是哪个进程写的: 跨进程读 /proc/<pid>/status 在 Android 11+ 被拦,
            // 所以只有 self 那一侧的 rss 是真实值, 分析时按 self 分组
            rec.put("self", if (selfIsMain) "app" else "bg")
            rec.putNullable("app_rss_kb", pair.app?.rssKb)
            rec.putNullable("bg_rss_kb", pair.bg?.rssKb)
            rec.putNullable("app_hwm_kb", pair.app?.hwmKb)
            rec.putNullable("bg_hwm_kb", pair.bg?.hwmKb)
            rec.putNullable("app_anon_kb", pair.app?.anonKb)
            rec.putNullable("bg_anon_kb", pair.bg?.anonKb)
            rec.putNullable("app_swap_kb", pair.app?.swapKb)
            rec.putNullable("bg_swap_kb", pair.bg?.swapKb)
            rec.putNullable("sys_avail_mb", sysAvailMb(context))
            rec.put("screen_on", screenOn(context))
            rec.put("charging", charging(context))
            rec.put("vpn_up", up)
            rec.putNullable("conns", conns)
            rec.putNullable("core_alloc_mb", mbOf(core, "alloc"))
            rec.putNullable("core_heap_inuse_mb", mbOf(core, "heap_inuse"))
            rec.putNullable("core_sys_mb", mbOf(core, "sys"))
            rec.putNullable("core_num_gc", num(core, "num_gc")?.toLong())
            rec.putNullable("core_pause_ms", num(core, "pause_total_ns")?.let { DiagJson.round1(it / 1_000_000.0) })
            rec.put("note", "")
        } catch (e: Exception) {
            Log.w("mem: build record failed: ${e.javaClass.simpleName}")
            return
        }

        append(store, rec)
        sampleCount += 1
        if (sampleCount <= 3 || sampleCount % 30 == 0)
            Log.w("diag: mem sample #$sampleCount self=${if (selfIsMain) "app" else "bg"} app=${pair.app?.rssKb}kB bg=${pair.bg?.rssKb}kB coreSys=${mbOf(core, "sys")}MB conns=$conns file=${java.io.File(context.filesDir, "diag/mem.jsonl").length()}B")
        checkPeak(pair)
    }

    /**
     * 峰值永迹:任一进程 RSS 越过 [PEAK_RSS_KB] 就补一条 evt=peak,
     * extra 里带当时该进程 /proc/<pid>/smaps_rollup 的前 20 行,读不到就写 unreadable。
     */
    private fun checkPeak(pair: MemPair) {
        val appKb = pair.app?.rssKb ?: 0L
        val bgKb = pair.bg?.rssKb ?: 0L
        if (appKb <= PEAK_RSS_KB && bgKb <= PEAK_RSS_KB) return

        val (role, pid, mem, rss) = when {
            appKb >= bgKb -> Quad("app", pidForMain(), pair.app, appKb)
            else -> Quad("bg", pidForBg(), pair.bg, bgKb)
        }
        val head = if (pid != null) smapsHead(pid!!) else "smaps_rollup unreadable"
        val extra = "role=$role pid=${pid ?: -1} rss_kb=$rss\n$head"
        // peak 走同一个 append 通道,delta_rss_kb 依旧相对上一条事件,不做特殊化
        writeEvent("peak", mem, extra)
    }

    private data class Quad(
        val role: String,
        val pid: Int?,
        val mem: ProcMem?,
        val rssKb: Long,
    )

    // ── 事件(供 AutoUploader 调用) ──────────────────────────────────────────

    /**
     * 写一条事件。rss/hwm/anon 取**调用方所在进程**(打包/上传都发生在 :background 进程,
     * 也就是真正会涨内存的那个进程);取不到写 null。
     *
     * 该函数任何失败都只记日志,不向调用方抛 —— 上传流程不能被采集拖垮。
     */
    @JvmStatic
    fun markEvent(context: Context?, evt: String, extra: String = "") {
        try {
            val app = context?.applicationContext ?: appContext ?: return
            val store = eventStoreFor(app)
            val mem = readProcMem(android.os.Process.myPid())
            appendEvent(store, evt, mem, extra)
        } catch (e: Exception) {
            Log.w("mem: event $evt failed: ${e.javaClass.simpleName}")
        }
    }

    private fun writeEvent(evt: String, mem: ProcMem?, extra: String) {
        try {
            val store = eventStore ?: return
            appendEvent(store, evt, mem, extra)
        } catch (e: Exception) {
            Log.w("mem: event $evt failed: ${e.javaClass.simpleName}")
        }
    }

    private fun appendEvent(store: DiagStore, evt: String, mem: ProcMem?, extra: String) {
        val rec = JSONObject()
        rec.put("ts", DiagJson.formatTs(System.currentTimeMillis()))
        rec.put("evt", evt)
        rec.putNullable("rss_kb", mem?.rssKb)
        rec.putNullable("hwm_kb", mem?.hwmKb)
        rec.putNullable("anon_kb", mem?.anonKb)
        val prev = lastEventRssKb
        val rss = mem?.rssKb
        rec.putNullable("delta_rss_kb", if (prev != null && rss != null) rss - prev else null)
        rec.put("extra", extra.take(4096))
        if (rss != null) lastEventRssKb = rss
        append(store, rec)
    }

    /** 采集器没起来时(手动上传)也要能写事件:按需构造一个同参数的 store。 */
    private fun eventStoreFor(context: Context): DiagStore {
        eventStore?.let { return it }
        synchronized(gate) {
            eventStore?.let { return it }
            val created = DiagStore(
                context.applicationContext,
                "mem_events.jsonl",
                maxBytes = CAP_BYTES,
                metaLine = DiagJson.MEM_EVENTS_META,
            )
            eventStore = created
            return created
        }
    }

    private fun append(store: DiagStore, rec: JSONObject) {
        synchronized(writeLock) {
            store.appendAll(listOf(rec.toString()))
        }
    }

    // ── /proc ───────────────────────────────────────────────────────────────

    private fun readPair(): MemPair {
        return try {
            val app = pidForMain()?.let { readProcMem(it) }
            val bg = pidForBg()?.let { readProcMem(it) }
            MemPair(app, bg)
        } catch (e: Exception) {
            Log.w("mem: read pair failed: ${e.javaClass.simpleName}")
            MemPair(null, null)
        }
    }

    private fun pidForMain(): Int? {
        if (selfIsMain) return android.os.Process.myPid()
        val cached = appPid
        if (cached > 0 && File("/proc/$cached/cmdline").exists()) return cached
        val pkg = packageName ?: return null
        val found = scanPid(pkg) ?: scanViaAm(pkg)
        appPid = found ?: 0
        return found
    }

    private fun pidForBg(): Int? {
        if (!selfIsMain) return android.os.Process.myPid()
        val cached = bgPid
        if (cached > 0 && File("/proc/$cached/cmdline").exists()) return cached
        val pkg = packageName ?: return null
        val found = scanPid("$pkg:background") ?: scanViaAm("$pkg:background")
        bgPid = found ?: 0
        return found
    }

    /** 极轻量的 cmdline 读取:只取前 256 字节,不整文件读。 */
    private fun readCmdline(pid: Int): String? = try {
        val file = File("/proc/$pid/cmdline")
        if (!file.exists()) null
        else FileInputStream(file).use { input ->
            val buf = ByteArray(256)
            val n = input.read(buf)
            if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8).trim('\u0000', ' ', '\n')
        }
    } catch (e: Exception) {
        null
    }

    /** 扫 /proc,找 cmdline 完全等于 [target] 的 pid。仅在缓存失效时调用(进程重启)。 */
    private fun scanPid(target: String): Int? {
        return try {
            val self = android.os.Process.myPid()
            val dirs = File("/proc").listFiles() ?: return null
            var found: Int? = null
            for (dir in dirs) {
                val name = dir.name
                if (name.isEmpty() || !name[0].isDigit()) continue
                val pid = name.toIntOrNull() ?: continue
                if (pid == self) continue
                if (readCmdline(pid) == target) {
                    found = pid
                    break
                }
            }
            found
        } catch (e: Exception) {
            null
        }
    }

    /** /proc 扫不到时的兜底(Android 10 及更早 runningAppProcesses 能列出本 App 的全部进程)。 */
    @Suppress("DEPRECATION")
    private fun scanViaAm(target: String): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return null
            val am = appContext?.getSystemService<ActivityManager>() ?: return null
            am.runningAppProcesses?.firstOrNull { it.processName == target }?.pid
        } catch (e: Exception) {
            null
        }
    }

    private fun readProcMem(pid: Int): ProcMem? {
        return try {
            val text = File("/proc/$pid/status").readText()
            var rss: Long? = null
            var hwm: Long? = null
            var anon: Long? = null
            var swap: Long? = null
            for (line in text.lineSequence()) {
                when {
                    line.startsWith("VmRSS:") -> rss = kbOf(line)
                    line.startsWith("VmHWM:") -> hwm = kbOf(line)
                    line.startsWith("RssAnon:") -> anon = kbOf(line)
                    line.startsWith("VmSwap:") -> swap = kbOf(line)
                }
            }
            if (rss == null && hwm == null && anon == null && swap == null) null
            else ProcMem(rss, hwm, anon, swap)
        } catch (e: Exception) {
            null
        }
    }

    private fun kbOf(line: String): Long? = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()

    private fun smapsHead(pid: Int): String {
        return try {
            val file = File("/proc/$pid/smaps_rollup")
            if (!file.exists() || !file.canRead()) return "smaps_rollup unreadable"
            val sb = StringBuilder()
            var lines = 0
            file.bufferedReader().use { reader ->
                while (lines < SMAPS_MAX_LINES) {
                    val line = reader.readLine() ?: break
                    sb.append(line).append('\n')
                    lines++
                }
            }
            if (sb.isEmpty()) "smaps_rollup unreadable" else sb.toString().trimEnd('\n')
        } catch (e: Exception) {
            "smaps_rollup unreadable"
        }
    }

    // ── 设备状态 ────────────────────────────────────────────────────────────

    private fun sysAvailMb(context: Context): Long? = try {
        val am = context.getSystemService<ActivityManager>()
        if (am == null) null
        else {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem / (1024 * 1024)
        }
    } catch (e: Exception) {
        null
    }

    private fun screenOn(context: Context): Boolean = try {
        val power = context.getSystemService<PowerManager>()
        power != null && power.isInteractive
    } catch (e: Exception) {
        false
    }

    private fun charging(context: Context): Boolean = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
    } catch (e: Exception) {
        false
    }

    /**
     * VPN 起来后 activeNetwork 就是 VPN 网络,所以先看 active,再兜底遍历全部网络:
     * 只要有一个带 TRANSPORT_VPN 的网络就算 up。
     */
    private fun vpnUp(context: Context): Boolean {
        return try {
            val cm = context.getSystemService<ConnectivityManager>()
            if (cm == null) false
            else {
                var up = false
                val active = cm.activeNetwork
                val caps = if (active != null) cm.getNetworkCapabilities(active) else null
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    up = true
                } else {
                    for (network in cm.allNetworks) {
                        val c = cm.getNetworkCapabilities(network) ?: continue
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            up = true
                            break
                        }
                    }
                }
                up
            }
        } catch (e: Exception) {
            false
        }
    }

    // ── 内核 API ────────────────────────────────────────────────────────────

    /**
     * 内核 /memstats(core/.../hub/route/memstats.go)。字段名固定,单位一律字节,
     * 由 [mbOf] 换算成 MB;接口没起或超时(2s)就整组 null。
     */
    private fun readCoreMem(): JSONObject? {
        val body = httpGet("$CONTROLLER/memstats", MEM_TIMEOUT_MS) ?: return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    private fun readConnCount(): Long? {
        val body = httpGet("$CONTROLLER/connections", CONN_TIMEOUT_MS) ?: return null
        return try {
            JSONObject(body).optJSONArray("connections")?.let { it.length().toLong() }
        } catch (e: Exception) {
            null
        }
    }

    /** 内核 /memstats 一律返回字节,换算成 MB 并保留一位小数。 */
    private fun mbOf(root: JSONObject?, key: String): Double? =
        num(root, key)?.let { DiagJson.round1(it / (1024.0 * 1024.0)) }

    private fun num(root: JSONObject?, vararg keys: String): Double? {
        if (root == null) return null
        for (key in keys) {
            if (!root.has(key) || root.isNull(key)) continue
            val value = root.optDouble(key, Double.NaN)
            if (!value.isNaN()) return value
        }
        return null
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            val opened = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
            }
            conn = opened
            if (opened.responseCode !in 200..299) null
            else opened.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ── JSON 小工具 ─────────────────────────────────────────────────────────

    /** org.json 的 put(key, null) 会删字段,空值必须显式写 JSONObject.NULL。 */
    private fun JSONObject.putNullable(key: String, value: Long?) {
        put(key, if (value == null) JSONObject.NULL else value)
    }

    private fun JSONObject.putNullable(key: String, value: Double?) {
        put(key, if (value == null) JSONObject.NULL else value)
    }
}
