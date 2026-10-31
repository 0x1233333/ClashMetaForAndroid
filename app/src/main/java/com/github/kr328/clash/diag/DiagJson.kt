package com.github.kr328.clash.diag

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.io.File
import java.util.TimeZone

/**
 * 连接表 / 权重 / 代理表的只读解析,以及一行 JSONL 的组装。
 * 目标身份只以 SHA-1 前 6 位十六进制离开这里,原文不进记录。
 */
internal object DiagJson {
    /** 只放在 routing.jsonl 第一行。数据行不要带这份说明。 */
    const val ROUTING_META =
        """{"_meta":"routing samples, one JSON per line","interval_s":10,"fields":{"ts":"ISO8601 local time","conn_id":"connection id","node":"sha1-hashed node name (node-<6 hex>)","chain":"策略组链，由内向外，不含节点自身；写入前在设备端用 nameToken 哈希，每个元素都是 node- 加 6 位十六进制，磁盘不留明文组名","target_class":"sha1 of destination host, never plaintext","down_bytes":"cumulative bytes down","up_bytes":"cumulative bytes up","down_rate":"recent down bytes/s","up_rate":"recent up bytes/s","age_s":"connection age seconds","idle_s":"seconds since last byte growth","idle_rounds":"consecutive sampling rounds without byte growth","grew":"bytes grew in this round","state":"active|stalled|dormant|nodata","stalled":"state == stalled（见过传输后连续无增长达到 3 轮，且未到 6 轮；满 6 轮即 60 秒记为 dormant，不再算卡顿）","net_type":"wifi|cellular","screen":"on|off","chosen_weight":"weight of the selected node","alts_top3":"top-3 candidate nodes by weight with their weight/last_delay_ms/alive"}}"""

    /** 只放在 weights.jsonl 第一行。快照间隔与 RoutingSampler 的 5 分钟落盘一致。 */
    const val WEIGHTS_META =
        """{"_meta":"node weight snapshots","interval_s":300,"fields":{"ts":"ISO8601","groups":"map: group name -> array of {node, weight, delay, alive}"}}"""

    /** 只放在 crashes.jsonl 第一行。勾选 sel_crashes(默认关)才打进诊断包。 */
    const val CRASHES_META =
        """{"_meta":"uncaught exceptions, one JSON per line. Written on the crash path, then the previous handler is called. Packed only when sel_crashes is checked (default off).","max_lines":200,"fields":{"ts":"ISO8601 local time","thread":"thread name","exception":"exception class name","message":"message with local paths, subscription URLs, UUIDs and private IPs removed","stack":"class.method(file:line) frames only","app_version":"app versionName","kernel_version":"core version if already known without a network call, else empty","screen":"on|off","last_screen":"simple name of the resumed activity, empty if none"}}"""

    data class Conn(
        val id: String,
        val start: String,
        val upload: Long,
        val download: Long,
        val maxUploadRate: Long,
        val maxDownloadRate: Long,
        val chains: List<String>,
        val host: String,
        val network: String,
        val smartTarget: String,
        val wildcardTarget: String,
        val remoteDestination: String,
        val destinationIPASN: String,
        val destinationGeoIP: String,
        val inboundName: String,
    )

    data class Ranked(
        val node: String,
        val weight: Double,
    )

    data class ProxyInfo(
        val type: String,
        val alive: Boolean?,
        val lastDelayMs: Long?,
        val members: List<String>,
    )

