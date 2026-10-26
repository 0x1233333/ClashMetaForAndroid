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
import android.os.StatFs
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 每轮采集把「App 自身 + 内核 + 各组 + 采样器 + 采集文件」的整体状态写一行 state.jsonl。
 *
 * 为什么要有它:只有选路/权重数据时,看到"某个节点占了 60% 流量"这类现象无法判断原因 ——
 * 不知道当时内核是否活着、配置是否生效、组是否有成员、采集文件是否卡住、采样是否在掉轮。
 * 这些状态全部落盘后,任何一条数据异常都能在同一个时间点自证原因。
 *
 * CPU 约束:
 *   · 默认 30 分钟一次,状态没变化就不写(状态串比对)
 *   · 全部 IO 在调用方的后台协程里做,单次 HTTP 超时 1.5s,失败即降级为 false/null,不重试
 *   · 组/节点名一律先哈希(与 routing 里 node 同一算法),不落明文
 */
internal class StateCollector(private val context: Context) {

    private val store = DiagStore(context, "state.jsonl", metaLine = DiagJson.STATE_META)
    private var lastLine: String? = null
    /**
     * 连续 /version 探测失败次数。跟这个采样器走,隧道重启会新建实例并归零:
     * 启动瞬间内核还没起来只算 1 次,不会把上一次隧道停止时的失败继承过来。
     */
    private var apiFailStreak = 0

    /** 采样器自身的运行统计,由 [RoutingSampler] 传入。 */
    data class SamplerStats(
        val rounds: Long,
        val failedRounds: Long,
        val lastOkAgeS: Long,
        val avgRoundMs: Long,
        val maxRoundMs: Long,
        val heavyRefreshes: Long,
        val connRows: Long,
        val stalledRows: Long,
        val droppedRounds: Long,
    )

    /**
     * 生成一行状态并落盘。trigger 用于区分 start / periodic / stop / manual。
     * 返回 true 表示真的写入了(状态与上次不同)。
     */
    fun capture(trigger: String, stats: SamplerStats, force: Boolean = false): Boolean {
        val line = try {
            buildLine(trigger, stats)
        } catch (e: Exception) {
            Log.w("diag: state snapshot failed: ${e.javaClass.simpleName}")
            return false
        }
        if (line == null) return false
        // 去重键:去掉 ts 之后的整行。
        // 这里原来写的是 line.substringAfter('\n') —— 行尾本来就有换行,取出来恒为空串,
        // 于是同一个 StateCollector 实例写完第一条之后全部被判定"没变化",中段状态永远不落盘
        // (实测每台设备只留了采样器出生/收尾那一两条)。
        val sig = line.trimEnd('\n').replaceFirst(TS_PREFIX, "{")
        if (!force && sig == lastLine) return false
        lastLine = sig
        store.appendAll(listOf(line))
        return true
    }

    private fun buildLine(trigger: String, stats: SamplerStats): String? {
        val ts = DiagJson.formatTs(System.currentTimeMillis())
        val obj = JSONObject()
        obj.put("ts", ts)
        obj.put("type", "state")
        obj.put("trigger", trigger)
        obj.put("app", appBlock())
        obj.put("device", deviceBlock())
        obj.put("network", networkBlock())
        obj.put("power", powerBlock())
        val proxies = httpGetJson("$CONTROLLER/proxies")
        obj.put("core", coreBlock(proxies))
        // 拿不到代理表就写 null。空数组会被当成「一台设备上没有任何策略组」。
        if (proxies == null) obj.put("groups", JSONObject.NULL) else obj.put("groups", groupsBlock(proxies))
        obj.put("sampler", samplerBlock(stats))
        obj.put("files", filesBlock())
        return obj.toString() + "\n"
    }

    // ── App 自身 ────────────────────────────────────────────────────────────
    private fun appBlock(): JSONObject {
        val o = JSONObject()
        o.put("version_name", versionName() ?: JSONObject.NULL)
        o.put("version_code", versionCode() ?: JSONObject.NULL)
        o.put("pid", android.os.Process.myPid())
        o.put("uptime_s", processUptimeS())
        o.put("notifications_enabled", notificationsEnabled())
        o.put("vpn_service_prepared", vpnPrepared())
        return o
    }

