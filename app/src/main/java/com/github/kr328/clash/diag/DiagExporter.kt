package com.github.kr328.clash.diag

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.TelegramStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 按 TelegramStore 的勾选项把原始文件打成 ZIP。
 * 不写 HTML。文件落在 cacheDir/export/，FileProvider 才放行。
 * 压缩包超过 8 MB 时先截内核日志尾部，再丢额外文件，最后才截 routing/weights 的旧行。
 */
object DiagExporter {
    private const val MAX_BUNDLE_BYTES = 8 * 1024 * 1024
    private const val CONTROLLER_VERSION = "http://127.0.0.1:9090/version"
    private const val HTTP_TIMEOUT_MS = 1500
    private val LOG_NAME = Regex("clash-(\\d+)\\.log")
    private val NODE_FIELD = Regex(""""node"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val YAML_NAME = Regex("""(?m)^\s*-?\s*name\s*:\s*(.+?)\s*$""")
    private val HOSTLIKE =
        Regex("""\b[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+\b""")
    private val NAME_STOPLIST = setOf(
        "DIRECT", "REJECT", "PROXY", "GLOBAL", "FINAL", "PASS", "COMPATIBLE",
        "select", "url-test", "fallback", "load-balance", "relay", "direct",
    )
    private val JSON_STRING = Regex(""""((?:\\.|[^"\\])*)"""")
    private val SCHEMA_KEYS = setOf(
        "ts", "type", "groups", "node", "weight", "delay", "alive",
        "conn_id", "target_class", "net", "inbound",
        "down_bytes", "up_bytes", "down_rate", "up_rate",
        "age_s", "idle_s", "stalled", "net_type", "screen",
        "chosen_weight", "alts_top3", "last_delay_ms",
        "android_version", "sdk", "model", "abi", "version_name",
        "kernel_version", "root", "power_save", "schema",
        "_meta", "interval_s", "fields",
    )
    private val ITEM_ORDER = listOf("routing", "env", "kernel_log", "crashes", "extra")

    data class Packed(val file: File, val items: String)

    private data class Part(
        val name: String,
        val bytes: ByteArray,
        val item: String,
        val redacted: Boolean = true,
    )

    private data class ExtraFile(val name: String, val bytes: ByteArray, val text: String?)

    fun buildBundle(context: Context, redact: Boolean = true): File {
        return pack(context, redact).file
    }

    fun pack(context: Context, redact: Boolean = true): Packed {
        val app = context.applicationContext
        val fitted = fitToLimit(app, collect(app, redact))
        return Packed(fitted.second, itemCsv(fitted.first))
    }

    /** 纯文本：文件名、字节数、是否脱敏。不做 HTML。 */
    fun previewText(context: Context, redact: Boolean = true): String {
        val app = context.applicationContext
        val fitted = fitToLimit(app, collect(app, redact))
        fitted.second.delete()
        val parts = fitted.first
        if (parts.isEmpty()) return app.getString(R.string.preview_upload_empty) + "\n"
        val sb = StringBuilder()
        for (part in parts) {
            val state = if (part.redacted) {
                app.getString(R.string.redacted_yes)
            } else {
                app.getString(R.string.redacted_no)
            }
            sb.append(part.name)
            sb.append('\t')
            sb.append(part.bytes.size)
            sb.append(" B\t")
            sb.append(state)
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 只改 `"node"` 字符串：换成 `node-` + 该名字 SHA-1 的前 6 位十六进制。
     * 第一行 `_meta` 是字段说明，原样保留。
     */
    fun redact(jsonl: String): String {
        if (jsonl.isEmpty() || !jsonl.contains("\"node\"")) return jsonl
        return preservingMeta(jsonl) { body ->
            NODE_FIELD.replace(body) { match ->
                val name = unescapeJson(match.groupValues[1])
                "\"node\":\"${nodeToken(name)}\""
            }
        }
    }

    fun sourcePayloadBytes(context: Context): Long {
        val app = context.applicationContext
        val store = TelegramStore(app)
        val diagDir = File(app.filesDir, "diag")
        var total = 0L
        if (store.selRouting) {
            for (name in arrayOf("routing.jsonl", "weights.jsonl")) {
                val file = File(diagDir, name)
                if (file.isFile) total += file.length()
            }
        }
        if (store.selCrashes) {
            val file = File(diagDir, "crashes.jsonl")
            if (file.isFile) total += file.length()
        }
        if (store.selExtra) {
            val files = File(diagDir, "extra").listFiles() ?: emptyArray()
            for (file in files) {
                if (file.isFile) total += file.length()
            }
        }
        if (store.selKernelLog) {
            val kernel = findLatestKernel(app)
            if (kernel != null && kernel.isFile) total += kernel.length()
        }
        // env.json 打包时现生成，磁盘上没有对应文件。
        if (store.selEnv) total += 1L
        return total
    }

    private fun collect(context: Context, redact: Boolean): List<Part> {
        val store = TelegramStore(context)
        val diagDir = File(context.filesDir, "diag")
        val routingRaw = if (store.selRouting) readWhole(File(diagDir, "routing.jsonl")) else null
        val weightsRaw = if (store.selRouting) readWhole(File(diagDir, "weights.jsonl")) else null
        val crashesRaw = if (store.selCrashes) readFile(File(diagDir, "crashes.jsonl")) else null
        val extras = if (store.selExtra) readExtraDecoded(File(diagDir, "extra")) else emptyList()
        val kernelRaw = if (store.selKernelLog) readLatestKernel(context) else null

        val nodeNames = LinkedHashSet<String>()
        if (redact) {
            // 名字清单三源动态合并(代码里不写死任何具体名字):
            //   ① 配置里的 name: 字段(运行时读) ② 设备内持久化清单 ③ 数据文件里出现过的名字
            val persisted = persistedNames(diagDir)
            nodeNames.addAll(configNodeNames(context))
            nodeNames.addAll(persisted)
            for (raw in listOfNotNull(routingRaw, weightsRaw, crashesRaw)) {
                nodeNames.addAll(collectNodeNames(String(raw, Charsets.UTF_8)))
            }
            for (extra in extras) {
                val text = extra.text ?: continue
                nodeNames.addAll(collectNodeNames(text))
            }
            if (nodeNames.size > persisted.size) savePersistedNames(diagDir, nodeNames)
        }

        val parts = ArrayList<Part>()
        if (routingRaw != null) {
            parts.add(
                Part(
                    "routing.jsonl",
                    prepareJsonl(routingRaw, DiagJson.ROUTING_META, redact, nodeNames),
                    "routing",
                    redacted = redact,
                )
            )
        }
        if (weightsRaw != null) {
            parts.add(
                Part(
                    "weights.jsonl",
                    prepareJsonl(weightsRaw, DiagJson.WEIGHTS_META, redact, nodeNames),
                    "routing",
                    redacted = redact,
                )
            )
        }
        if (store.selEnv) {
            var env = buildEnv(context)
            if (redact && nodeNames.isNotEmpty()) env = scrubNames(env, nodeNames)
            val bytes = env.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty()) parts.add(Part("env.json", bytes, "env", redacted = redact))
        }
        if (crashesRaw != null) {
            val bytes = prepareCrashes(crashesRaw, redact, nodeNames)
            if (bytes.isNotEmpty()) parts.add(Part("crashes.jsonl", bytes, "crashes", redacted = redact))
        }
        val seen = HashSet<String>()
        for (extra in extras) {
            val entry = if (extra.text == null) rawEntryName(extra.name) else extra.name
            if (!seen.add(entry)) continue
            val bytes = if (extra.text == null || !redact) {
                extra.bytes
            } else {
                redactExtraText(extra.name, extra.text, nodeNames).toByteArray(Charsets.UTF_8)
            }
            if (bytes.isNotEmpty()) {
                parts.add(Part(entry, bytes, "extra", redacted = extra.text != null && redact))
            }
        }
        if (kernelRaw != null && kernelRaw.isNotEmpty()) {
            val bytes = if (redact && nodeNames.isNotEmpty()) {
                replaceNames(String(kernelRaw, Charsets.UTF_8), nodeNames).toByteArray(Charsets.UTF_8)
            } else {
                kernelRaw
            }
            if (bytes.isNotEmpty()) parts.add(Part("kernel.log", bytes, "kernel_log", redacted = redact))
        }
        return parts
    }

    /**
     * 反复打包试大小。超限时优先把 kernel.log 留尾部，routing/weights 最后才丢旧行。
     * 返回的文件已经落在 export/，调用方负责在不要它时删除。
     */
    private fun fitToLimit(context: Context, parts: List<Part>): Pair<List<Part>, File> {
        var current = parts.filter { it.bytes.isNotEmpty() }
        var file = writeZip(context, current)
        var guard = 0
        while (file.length() > MAX_BUNDLE_BYTES && current.isNotEmpty() && guard < 24) {
            file.delete()
            guard++
            val next = shrinkForCap(current)
            val nextBytes = next.sumOf { it.bytes.size }
            current = if (nextBytes >= current.sumOf { it.bytes.size }) {
                current.dropLast(1)
            } else {
                next
            }
            file = writeZip(context, current)
        }
        if (file.length() > MAX_BUNDLE_BYTES) {
            file.delete()
            current = current.mapNotNull { part ->
                when (part.item) {
                    "routing" -> {
                        val tailed = tailJsonl(part.bytes, 2 * 1024 * 1024)
                        if (tailed.isEmpty()) null else part.copy(bytes = tailed)
                    }
                    "env" -> part
                    else -> null
                }
            }
            file = writeZip(context, current)
        }
        return current to file
    }

    private fun shrinkForCap(parts: List<Part>): List<Part> {
        val kernelIdx = parts.indexOfLast { it.item == "kernel_log" }
        if (kernelIdx >= 0) {
            val part = parts[kernelIdx]
            val halved = tailBytes(part.bytes, part.bytes.size / 2)
            if (halved.isNotEmpty() && halved.size < part.bytes.size) {
                val copy = parts.toMutableList()
                copy[kernelIdx] = part.copy(bytes = halved)
                return copy
            }
            return parts.filter { it.item != "kernel_log" }
        }
        if (parts.any { it.item == "extra" }) return parts.filter { it.item != "extra" }
        if (parts.any { it.item == "crashes" }) return parts.filter { it.item != "crashes" }
        val big = parts.indices
            .filter { parts[it].name == "routing.jsonl" || parts[it].name == "weights.jsonl" }
            .maxByOrNull { parts[it].bytes.size }
        if (big != null && parts[big].bytes.size > 2048) {
            val part = parts[big]
            val tailed = tailJsonl(part.bytes, part.bytes.size / 2)
            if (tailed.isNotEmpty() && tailed.size < part.bytes.size) {
                val copy = parts.toMutableList()
                copy[big] = part.copy(bytes = tailed)
                return copy
            }
        }
        val envIdx = parts.indexOfFirst { it.item == "env" }
        if (envIdx >= 0 && parts.size > 1) {
            return parts.filterIndexed { index, _ -> index != envIdx }
        }
        return if (parts.size <= 1) emptyList() else parts.dropLast(1)
    }

    private fun itemCsv(parts: List<Part>): String {
        val present = parts.map { it.item }.toSet()
        return ITEM_ORDER.filter { it in present }.joinToString(",")
    }

    private fun readWhole(file: File): ByteArray? {
        if (!file.isFile || file.length() <= 0L) return null
        val raw = file.readBytes()
        return if (raw.isEmpty()) null else raw
    }

    private fun readFile(file: File): ByteArray? {
        if (!file.isFile || file.length() <= 0L) return null
        val raw = if (file.length() <= MAX_BUNDLE_BYTES) file.readBytes() else readTail(file, MAX_BUNDLE_BYTES)
        return if (raw.isEmpty()) null else raw
    }

    private fun readExtraDecoded(dir: File): List<ExtraFile> {
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<ExtraFile>()
        for (file in files.sortedBy { it.name }) {
            val entry = extraEntryName(file) ?: continue
            val raw = readFile(file) ?: continue
            out.add(ExtraFile(entry, raw, textOrNull(raw)))
        }
        return out
    }

    private fun extraEntryName(file: File): String? {
        if (!file.isFile) return null
        val name = file.name
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return null
        return "extra/$name"
    }

    /** 二进制原样进包,文件名后加 .RAW,界面用这个后缀提示未脱敏。 */
    private fun rawEntryName(entry: String): String {
        if (entry.endsWith(".RAW")) return entry
        return "$entry.RAW"
    }

    private fun prepareCrashes(raw: ByteArray, redact: Boolean, nodeNames: Set<String>): ByteArray {
        var text = String(raw, Charsets.UTF_8)
        if (redact) {
            text = preservingMeta(text) { body ->
                var out = DiagExporter.redact(body)
                if (nodeNames.isNotEmpty()) out = scrubNames(out, nodeNames)
                DiagRedact.secrets(out)
            }
        }
        text = capCrashRecords(text)
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun capCrashRecords(text: String): String {
        val data = ArrayList<String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            if (line.trimStart().startsWith("{\"_meta\"")) continue
            if (!line.startsWith("{")) continue
            data.add(line)
        }
        val kept = if (data.size > CrashCapture.MAX_RECORDS) {
            data.takeLast(CrashCapture.MAX_RECORDS)
        } else {
            data
        }
        return buildString {
            append(DiagJson.CRASHES_META)
            append('\n')
            for (line in kept) {
                append(line)
                append('\n')
            }
        }
    }

    private fun redactExtraText(name: String, text: String, nodeNames: Set<String>): String {
        val fileName = name.substringAfterLast('/')
        val trimmed = text.trimStart()
        val jsonish = fileName.endsWith(".json") || fileName.endsWith(".jsonl") ||
            trimmed.startsWith("{") || trimmed.startsWith("[")
        var out = if (jsonish) {
            // 先抹域名形状,再按名字清单替换(两步都做,域名不因清单非空而漏网)
            val redacted = redact(HOSTLIKE.replace(text, "<host>"))
            if (nodeNames.isEmpty()) redacted else scrubNames(redacted, nodeNames)
        } else {
            replaceNames(HOSTLIKE.replace(text, "<host>"), nodeNames)
        }
        if (!jsonish && out.contains("\"node\"")) out = redact(out)
        return DiagRedact.secrets(out)
    }

    private fun prepareJsonl(
        raw: ByteArray,
        meta: String,
        redact: Boolean,
        nodeNames: Set<String>,
    ): ByteArray {
        var text = String(raw, Charsets.UTF_8)
        if (redact) {
            text = redact(text)
            if (nodeNames.isNotEmpty()) text = scrubNames(text, nodeNames)
        }
        text = ensureMeta(text, meta)
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun ensureMeta(text: String, metaLine: String): String {
        val line = if (metaLine.endsWith("\n")) metaLine else metaLine + "\n"
        if (text.isEmpty()) return line
        val nl = text.indexOf('\n')
        val first = if (nl < 0) text else text.substring(0, nl)
        if (first.contains("\"_meta\"")) {
            return if (text.endsWith("\n")) text else text + "\n"
        }
        return line + text
    }

    /** 第一行是 `_meta` 时不改它，只处理后面的数据。 */
    private fun preservingMeta(text: String, transform: (String) -> String): String {
        val nl = text.indexOf('\n')
        if (nl <= 0) {
            return if (text.contains("\"_meta\"")) text else transform(text)
        }
        val first = text.substring(0, nl)
        if (!first.contains("\"_meta\"")) return transform(text)
        return first + "\n" + transform(text.substring(nl + 1))
    }

    /**
     * extra 里文本和二进制的分界。
     * 整段必须能按 UTF-8 严格解码,截断的多字节或图片魔数都算二进制。
     * 再看解码后前 8192 个字符:不可打印字符占比超过 10% 也算二进制。
     * 不可打印 = U+0000–U+0008、U+000B、U+000C、U+000E–U+001F、U+007F。
     * \t \n \r 不算。10% 是为了放过带少量控制符的日志,
     * 同时拦住 SQLite、压缩包、图片这类开头就有大量 NUL 或控制字节的文件。
     * 判成二进制的不改字节,压缩包内文件名加 .RAW。
     */
    private fun textOrNull(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        val text = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        val n = minOf(text.length, 8192)
        if (n == 0) return text
        var bad = 0
        for (i in 0 until n) {
            if (isNonPrintable(text[i])) bad++
        }
        if (bad.toDouble() / n.toDouble() > 0.10) return null
        return text
    }

    private fun isNonPrintable(c: Char): Boolean {
        val code = c.code
        if (code == 0x7F) return true
        if (code >= 0x20) return false
        return code != '\t'.code && code != '\n'.code && code != '\r'.code
    }

    private fun writeZip(context: Context, parts: List<Part>): File {
        val dir = File(context.cacheDir, "export")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("mkdir ${dir.absolutePath} failed")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var out = File(dir, "clash-smart-diag-$stamp.zip")
        if (out.exists()) {
            out = File(dir, "clash-smart-diag-$stamp-${System.currentTimeMillis()}.zip")
        }
        val tmp = File(dir, out.name + ".tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
                for (part in parts) {
                    zip.putNextEntry(ZipEntry(part.name))
                    zip.write(part.bytes)
                    zip.closeEntry()
                }
            }
            if (out.exists() && !out.delete()) {
                throw IOException("replace ${out.name} failed")
            }
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            return out
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun readLatestKernel(context: Context): ByteArray? {
        val chosen = findLatestKernel(context) ?: return null
        val bytes = readTail(chosen, MAX_BUNDLE_BYTES)
        return if (bytes.isEmpty()) null else bytes
    }

    private fun findLatestKernel(context: Context): File? {
        val dir = File(context.cacheDir, "logs")
        val files = dir.listFiles() ?: return null
        var best: File? = null
        var bestTime = Long.MIN_VALUE
        var fallback: File? = null
        var fallbackTime = Long.MIN_VALUE
        for (file in files) {
            if (!file.isFile) continue
            val modified = file.lastModified()
            if (modified >= fallbackTime) {
                fallbackTime = modified
                fallback = file
            }
            val match = LOG_NAME.matchEntire(file.name) ?: continue
            val time = match.groupValues[1].toLongOrNull() ?: continue
            if (time >= bestTime) {
                bestTime = time
                best = file
            }
        }
        return best ?: fallback
    }

    private fun readTail(file: File, maxBytes: Int): ByteArray {
        val len = file.length()
        if (len <= 0L) return ByteArray(0)
        if (len <= maxBytes) return file.readBytes()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - maxBytes)
            val buf = ByteArray(maxBytes)
            var off = 0
            while (off < maxBytes) {
                val n = raf.read(buf, off, maxBytes - off)
                if (n < 0) break
                off += n
            }
            return if (off == maxBytes) buf else buf.copyOf(off)
        }
    }

    /** 保留第一行 `_meta`，其余只留尾部的完整行。 */
    private fun tailJsonl(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (maxBytes <= 0 || bytes.isEmpty()) return ByteArray(0)
        if (bytes.size <= maxBytes) return bytes
        val nl = indexOfByte(bytes, '\n'.code.toByte(), 0)
        val metaEnd = if (nl >= 0 && lineHasMeta(bytes, 0, nl) && nl + 1 <= maxBytes) nl + 1 else 0
        val room = maxBytes - metaEnd
        if (room <= 0) return if (metaEnd > 0) bytes.copyOfRange(0, metaEnd) else ByteArray(0)
        val body = if (metaEnd >= bytes.size) {
            ByteArray(0)
        } else {
            tailBytes(bytes.copyOfRange(metaEnd, bytes.size), room)
        }
        if (metaEnd == 0) return body
        if (body.isEmpty()) return bytes.copyOfRange(0, metaEnd)
        val out = ByteArray(metaEnd + body.size)
        System.arraycopy(bytes, 0, out, 0, metaEnd)
        System.arraycopy(body, 0, out, metaEnd, body.size)
        return out
    }

    private fun tailBytes(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (maxBytes <= 0 || bytes.isEmpty()) return ByteArray(0)
        if (bytes.size <= maxBytes) return bytes
        var start = bytes.size - maxBytes
        val limit = minOf(bytes.size - 1, start + 8192)
        var i = start
        while (i < limit && bytes[i] != '\n'.code.toByte()) i++
        if (i < bytes.size && bytes[i] == '\n'.code.toByte() && i + 1 < bytes.size) {
            start = i + 1
        }
        if (start >= bytes.size) return ByteArray(0)
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun lineHasMeta(bytes: ByteArray, start: Int, end: Int): Boolean {
        val token = "\"_meta\"".toByteArray(Charsets.UTF_8)
        val last = end - token.size
        var i = start
        while (i <= last) {
            var ok = true
            for (j in token.indices) {
                if (bytes[i + j] != token[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return true
            i++
        }
        return false
    }

    private fun indexOfByte(bytes: ByteArray, target: Byte, from: Int): Int {
        for (i in from until bytes.size) {
            if (bytes[i] == target) return i
        }
        return -1
    }

    private fun buildEnv(context: Context): String {
        val obj = JSONObject()
        obj.put("android_version", Build.VERSION.RELEASE)
        obj.put("sdk", Build.VERSION.SDK_INT)
        obj.put("model", Build.MODEL)
        val abis = JSONArray()
        for (abi in Build.SUPPORTED_ABIS) abis.put(abi)
        obj.put("abi", abis)
        obj.put("version_name", versionName(context) ?: JSONObject.NULL)
        obj.put("kernel_version", kernelVersion() ?: JSONObject.NULL)
        obj.put("root", suExists())
        obj.put("power_save", powerSave(context))
        val schema = JSONObject()
        schema.put("android_version", "Android release name")
        schema.put("sdk", "Android SDK int")
        schema.put("model", "device model")
        schema.put("abi", "supported CPU ABIs")
        schema.put("version_name", "this app versionName, or null")
        schema.put("kernel_version", "core version string, or null when the local controller is down")
        schema.put("root", "true when an su binary is visible")
        schema.put("power_save", "true when system power-save mode is on")
        schema.put("schema", "this object: one line of meaning for each key")
        obj.put("schema", schema)
        return obj.toString(2) + "\n"
    }

    private fun versionName(context: Context): String? {
        return try {
            val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            if (name.isNullOrEmpty()) null else name
        } catch (e: Exception) {
            null
        }
    }

    private fun kernelVersion(): String? {
        val conn = try {
            URL(CONTROLLER_VERSION).openConnection() as HttpURLConnection
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
            val body = conn.inputStream.use { input ->
                val buf = ByteArray(65536)
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
            val obj = JSONObject(body)
            val version = if (!obj.has("version") || obj.isNull("version")) {
                null
            } else {
                obj.optString("version", "").ifEmpty { null }
            }
            if (!version.isNullOrEmpty()) CrashCapture.KernelVersionCache.value = version
            version
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun suExists(): Boolean {
        return try {
            val fixed = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/vendor/bin/su",
                "/su/bin/su",
                "/magisk/bin/su",
            )
            if (fixed.any { File(it).exists() }) return true
            val path = System.getenv("PATH") ?: return false
            path.split(File.pathSeparator).any { dir ->
                dir.isNotEmpty() && File(dir, "su").exists()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun powerSave(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 配置里出现过的节点/分组名 —— **运行时读取**,代码里不写死任何具体名字。
     * 来源:processing/imported/pending 下的 config.yaml 的 `name:` 字段。
     */
    private fun configNodeNames(context: Context): Set<String> {
        val out = LinkedHashSet<String>()
        val roots = listOf("processing", "imported", "pending")
        for (r in roots) {
            val root = File(context.filesDir, r)
            val dirs = if (r == "pending") root.listFiles()?.toList().orEmpty() else listOf(root)
            for (d in dirs) {
                val f = File(d, "config.yaml")
                if (!f.isFile || f.length() > 32L * 1024 * 1024) continue
                val text = try {
                    f.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    continue
                }
                for (m in YAML_NAME.findAll(text)) {
                    val n = m.groupValues[1].trim().trim('"').trim('\'')
                    if (n.length in 3..64 && n !in NAME_STOPLIST) out.add(n)
                }
            }
        }
        return out
    }

    /** 设备内持久化的历史名字(只在手机里,不进仓库、不联网)。 */
    private fun persistedNames(diagDir: File): MutableSet<String> {
        val out = LinkedHashSet<String>()
        val f = File(diagDir, "known_names.txt")
        try {
            if (f.isFile) {
                for (line in f.readLines(Charsets.UTF_8)) {
                    val n = line.trim()
                    if (n.isNotEmpty() && n.length <= 64) out.add(n)
                }
            }
        } catch (e: Exception) {
            // 读不到就当空(不影响脱敏主流程)
        }
        return out
    }

    private fun savePersistedNames(diagDir: File, all: Collection<String>) {
        try {
            diagDir.mkdirs()
            File(diagDir, "known_names.txt").writeText(all.take(4000).joinToString("\n"), Charsets.UTF_8)
        } catch (e: Exception) {
            // 写不进去也无妨
        }
    }

    private fun collectNodeNames(jsonl: String): Set<String> {
        if (!jsonl.contains("\"node\"")) return emptySet()
        val body = dropMetaLine(jsonl)
        if (!body.contains("\"node\"")) return emptySet()
        val names = LinkedHashSet<String>()
        for (match in NODE_FIELD.findAll(body)) {
            val name = unescapeJson(match.groupValues[1])
            if (name.isNotEmpty()) names.add(name)
        }
        return names
    }

    private fun dropMetaLine(text: String): String {
        val nl = text.indexOf('\n')
        if (nl <= 0) return if (text.contains("\"_meta\"")) "" else text
        val first = text.substring(0, nl)
        return if (first.contains("\"_meta\"")) text.substring(nl + 1) else text
    }

    /**
     * 节点名若还出现在其它 JSON 字符串里（例如权重分组名），也换成同一个 token。
     * 结构字段名（node/weight/...）本身不改。`_meta` 行不改。
     */
    private fun scrubNames(jsonl: String, names: Set<String>): String {
        if (jsonl.isEmpty() || names.isEmpty()) return jsonl
        return preservingMeta(jsonl) { body -> scrubBody(body, names) }
    }

    private fun scrubBody(jsonl: String, names: Set<String>): String {
        if (jsonl.isEmpty() || names.isEmpty()) return jsonl
        return JSON_STRING.replace(jsonl) { match ->
            val decoded = unescapeJson(match.groupValues[1])
            if (decoded.isEmpty() || decoded !in names) return@replace match.value
            if (decoded in SCHEMA_KEYS && isJsonKey(jsonl, match.range.last)) return@replace match.value
            "\"${nodeToken(decoded)}\""
        }
    }

    private fun isJsonKey(text: String, endInclusive: Int): Boolean {
        var i = endInclusive + 1
        while (i < text.length && text[i].isWhitespace()) i++
        return i < text.length && text[i] == ':'
    }

    private fun replaceNames(text: String, names: Set<String>): String {
        val sorted = names.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        if (sorted.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched: String? = null
            for (name in sorted) {
                if (i + name.length <= text.length && text.startsWith(name, i)) {
                    matched = name
                    break
                }
            }
            if (matched != null) {
                out.append(nodeToken(matched))
                i += matched.length
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun nodeToken(name: String): String {
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

    private fun unescapeJson(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                out.append(c)
                i++
                continue
            }
            when (val n = raw[i + 1]) {
                '"', '\\', '/' -> {
                    out.append(n)
                    i += 2
                }
                'b' -> {
                    out.append('\b')
                    i += 2
                }
                'f' -> {
                    out.append('\u000C')
                    i += 2
                }
                'n' -> {
                    out.append('\n')
                    i += 2
                }
                'r' -> {
                    out.append('\r')
                    i += 2
                }
                't' -> {
                    out.append('\t')
                    i += 2
                }
                'u' -> {
                    if (i + 6 <= raw.length) {
                        val cp = raw.substring(i + 2, i + 6).toIntOrNull(16)
                        if (cp != null) {
                            out.append(cp.toChar())
                            i += 6
                            continue
                        }
                    }
                    out.append(c)
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