    fun parseConnections(body: String): List<Conn> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("connections") ?: return emptyList()
        val out = ArrayList<Conn>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val meta = item.optJSONObject("metadata")
            out.add(
                Conn(
                    id = item.optString("id", ""),
                    start = item.optString("start", ""),
                    upload = item.optLong("upload", 0L),
                    download = item.optLong("download", 0L),
                    maxUploadRate = item.optLong("maxUploadRate", 0L),
                    maxDownloadRate = item.optLong("maxDownloadRate", 0L),
                    chains = stringList(item.optJSONArray("chains")),
                    host = meta?.optString("host", "").orEmpty(),
                    network = meta?.optString("network", "").orEmpty(),
                    smartTarget = meta?.optString("smartTarget", "").orEmpty(),
                    wildcardTarget = meta?.optString("wildcardTarget", "").orEmpty(),
                    remoteDestination = meta?.optString("remoteDestination", "").orEmpty(),
                    destinationIPASN = meta?.optString("destinationIPASN", "").orEmpty(),
                    destinationGeoIP = geoCode(meta),
                    inboundName = meta?.optString("inboundName", "").orEmpty(),
                )
            )
        }
        return out
    }

    fun parseGroupWeights(body: String): List<Ranked> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("weights") ?: return emptyList()
        val out = ArrayList<Ranked>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = flexibleString(item, "name", "Name")
            val weight = flexibleDouble(item, "weight", "Weight") ?: continue
            if (name.isEmpty()) continue
            out.add(Ranked(name, weight))
        }
        return out
    }

    fun parseProxies(body: String): Map<String, ProxyInfo> {
        val root = JSONObject(body)
        val proxies = root.optJSONObject("proxies") ?: return emptyMap()
        val out = LinkedHashMap<String, ProxyInfo>(proxies.length())
        val keys = proxies.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val item = proxies.optJSONObject(name) ?: continue
            val history = item.optJSONArray("history")
            val lastDelay = if (history != null && history.length() > 0) {
                val last = history.optJSONObject(history.length() - 1)
                if (last != null && last.has("delay") && !last.isNull("delay")) last.optLong("delay") else null
            } else {
                null
            }
            val alive = if (item.has("alive") && !item.isNull("alive")) item.optBoolean("alive") else null
            out[name] = ProxyInfo(
                type = item.optString("type", ""),
                alive = alive,
                lastDelayMs = lastDelay,
                members = stringList(item.optJSONArray("all")),
            )
        }
        return out
    }

    /**
     * host 优先,再 smartTarget、wildcardTarget、remoteDestination、ASN、国家码。
     * 全空写 unknown,不哈希空串,不写原文。
     */
    fun targetClass(
        host: String,
        smartTarget: String,
        wildcardTarget: String,
        remoteDestination: String,
        destinationIPASN: String,
        destinationGeoIP: String,
    ): String {
        val raw = sequenceOf(
            host,
            smartTarget,
            wildcardTarget,
            remoteDestination,
            destinationIPASN,
            destinationGeoIP,
        ).firstOrNull { it.isNotBlank() } ?: return "unknown"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        val alphabet = "0123456789abcdef"
        val hex = CharArray(6)
        for (i in 0 until 3) {
            val b = digest[i].toInt() and 0xff
            hex[i * 2] = alphabet[b ushr 4]
            hex[i * 2 + 1] = alphabet[b and 0x0f]
        }
        return "sha1:" + String(hex)
    }

    /**
     * mihomo 先把真实出站追加进 chains,策略组在后面。
     * chains[0] 是节点名,最后一个才是最外层显示名。
     * 标签本身若是 `显示名[节点名]`(Chain.String 的形式),只留节点名。
     */
    fun nodeName(chains: List<String>): String {
        val head = chains.firstOrNull()?.trim().orEmpty()
        if (head.isEmpty()) return ""
        val open = head.lastIndexOf('[')
        if (open > 0 && head.endsWith("]") && open < head.length - 1) {
            return head.substring(open + 1, head.length - 1)
        }
        return head
    }

    /**
     * 连接经过的策略组链(按由内向外的顺序),不含节点自身。
     * 每个元素在这里就换成 [nameToken],满足 `node-` + 6 位十六进制。
     * 导出端的名字清单只认 `"node"` 字段和配置里的名字,组名常常不在其中,
     * 明文写进 jsonl 之后就脱敏不掉。
     */
    fun chainArray(chains: List<String>): JSONArray? {
        if (chains.size <= 1) return null
        val arr = JSONArray()
        for (i in 1 until chains.size) {
            val raw = chains[i].trim()
            if (raw.isEmpty()) continue
            arr.put(nameToken(raw))
        }
        return if (arr.length() > 0) arr else null
    }

    private val NAME_TOKEN = Regex("^node-[0-9a-f]{6}$")

    /** 已经是设备端哈希结果时导出端不得再哈希一次。 */
    fun isNameToken(name: String): Boolean = NAME_TOKEN.matches(name)

    /**
     * 名字 → `node-<6 hex>`(SHA-1 前 6 位)。组名/节点名统一走它,
     * 这样 state.jsonl 里可以直接写脱敏名,磁盘上从不出现明文。
     */
    fun nameToken(name: String): String {
        if (name.isEmpty()) return name
        val digest = MessageDigest.getInstance("SHA-1").digest(name.toByteArray(Charsets.UTF_8))
        val alphabet = "0123456789abcdef"
        val hex = CharArray(6)
        for (i in 0 until 3) {
            val b = digest[i].toInt() and 0xff
            hex[i * 2] = alphabet[b ushr 4]
            hex[i * 2 + 1] = alphabet[b and 0x0f]
        }
        return "node-" + String(hex)
    }

    /** 只放在 state.jsonl 第一行。 */
    const val STATE_META =
        """{"_meta":"per-round state snapshots, one JSON per line","interval_s":1800,"fields":{"ts":"ISO8601 local time","type":"state","trigger":"start|periodic|stop","app":"version/pid/通知开关/vpn 准备状态；uptime_s=本进程启动至今的秒数（含休眠），不是手机开机时长；version_code 读取失败时为 null","device":"机型/SDK/ABI/核数/内存/存储/进程 RSS（RSS 按本机页大小换算）","network":"当前网络类型/是否 VPN/是否计费/是否已验证","power":"亮屏/省电模式/电池优化白名单/电量/温度","core":"api_up=本轮 /version 是否成功；api_fail_streak=当前采样器连续失败次数，隧道重启归零；api_ok_at=本进程最近一次成功的时间，从未成功为 null；version；mode/tun_enabled/mixed_port 只在配置里真有该字段时出现；group_count/node_count/node_alive 缺省表示代理表未知，不要当成 0","groups":"null=控制器不可达，组列表未知，不是没有组；空数组=控制器可达但没有带成员的策略组。元素：脱敏组名/类型/当前选中（脱敏，没有选中则为 null）/成员数/存活数。没有成员的条目不列入","sampler":"当前隧道的采样轮次/失败轮次/掉轮/平均与最大单轮耗时/行数，隧道重启归零；cpu_ms_per_min=本进程 CPU 毫秒每分钟，进程内第一条为 -1（还没有基线），同一进程内隧道重启不重置基线；last_ok_age_s=-1 表示还没有成功轮","files":"采集文件大小与最后写入距今秒数。stuck：routing.jsonl 超过 1800 秒为 true（标称 10 秒一条；完全没有连接时文件不增长，可能误报）；weights.jsonl 超过 21600 秒才为 true（最快 5 分钟一条，排名不变会跳过，1800 秒会把稳定期当成卡住）；state.jsonl 与 crashes.jsonl 为 null（状态不变不落盘，或本来就不是周期文件，不能用年龄判断卡住）"}}"""

    /** 只放在 mem.jsonl 第一行。隧道运行期间每 60 秒一条。 */
    const val MEM_META =
        """{"_meta":"memory samples, one JSON per line, written only while the tunnel is up","interval_s":60,"fields":{"ts":"ISO8601 local time","self":"app|bg = 这一行是哪个进程写的。Android 11+ 禁跨进程读 /proc/<pid>/status，所以只有 self 那一侧的 rss/hwm/anon/swap 是真实值，另一侧恒为 null","app_rss_kb":"主进程(UI)常驻内存 KB = /proc/<pid>/status 的 VmRSS；null=进程不在或读不到","bg_rss_kb":"后台服务进程(:background, 跑内核与上传)的 VmRSS KB","app_hwm_kb":"主进程 VmHWM 历史峰值 KB","bg_hwm_kb":"后台进程 VmHWM 历史峰值 KB","app_anon_kb":"主进程 RssAnon(Java/native 匿名页)KB；不含文件映射","bg_anon_kb":"后台进程 RssAnon KB","app_swap_kb":"主进程 VmSwap KB","bg_swap_kb":"后台进程 VmSwap KB","sys_avail_mb":"系统可用内存 MB(ActivityManager.MemoryInfo.availMem)","screen_on":"true|false 亮屏","charging":"true|false 充电或已插电","vpn_up":"true|false 任一网络带 TRANSPORT_VPN","conns":"内核 /connections 的条目数；null=控制器超时(3s)或未就绪","core_alloc_mb":"内核 /memstats.alloc(Go 堆活对象，原始单位字节)换算成 MB；null=接口没起或 2s 超时","core_heap_inuse_mb":"内核 /memstats.heap_inuse 换算成 MB(想算没还给 OS 的部分用 heap_released)","core_sys_mb":"内核 /memstats.sys 换算成 MB(Go 向系统申请的内存量)","core_num_gc":"内核 /memstats.num_gc 累计 GC 次数","core_pause_ms":"内核 /memstats.pause_total_ns 换算成毫秒(累计 STW 暂停)","note":"自由备注，正常为空串；null 只在对应字段取不到时出现，不要当成 0"}}"""

    /** 只放在 mem_events.jsonl 第一行。打包/上传/重载等关键时刻各一条。 */
    const val MEM_EVENTS_META =
        """{"_meta":"memory events around packing and uploading, one JSON per line","fields":{"ts":"ISO8601 local time","evt":"pack_start|pack_end|upload_start|upload_end|reload|peak","rss_kb":"写这条事件的进程(:background, 即打包/上传所在进程)的 VmRSS KB；null=读不到","hwm_kb":"同进程 VmHWM KB","anon_kb":"同进程 RssAnon KB","delta_rss_kb":"相对本文件上一条事件的 RSS 差值 KB；上一条缺失为 null","extra":"自由文本：pack_end/upload_end 记包大小字节，upload_end 记成功与否；evt=peak 时为 role=app|bg、pid、rss_kb 再加该进程 /proc/<pid>/smaps_rollup 前 20 行（读不到写 smaps_rollup unreadable）"}}"""

    fun formatTs(epochMs: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val tz = TimeZone.getDefault()
        fmt.timeZone = tz
        val base = fmt.format(java.util.Date(epochMs))
        val offsetMin = tz.getOffset(epochMs) / 60_000
        val sign = if (offsetMin >= 0) '+' else '-'
        val abs = kotlin.math.abs(offsetMin)
        return String.format(Locale.US, "%s%c%02d:%02d", base, sign, abs / 60, abs % 60)
    }

    fun ageSeconds(start: String, nowMs: Long): Double? {
        val startMs = parseRfc3339(start) ?: return null
        return round1(((nowMs - startMs) / 1000.0).coerceAtLeast(0.0))
    }

    fun round1(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0

    fun record(
        ts: String,
        connId: String,
        node: String,
        targetClass: String,
        net: String,
        inbound: String,
        downBytes: Long,
        upBytes: Long,
        downRate: Long,
        upRate: Long,
        ageS: Double?,
        idleS: Double,
        idleRounds: Int,
        grew: Boolean,
        state: String,
        stalled: Boolean,
        netType: String,
        screen: String,
        chosenWeight: Double?,
        chosenDelayMs: Int?,
        altsTop3: JSONArray?,
        chain: JSONArray? = null,
    ): String {
        val obj = JSONObject()
        obj.put("ts", ts)
        obj.put("conn_id", connId)
        obj.put("node", node)
        if (chain != null) obj.put("chain", chain)
        obj.put("target_class", targetClass)
        obj.put("net", net)
        obj.put("inbound", inbound)
        obj.put("down_bytes", downBytes)
        obj.put("up_bytes", upBytes)
        obj.put("down_rate", downRate)
        obj.put("up_rate", upRate)
        if (ageS != null) obj.put("age_s", ageS)
        obj.put("idle_s", idleS)
        obj.put("idle_rounds", idleRounds)
        obj.put("grew", grew)
        obj.put("state", state)
        obj.put("stalled", stalled)
        obj.put("net_type", netType)
        obj.put("screen", screen)
        if (chosenWeight != null) obj.put("chosen_weight", chosenWeight)
        if (chosenDelayMs != null) obj.put("chosen_delay_ms", chosenDelayMs)
        if (altsTop3 != null) obj.put("alts_top3", altsTop3)
        return obj.toString()
    }

    /** 60 秒一次的全量权重。delay / alive 拿不到就省略。 */
    fun weightsRecord(
        ts: String,
        groups: Map<String, List<Ranked>>,
        proxies: Map<String, ProxyInfo>,
    ): String {
        val root = JSONObject()
        root.put("ts", ts)
        root.put("type", "weights")
        val groupsObj = JSONObject()
        for ((name, ranked) in groups) {
            val arr = JSONArray()
            for (item in ranked) {
                val proxy = proxies[item.node]
                val obj = JSONObject()
                obj.put("node", item.node)
                obj.put("weight", item.weight)
                if (proxy?.lastDelayMs != null) obj.put("delay", proxy.lastDelayMs)
                if (proxy?.alive != null) obj.put("alive", proxy.alive)
                arr.put(obj)
            }
            groupsObj.put(name, arr)
        }
        root.put("groups", groupsObj)
        return root.toString()
    }

    fun parseRfc3339(raw: String): Long? {
        val s = raw.trim()
        if (s.length < 19 || s[10] != 'T') return null
        return try {
            val year = s.substring(0, 4).toInt()
            val month = s.substring(5, 7).toInt()
            val day = s.substring(8, 10).toInt()
            val hour = s.substring(11, 13).toInt()
            val minute = s.substring(14, 16).toInt()
            val second = s.substring(17, 19).toInt()
            var idx = 19
            var millis = 0
            if (idx < s.length && s[idx] == '.') {
                idx++
                val fracStart = idx
                while (idx < s.length && s[idx].isDigit()) idx++
                val digits = s.substring(fracStart, idx)
                if (digits.isNotEmpty()) {
                    millis = digits.padEnd(3, '0').take(3).toInt()
                }
            }
            var offsetMs = 0
            if (idx < s.length && s[idx] != 'Z') {
                val sign = when (s[idx]) {
                    '+' -> 1
                    '-' -> -1
                    else -> return null
                }
                val rest = s.substring(idx + 1)
                val hours: Int
                val minutes: Int
                if (rest.length >= 5 && rest[2] == ':') {
                    hours = rest.substring(0, 2).toInt()
                    minutes = rest.substring(3, 5).toInt()
                } else if (rest.length >= 4) {
                    hours = rest.substring(0, 2).toInt()
                    minutes = rest.substring(2, 4).toInt()
                } else {
                    return null
                }
                offsetMs = sign * (hours * 60 + minutes) * 60_000
            }
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month - 1)
            cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, second)
            cal.set(Calendar.MILLISECOND, millis)
            cal.timeInMillis - offsetMs
        } catch (e: Exception) {
            null
        }
    }

    /** destinationGeoIP 是国家码数组;偶发字符串时直接收。空则返回空串。 */
    private fun geoCode(meta: JSONObject?): String {
        if (meta == null) return ""
        val arr = meta.optJSONArray("destinationGeoIP")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val value = arr.optString(i, "").trim()
                if (value.isNotEmpty()) return value
            }
            return ""
        }
        if (!meta.has("destinationGeoIP") || meta.isNull("destinationGeoIP")) return ""
        return meta.optString("destinationGeoIP", "").trim()
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val value = arr.optString(i, "")
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }

    private fun flexibleString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.optString(key, "")
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun flexibleDouble(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.optDouble(key, Double.NaN)
            if (!value.isNaN()) return value
        }
        return null
    }
}
