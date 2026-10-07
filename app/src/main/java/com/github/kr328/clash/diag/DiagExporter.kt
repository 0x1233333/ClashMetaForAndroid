package com.github.kr328.clash.diag

import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把 filesDir/diag 和最新一份内核日志打成 ZIP。
 * 文件必须落在 cacheDir/export/，FileProvider 才放行。
 * 分享复用 LogsActivity 现成逻辑；.zip 的 MIME 是 application/zip。
 */
object DiagExporter {
    private const val KERNEL_TAIL_BYTES = 2 * 1024 * 1024
    private const val CONTROLLER_VERSION = "http://127.0.0.1:9090/version"
    private const val HTTP_TIMEOUT_MS = 1500
    private val LOG_NAME = Regex("clash-(\\d+)\\.log")
    private val NODE_FIELD = Regex(""""node"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val JSON_STRING = Regex(""""((?:\\.|[^"\\])*)"""")
    private val SCHEMA_KEYS = setOf(
        "ts", "type", "groups", "node", "weight", "delay", "alive",
        "conn_id", "target_class", "net", "inbound",
        "down_bytes", "up_bytes", "down_rate", "up_rate",
        "age_s", "idle_s", "stalled", "net_type", "screen",
        "chosen_weight", "alts_top3", "last_delay_ms",
        "android_version", "sdk", "model", "abi", "version_name",
        "kernel_version", "root", "power_save",
    )

    private data class Part(val name: String, val bytes: ByteArray, val lines: Int)

    fun buildBundle(context: Context, redact: Boolean = true, includeKernel: Boolean = true): File {
        val app = context.applicationContext
        val dir = File(app.cacheDir, "export")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("mkdir ${dir.absolutePath} failed")
        }
        val parts = assemble(app, redact, includeKernel)
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

    /**
     * 只改 `"node"` 字符串：换成 `node-` + 该名字 SHA-1 的前 6 位十六进制。
     * alts_top3 / weights 里的 node 是同一个字段，一并处理。其余原样保留。
     */
    fun redact(jsonl: String): String {
        if (jsonl.isEmpty() || !jsonl.contains("\"node\"")) return jsonl
        return NODE_FIELD.replace(jsonl) { match ->
            val name = unescapeJson(match.groupValues[1])
            "\"node\":\"${nodeToken(name)}\""
        }
    }

    fun previewText(context: Context, redact: Boolean = true): String {
        val parts = assemble(context.applicationContext, redact, includeKernel = true)
        val sb = StringBuilder()
        for (part in parts) {
            sb.append(part.name)
            sb.append(" size=")
            sb.append(part.bytes.size)
            sb.append(" lines=")
            sb.append(part.lines)
            sb.append('\n')
        }
        sb.append("\nenv.json:\n")
        val env = parts.firstOrNull { it.name == "env.json" }
        if (env == null) {
            sb.append("(缺失)\n")
        } else {
            sb.append(String(env.bytes, Charsets.UTF_8))
            if (env.bytes.isEmpty() || env.bytes.last() != '\n'.code.toByte()) sb.append('\n')
        }
        sb.append("\nrouting (first 5):\n")
        val routing = parts.firstOrNull { it.name == "routing.jsonl" }
        if (routing == null) {
            sb.append("(缺失)\n")
        } else {
            var shown = 0
            for (line in String(routing.bytes, Charsets.UTF_8).lineSequence()) {
                if (line.isEmpty()) continue
                sb.append(line)
                sb.append('\n')
                shown++
                if (shown == 5) break
            }
            if (shown == 0) sb.append("(空)\n")
        }
        return sb.toString()
    }

    fun sourcePayloadBytes(context: Context): Long {
        val diagDir = File(context.applicationContext.filesDir, "diag")
        var total = 0L
        for (name in listOf("routing.jsonl", "weights.jsonl")) {
            val file = File(diagDir, name)
            if (file.isFile) total += file.length()
        }
        return total
    }

    private fun assemble(context: Context, redact: Boolean, includeKernel: Boolean): List<Part> {
        val diagDir = File(context.filesDir, "diag")
        val names = if (includeKernel) {
            listOf("routing.jsonl", "weights.jsonl", "crashes.jsonl", "events.jsonl")
        } else {
            listOf("routing.jsonl", "weights.jsonl")
        }
        val raws = LinkedHashMap<String, ByteArray>()
        val nodeNames = LinkedHashSet<String>()
        for (name in names) {
            val file = File(diagDir, name)
            if (!file.isFile || file.length() <= 0L) continue
            val raw = file.readBytes()
            if (raw.isEmpty()) continue
            raws[name] = raw
            if (redact) nodeNames.addAll(collectNodeNames(String(raw, Charsets.UTF_8)))
        }
        val parts = ArrayList<Part>()
        for ((name, raw) in raws) {
            val bytes = if (redact) {
                var text = redact(String(raw, Charsets.UTF_8))
                if (nodeNames.isNotEmpty()) text = scrubNames(text, nodeNames)
                text.toByteArray(Charsets.UTF_8)
            } else {
                raw
            }
            if (bytes.isEmpty()) continue
            parts.add(Part(name, bytes, countLines(bytes)))
        }
        if (includeKernel) {
            val kernel = readLatestKernel(context)
            if (kernel != null && kernel.isNotEmpty()) {
                val bytes = if (redact && nodeNames.isNotEmpty()) {
                    replaceNames(String(kernel, Charsets.UTF_8), nodeNames).toByteArray(Charsets.UTF_8)
                } else {
                    kernel
                }
                if (bytes.isNotEmpty()) parts.add(Part("kernel.log", bytes, countLines(bytes)))
            }
        }
        var envText = buildEnv(context)
        if (redact && nodeNames.isNotEmpty()) envText = scrubNames(envText, nodeNames)
        val env = envText.toByteArray(Charsets.UTF_8)
        parts.add(Part("env.json", env, countLines(env)))
        val routing = parts.firstOrNull { it.name == "routing.jsonl" }?.let {
            String(it.bytes, Charsets.UTF_8)
        }
        val summary = buildSummary(parts, routing, String(env, Charsets.UTF_8))
        parts.add(Part("summary.html", summary, countLines(summary)))
        return parts
    }

    private fun readLatestKernel(context: Context): ByteArray? {
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
        val chosen = best ?: fallback ?: return null
        return readTail(chosen, KERNEL_TAIL_BYTES)
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
            if (!obj.has("version") || obj.isNull("version")) null
            else obj.optString("version", "").ifEmpty { null }
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

    private fun buildSummary(parts: List<Part>, routing: String?, envJson: String): ByteArray {
        val stalled = countStalled(routing, 10)
        val dist = targetClassDist(routing, 5)
        val template = StringBuilder()
        template.append(
            """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <title>诊断包摘要</title>
            <style>
            body{font-family:sans-serif;margin:16px;color:#222;background:#fff}
            h1{font-size:20px}
            h2{font-size:16px;margin-top:20px}
            table{border-collapse:collapse;width:100%;max-width:720px}
            th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;vertical-align:top}
            th{background:#f4f4f4}
            code{font-family:monospace}
            </style>
            </head>
            <body>
            <h1>诊断包摘要</h1>
            <h2>文件清单</h2>
            <table>
            <thead><tr><th>文件</th><th>大小（字节）</th><th>行数</th></tr></thead>
            <tbody>
            """.trimIndent(),
        )
        template.append('\n')
        for (part in parts) {
            template.append("<tr><td><code>")
            template.append(esc(part.name))
            template.append("</code></td><td>")
            template.append(part.bytes.size)
            template.append("</td><td>")
            template.append(part.lines)
            template.append("</td></tr>\n")
        }
        template.append("<tr><td><code>summary.html</code></td><td>__SUMMARY_BYTES__</td><td>__SUMMARY_LINES__</td></tr>\n")
        template.append("</tbody></table>\n")
        template.append("<h2>连接</h2>\n<p>最近 10 条连接记录里 stalled=true 的条数：")
        template.append(stalled)
        template.append("</p>\n")
        template.append("<h2>target_class 分布（最近 5 条）</h2>\n")
        if (dist.isEmpty()) {
            template.append("<p>无连接记录</p>\n")
        } else {
            template.append("<table><thead><tr><th>target_class</th><th>条数</th></tr></thead><tbody>\n")
            for ((key, count) in dist) {
                template.append("<tr><td><code>")
                template.append(esc(key))
                template.append("</code></td><td>")
                template.append(count)
                template.append("</td></tr>\n")
            }
            template.append("</tbody></table>\n")
        }
        template.append("<h2>环境信息</h2>\n<table><tbody>\n")
        appendEnvRows(template, envJson)
        template.append("</tbody></table>\n</body>\n</html>\n")
        return renderSummary(template.toString())
    }

    private fun renderSummary(template: String): ByteArray {
        var size = 0
        var lines = countLines(template.toByteArray(Charsets.UTF_8))
        var rendered = ByteArray(0)
        repeat(8) {
            rendered = template
                .replace("__SUMMARY_BYTES__", size.toString())
                .replace("__SUMMARY_LINES__", lines.toString())
                .toByteArray(Charsets.UTF_8)
            val nextLines = countLines(rendered)
            if (rendered.size == size && nextLines == lines) return rendered
            size = rendered.size
            lines = nextLines
        }
        return rendered
    }

    private fun appendEnvRows(body: StringBuilder, envJson: String) {
        val obj = try {
            JSONObject(envJson)
        } catch (e: Exception) {
            body.append("<tr><td>env.json</td><td><pre>")
            body.append(esc(envJson))
            body.append("</pre></td></tr>\n")
            return
        }
        val labels = listOf(
            "android_version" to "Android 版本",
            "sdk" to "SDK",
            "model" to "机型",
            "abi" to "ABI",
            "version_name" to "应用版本",
            "kernel_version" to "内核版本",
            "root" to "Root",
            "power_save" to "省电模式",
        )
        for ((key, label) in labels) {
            body.append("<tr><td>")
            body.append(esc(label))
            body.append("</td><td>")
            body.append(esc(formatEnv(obj, key)))
            body.append("</td></tr>\n")
        }
    }

    private fun formatEnv(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return "null"
        return when (val value = obj.opt(key)) {
            is JSONArray -> {
                val items = ArrayList<String>(value.length())
                for (i in 0 until value.length()) items.add(value.optString(i))
                items.joinToString(", ")
            }
            is Boolean -> if (value) "是" else "否"
            else -> value.toString()
        }
    }

    private fun countStalled(routing: String?, n: Int): Int {
        var count = 0
        for (line in lastLines(routing, n)) {
            val obj = try {
                JSONObject(line)
            } catch (e: Exception) {
                continue
            }
            if (obj.optBoolean("stalled", false)) count++
        }
        return count
    }

    private fun targetClassDist(routing: String?, n: Int): List<Pair<String, Int>> {
        val map = LinkedHashMap<String, Int>()
        for (line in lastLines(routing, n)) {
            val obj = try {
                JSONObject(line)
            } catch (e: Exception) {
                continue
            }
            val key = if (!obj.has("target_class") || obj.isNull("target_class")) {
                "(缺失)"
            } else {
                obj.optString("target_class", "(缺失)").ifEmpty { "(缺失)" }
            }
            map[key] = (map[key] ?: 0) + 1
        }
        return map.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    private fun lastLines(text: String?, n: Int): List<String> {
        if (text.isNullOrEmpty() || n <= 0) return emptyList()
        val out = ArrayDeque<String>(n)
        var end = text.length
        if (text[end - 1] == '\n') end--
        while (end > 0 && out.size < n) {
            val breakAt = text.lastIndexOf('\n', end - 1)
            val start = if (breakAt < 0) 0 else breakAt + 1
            val line = text.substring(start, end)
            if (line.isNotEmpty()) out.addFirst(line)
            if (start == 0) break
            end = start - 1
        }
        return out
    }

    private fun collectNodeNames(jsonl: String): Set<String> {
        if (!jsonl.contains("\"node\"")) return emptySet()
        val names = LinkedHashSet<String>()
        for (match in NODE_FIELD.findAll(jsonl)) {
            val name = unescapeJson(match.groupValues[1])
            if (name.isNotEmpty()) names.add(name)
        }
        return names
    }

    /**
     * 节点名若还出现在其它 JSON 字符串里（例如权重分组名），也换成同一个 token。
     * 结构字段名（node/weight/...）本身不改。
     */
    private fun scrubNames(jsonl: String, names: Set<String>): String {
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

    private fun countLines(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var n = 0
        for (b in bytes) {
            if (b == '\n'.code.toByte()) n++
        }
        if (bytes.last() != '\n'.code.toByte()) n++
        return n
    }

    private fun esc(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
