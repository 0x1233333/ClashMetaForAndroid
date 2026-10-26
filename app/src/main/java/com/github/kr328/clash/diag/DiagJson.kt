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
        """{"_meta":"routing samples, one JSON per line","interval_s":10,"fields":{"ts":"ISO8601 local time","conn_id":"connection id","node":"sha1-hashed node name (node-<6 hex>)","chain":"sha1-hashed group chain of this connection (innermost first); 用来判定该流量是否落在 Smart 组内","target_class":"sha1 of destination host, never plaintext","down_bytes":"cumulative bytes down","up_bytes":"cumulative bytes up","down_rate":"recent down bytes/s","up_rate":"recent up bytes/s","age_s":"connection age seconds","idle_s":"seconds since last byte growth","idle_rounds":"consecutive sampling rounds without byte growth","grew":"bytes grew in this round","state":"active|stalled|dormant|nodata","stalled":"state == stalled (transfer observed, then 3+ rounds without growth, and not yet 18 rounds)","net_type":"wifi|cellular","screen":"on|off","chosen_weight":"weight of the selected node","alts_top3":"top-3 candidate nodes by weight with their weight/last_delay_ms/alive"}}"""

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
     * 目的:选路分析必须知道这条流量属于哪个组 —— 只记录 node 时无法判断它是否落在 Smart 组内,
     * 也解释不了"高流量节点一次都没出现在权重快照里"这类现象。
     */
    fun chainArray(chains: List<String>): JSONArray? {
        if (chains.size <= 1) return null
        val arr = JSONArray()
        for (i in 1 until chains.size) {
            val raw = chains[i].trim()
            if (raw.isEmpty()) continue
            arr.put(raw)
        }
        return if (arr.length() > 0) arr else null
    }

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
        """{"_meta":"per-round state snapshots, one JSON per line","interval_s":1800,"fields":{"ts":"ISO8601 local time","type":"state","trigger":"start|periodic|stop","app":"version/pid/uptime/notification/vpn 准备状态","device":"机型/SDK/ABI/核数/内存/存储/进程 RSS","network":"当前网络类型/是否 VPN/是否计费/是否已验证","power":"亮屏/省电模式/电池优化白名单/电量/温度","core":"内核 API 是否可达/连续失败次数(api_fail_streak)/最近一次可达时间(api_ok_at)/版本/配置摘要/组数/节点数/存活节点数","groups":"每个策略组:脱敏组名/类型/当前选中(脱敏)/成员数/存活数/是否为空","sampler":"采样轮次/失败轮次/掉轮/平均与最大单轮耗时/自进程 CPU(毫秒/分钟)/行数","files":"采集文件大小/最后写入距今秒数/是否卡住(>1800s 未写)"}}"""

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