    private fun versionName(): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun versionCode(): Int? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 本进程启动至今的秒数,含休眠。
     * [SystemClock.elapsedRealtime] 是手机开机时长,不能拿来当 App 运行了多久。
     * API 24+ 用 [android.os.Process.getStartElapsedRealtime]（与 elapsedRealtime 同一时基）。
     * 更老的系统读 /proc/self/stat 第 22 项 starttime（时钟滴答,不是第 15 项 stime）。
     */
    private fun processUptimeS(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val start = android.os.Process.getStartElapsedRealtime()
            return ((SystemClock.elapsedRealtime() - start) / 1000L).coerceAtLeast(0L)
        }
        return processUptimeFromProc()
    }

    private fun processUptimeFromProc(): Long = try {
        val stat = File("/proc/self/stat").readText()
        val close = stat.lastIndexOf(')')
        if (close <= 0) {
            -1L
        } else {
            val fields = stat.substring(close + 2).split(' ')
            val startTicks = fields.getOrNull(19)?.toLongOrNull()
            if (startTicks == null) {
                -1L
            } else {
                val hz = clockTicksPerSec()
                val startMs = startTicks * 1000L / hz
                ((SystemClock.uptimeMillis() - startMs) / 1000L).coerceAtLeast(0L)
            }
        }
    } catch (e: Exception) {
        -1L
    }

    private fun notificationsEnabled(): Boolean = try {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm?.areNotificationsEnabled() ?: false
    } catch (e: Exception) {
        false
    }

    private fun vpnPrepared(): Boolean = try {
        android.net.VpnService.prepare(context) == null
    } catch (e: Exception) {
        false
    }

    // ── 设备 ────────────────────────────────────────────────────────────────
    private fun deviceBlock(): JSONObject {
        val o = JSONObject()
        o.put("model", Build.MODEL)
        o.put("manufacturer", Build.MANUFACTURER)
        o.put("sdk", Build.VERSION.SDK_INT)
        o.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
        o.put("cores", Runtime.getRuntime().availableProcessors())
        try {
            val am = context.getSystemService<ActivityManager>()
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                o.put("ram_total_mb", mi.totalMem / (1024 * 1024))
                o.put("ram_avail_mb", mi.availMem / (1024 * 1024))
                o.put("low_memory", mi.lowMemory)
            }
        } catch (e: Exception) {
            Log.w("diag: memory info failed: ${e.javaClass.simpleName}")
        }
        try {
            val sf = StatFs(context.filesDir.absolutePath)
            o.put("storage_free_mb", sf.availableBytes / (1024 * 1024))
        } catch (e: Exception) {
            Log.w("diag: storage info failed: ${e.javaClass.simpleName}")
        }
        o.put("process_rss_mb", processRssMb())
        return o
    }

    private fun processRssMb(): Long = try {
        val stat = File("/proc/self/statm").readText().trim().split(' ')
        val pages = stat.getOrNull(1)?.toLongOrNull() ?: 0L
        pages * pageSizeBytes() / (1024 * 1024)
    } catch (e: Exception) {
        0L
    }

    // ── 网络 ────────────────────────────────────────────────────────────────
    private fun networkBlock(): JSONObject {
        val o = JSONObject()
        var type = "none"
        var vpn = false
        var metered: Boolean? = null
        var validated: Boolean? = null
        try {
            val cm = context.getSystemService<ConnectivityManager>()
            if (cm != null) {
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                if (caps != null) {
                    type = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                        else -> "other"
                    }
                    vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            }
        } catch (e: Exception) {
            Log.w("diag: network info failed: ${e.javaClass.simpleName}")
        }
        o.put("type", type)
        o.put("vpn_active", vpn)
        if (metered != null) o.put("metered", metered)
        if (validated != null) o.put("validated", validated)
        return o
    }

    // ── 电源 ────────────────────────────────────────────────────────────────
    private fun powerBlock(): JSONObject {
        val o = JSONObject()
        try {
            val pm = context.getSystemService<PowerManager>()
            if (pm != null) {
                o.put("screen_on", pm.isInteractive)
                o.put("power_save", pm.isPowerSaveMode)
                o.put("ignoring_battery_optimizations", pm.isIgnoringBatteryOptimizations(context.packageName))
            }
        } catch (e: Exception) {
            Log.w("diag: power info failed: ${e.javaClass.simpleName}")
        }
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) o.put("battery_pct", level * 100 / scale)
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (temp != Int.MIN_VALUE) o.put("battery_temp_c", temp / 10.0)
            }
        } catch (e: Exception) {
            Log.w("diag: battery info failed: ${e.javaClass.simpleName}")
        }
        return o
    }

    // ── 内核 ────────────────────────────────────────────────────────────────
    private fun coreBlock(proxies: JSONObject?): JSONObject {
        val o = JSONObject()
        val version = httpGetJson("$CONTROLLER/version")
        val up = version != null
        // 只有一次探测结果是不够的:服务刚启动、内核还在加载配置时 /version 也会失败,
        // 下游据此报"内核不可达"是误报(实测开机后第一行快照 vpn_active=false、groups=[]、
        // rounds=1,而同一分钟的权重快照里各组仍有 110/33/36 个成员)。
        // 因此把"连续失败几次"和"最近一次可达时间"一起落盘,让持续性可以自证。
        if (up) {
            apiFailStreak = 0
            processApiOkAt = DiagJson.formatTs(System.currentTimeMillis())
        } else {
            apiFailStreak++
        }
        o.put("api_up", up)
        o.put("api_fail_streak", apiFailStreak)
        o.put("api_ok_at", processApiOkAt ?: JSONObject.NULL)
        o.put("version", version?.optString("version")?.ifEmpty { null } ?: JSONObject.NULL)
        val configs = httpGetJson("$CONTROLLER/configs")
        if (configs != null) {
            if (configs.has("mode") && !configs.isNull("mode")) {
                o.put("mode", configs.optString("mode", "").ifEmpty { null } ?: JSONObject.NULL)
            }
            // 缺字段时不要写成 false/0,否则下游分不清「关了」和「没读到」。
            if (configs.has("tun") && !configs.isNull("tun")) o.put("tun_enabled", configs.optBoolean("tun"))
            if (configs.has("mixed-port") && !configs.isNull("mixed-port")) {
                o.put("mixed_port", configs.optInt("mixed-port"))
            }
        }
        if (proxies != null) {
            val map = proxies.optJSONObject("proxies") ?: JSONObject()
            var groups = 0
            var nodes = 0
            var alive = 0
            val names = map.keys()
            while (names.hasNext()) {
                val name = names.next()
                val item = map.optJSONObject(name) ?: continue
                if (item.optJSONArray("all")?.length() ?: 0 > 0) {
                    groups++
                } else {
                    nodes++
                    val history = item.optJSONArray("history")
                    if (history != null && history.length() > 0) {
                        val last = history.optJSONObject(history.length() - 1)
                        if (last != null && last.optInt("delay", 0) > 0) alive++
                    }
                }
            }
            o.put("group_count", groups)
            o.put("node_count", nodes)
            o.put("node_alive", alive)
        }
        return o
    }

    // ── 策略组 ──────────────────────────────────────────────────────────────
    /**
     * 只列有成员的策略组。组名一律哈希后才落盘。
     * 调用方在 proxies == null 时不走这里,改写 groups:null。
     * 无成员的条目不列入;原先的 empty 恒为 false,会让人以为「没有空组」。
     */
    private fun groupsBlock(proxies: JSONObject): JSONArray {
        val arr = JSONArray()
        val map = proxies.optJSONObject("proxies") ?: return arr
        val names = map.keys()
        while (names.hasNext()) {
            val name = names.next()
            val item = map.optJSONObject(name) ?: continue
            val members = item.optJSONArray("all") ?: continue
            if (members.length() == 0) continue
            val g = JSONObject()
            g.put("name", DiagJson.nameToken(name))
            g.put("type", item.optString("type", ""))
            val now = item.optString("now", "")
            g.put("now", if (now.isEmpty()) JSONObject.NULL else DiagJson.nameToken(now))
            g.put("members", members.length())
            var alive = 0
            for (i in 0 until members.length()) {
                val m = members.optString(i, "")
                val sub = map.optJSONObject(m) ?: continue
                val history = sub.optJSONArray("history") ?: continue
                if (history.length() > 0) {
                    val last = history.optJSONObject(history.length() - 1)
                    if (last != null && last.optInt("delay", 0) > 0) alive++
                }
            }
            g.put("alive", alive)
            arr.put(g)
        }
        return arr
    }

    // ── 采样器 ──────────────────────────────────────────────────────────────
    private fun samplerBlock(stats: SamplerStats): JSONObject {
        val o = JSONObject()
        o.put("rounds", stats.rounds)
        o.put("failed_rounds", stats.failedRounds)
        o.put("last_ok_age_s", stats.lastOkAgeS)
        o.put("avg_round_ms", stats.avgRoundMs)
        o.put("max_round_ms", stats.maxRoundMs)
        o.put("heavy_refreshes", stats.heavyRefreshes)
        o.put("conn_rows", stats.connRows)
        o.put("stalled_rows", stats.stalledRows)
        o.put("dropped_rounds", stats.droppedRounds)
        o.put("cpu_ms_per_min", cpuMsPerMin())
        return o
    }

    /**
     * 自身进程 CPU 占用(毫秒/分钟),用 /proc/self/stat 的 utime+stime 差分。
     * 进程内第一次没有基线,返回 -1。基线放在 companion:这条计数是进程级的,
     * 而 [RoutingSampler.start] 每次隧道重启都会新建 StateCollector。
     */
    private fun cpuMsPerMin(): Long {
        val jiffies = try {
            val stat = File("/proc/self/stat").readText()
            val close = stat.lastIndexOf(')')
            if (close <= 0) return -1
            val fields = stat.substring(close + 2).split(' ')
            // fields[0]=state, [11]=utime, [12]=stime (相对 proc 文档中的第 14/15 项)
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: return -1
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: return -1
            utime + stime
        } catch (e: Exception) {
            return -1
        }
        val now = SystemClock.elapsedRealtime()
        val prev: Long
        val prevAt: Long
        synchronized(cpuLock) {
            prev = lastCpuJiffies
            prevAt = lastCpuAt
            lastCpuJiffies = jiffies
            lastCpuAt = now
        }
        if (prev < 0L || prevAt == 0L) return -1
        val spentMs = (jiffies - prev) * 10L          // USER_HZ=100 → 1 jiffy = 10ms
        val elapsedMs = now - prevAt
        if (elapsedMs <= 0L) return -1
        return spentMs * 60_000L / elapsedMs
    }

    // ── 采集文件 ────────────────────────────────────────────────────────────
    private fun filesBlock(): JSONArray {
        val arr = JSONArray()
        val dir = File(context.filesDir, "diag")
        val now = System.currentTimeMillis()
        for (name in listOf("routing.jsonl", "weights.jsonl", "state.jsonl", "crashes.jsonl")) {
            val f = File(dir, name)
            val o = JSONObject()
            o.put("name", name)
            o.put("exists", f.isFile)
            if (f.isFile) {
                o.put("bytes", f.length())
                val ageS = (now - f.lastModified()) / 1000
                o.put("last_write_age_s", ageS)
                val limit = stuckAfterS(name)
                o.put("stuck", if (limit == null) JSONObject.NULL else ageS > limit)
            }
            arr.put(o)
        }
        return arr
    }

    private fun httpGetJson(url: String): JSONObject? {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.use { it.readBytes() }
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 年龄超过该秒数才标 stuck。null 表示这个文件的年龄不能代表采集卡住。
     * routing 标称 10 秒一条,1800 秒已经远超采样抖动。
     * weights 最快 5 分钟一条,但排名不变会整条跳过,1800 秒会把稳定期判成卡住;
     * 放到 6 小时,只抓长时间完全停更。
     * state 30 分钟一条且内容不变不写;crashes 是事件文件。二者都不标。
     */
    private fun stuckAfterS(name: String): Long? = when (name) {
        "routing.jsonl" -> 1800L
        "weights.jsonl" -> 6L * 3600L
        else -> null
    }

    private companion object {
        /** 去重时剥掉行首的时间戳,让"内容相同"能被识别出来。 */
        private val TS_PREFIX = Regex("""^\{"ts":"[^"]*",""")
        /** 与 RoutingSampler 共用同一个本地控制器地址。 */
        const val CONTROLLER = "http://127.0.0.1:9090"
        const val HTTP_TIMEOUT_MS = 1500

        private val cpuLock = Any()
        /** 进程级 CPU 基线。不能放在实例上,隧道重启会把实例丢掉。 */
        @Volatile private var lastCpuJiffies = -1L
        @Volatile private var lastCpuAt = 0L
        /** 本进程最近一次 /version 成功的时间;从未成功则为 null。 */
        @Volatile var processApiOkAt: String? = null

        private fun clockTicksPerSec(): Long = try {
            android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).coerceAtLeast(1L)
        } catch (e: Exception) {
            100L
        }

        private fun pageSizeBytes(): Long = try {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).coerceAtLeast(1L)
        } catch (e: Exception) {
            4096L
        }
    }
}
