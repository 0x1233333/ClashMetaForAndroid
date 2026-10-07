package com.github.kr328.clash.diag

import android.content.Context
import com.github.kr328.clash.common.log.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.UUID

/**
 * 阻塞把诊断包 POST 到 Bot API sendDocument。调用方放到后台线程。
 * 走本机 HTTP 代理 127.0.0.1:7892；端口不通则改直连，连接 15s、读取 60s，不会一直卡住。
 * 日志里的 token 只写成 bot<REDACTED>，异常原因也不附带原始 throwable。
 */
object TelegramUploader {
    private const val PROXY_HOST = "127.0.0.1"
    private const val PROXY_PORT = 7892
    private const val PROXY_PROBE_MS = 2_000
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BODY_LIMIT = 65_536

    fun upload(context: Context, zip: File, token: String, chatId: String): Result<Unit> {
        val cleanToken = token.trim()
        val cleanChat = chatId.trim()
        val rejected = reject(context, zip, cleanToken, cleanChat)
        if (rejected != null) {
            Log.w("sendDocument rejected bot<REDACTED> $rejected")
            return Result.failure(IllegalArgumentException(rejected))
        }
        val viaProxy = proxyReachable()
        if (viaProxy) {
            Log.i("sendDocument via Proxy $PROXY_HOST:$PROXY_PORT bot<REDACTED>")
        } else {
            Log.i("$PROXY_HOST:$PROXY_PORT unreachable, direct sendDocument bot<REDACTED>")
        }
        val first = send(zip, cleanToken, cleanChat, viaProxy)
        if (viaProxy && first.isFailure && proxyDown(first.exceptionOrNull())) {
            Log.i("$PROXY_HOST:$PROXY_PORT unreachable, direct sendDocument bot<REDACTED>")
            return send(zip, cleanToken, cleanChat, false)
        }
        return first
    }

    /**
     * 连接自测:先 getMe 验身份,再 sendMessage 验会话。返回 bot 用户名;
     * 失败时异常消息与上传一致(http=… / 异常名: 详情),交给界面翻译成人话。
     */
    fun test(context: Context, token: String, chatId: String): Result<String> {
        val cleanToken = token.trim()
        val cleanChat = chatId.trim()
        if (cleanToken.isEmpty() || cleanChat.isEmpty()) {
            return Result.failure(IllegalArgumentException("not configured"))
        }
        if (!tokenOk(cleanToken)) return Result.failure(IllegalArgumentException("bad token"))
        if (!chatOk(cleanChat)) return Result.failure(IllegalArgumentException("bad chat id"))
        val viaProxy = proxyReachable()
        if (viaProxy) {
            Log.i("test via Proxy $PROXY_HOST:$PROXY_PORT bot<REDACTED>")
        } else {
            Log.i("$PROXY_HOST:$PROXY_PORT unreachable, direct test bot<REDACTED>")
        }
        val me = postForm(cleanToken, viaProxy, "getMe", null)
        if (me.isFailure) return Result.failure<String>(me.exceptionOrNull() ?: IOException("getMe"))
        val username = try {
            JSONObject(me.getOrThrow()).optJSONObject("result")?.optString("username").orEmpty()
        } catch (e: Exception) {
            ""
        }
        val text = "Clash Smart 连接测试 / connection test"
        val sent = postForm(
            cleanToken,
            viaProxy,
            "sendMessage",
            "chat_id=" + java.net.URLEncoder.encode(cleanChat, "UTF-8") +
                "&text=" + java.net.URLEncoder.encode(text, "UTF-8"),
        )
        if (sent.isFailure) return Result.failure<String>(sent.exceptionOrNull() ?: IOException("sendMessage"))
        Log.i("test ok bot<REDACTED>")
        return Result.success(username)
    }

    private fun postForm(token: String, viaProxy: Boolean, method: String, form: String?): Result<String> {
        return try {
            val url = URL("https://api.telegram.org/bot$token/$method")
            val conn = if (viaProxy) {
                url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress(PROXY_HOST, PROXY_PORT)))
            } else {
                url.openConnection()
            } as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                if (form == null) {
                    conn.outputStream.use { it.write(ByteArray(0)) }
                } else {
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val body = readLimited(if (code in 200..299) conn.inputStream else conn.errorStream)
                if (code in 200..299 && jsonOk(body)) {
                    Result.success(body)
                } else {
                    val message = httpFailure(code, body, token)
                    Log.w("$method failed bot<REDACTED> $message")
                    Result.failure(IOException(message))
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            failureOf(e, token)
        }
    }

    private fun reject(context: Context, zip: File, token: String, chatId: String): String? {
        if (token.isEmpty() || chatId.isEmpty()) return "not configured"
        if (!tokenOk(token)) return "bad token"
        if (!chatOk(chatId)) return "bad chat id"
        val inside = try {
            insideExport(context, zip)
        } catch (e: IOException) {
            return "zip unreadable"
        }
        if (!inside) return "zip outside export"
        return null
    }

    private fun insideExport(context: Context, zip: File): Boolean {
        val root = File(context.applicationContext.cacheDir, "export").canonicalFile
        val file = zip.canonicalFile
        if (!file.isFile || file.length() <= 0L) return false
        return file.path.startsWith(root.path + File.separator)
    }

    private fun tokenOk(token: String): Boolean {
        if (token.length !in 20..128 || ':' !in token) return false
        return token.all { it.isLetterOrDigit() || it == ':' || it == '_' || it == '-' }
    }

    private fun chatOk(chatId: String): Boolean {
        if (chatId.length !in 1..64) return false
        return chatId.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '@' }
    }

    private fun proxyReachable(): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(PROXY_HOST, PROXY_PORT), PROXY_PROBE_MS)
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // probe socket
            }
        }
    }

    private fun proxyDown(error: Throwable?): Boolean {
        val msg = error?.message ?: return false
        return msg.contains(PROXY_HOST) || msg.contains(":$PROXY_PORT")
    }

    private fun send(zip: File, token: String, chatId: String, viaProxy: Boolean): Result<Unit> {
        val conn = try {
            open(token, viaProxy)
        } catch (e: Exception) {
            return failureOf(e, token)
        }
        try {
            val boundary = "ClashDiag" + UUID.randomUUID().toString().replace("-", "")
            val filename = safeFilename(zip.name)
            val chatPart = textPart(boundary, "chat_id", chatId)
            val head = fileHead(boundary, filename)
            val ending = ("--$boundary--\r\n").toByteArray(Charsets.UTF_8)
            val total = chatPart.size.toLong() + head.size + zip.length() + 2L + ending.size
            conn.setFixedLengthStreamingMode(total)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                out.write(chatPart)
                out.write(head)
                zip.inputStream().use { input -> input.copyTo(out) }
                out.write(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
                out.write(ending)
            }
            val code = conn.responseCode
            val body = readLimited(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code == 200 && jsonOk(body)) {
                Log.i("sendDocument ok bot<REDACTED>")
                return Result.success(Unit)
            }
            val message = httpFailure(code, body, token)
            Log.w("sendDocument failed bot<REDACTED> $message")
            return Result.failure(IOException(message))
        } catch (e: Exception) {
            return failureOf(e, token)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(token: String, viaProxy: Boolean): HttpURLConnection {
        val url = URL("https://api.telegram.org/bot$token/sendDocument")
        val raw = if (viaProxy) {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(PROXY_HOST, PROXY_PORT))
            url.openConnection(proxy)
        } else {
            url.openConnection()
        }
        val conn = raw as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        return conn
    }

    private fun textPart(boundary: String, name: String, value: String): ByteArray {
        return (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"$name\"\r\n" +
                "\r\n" +
                value +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun fileHead(boundary: String, filename: String): ByteArray {
        return (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"document\"; filename=\"$filename\"\r\n" +
                "Content-Type: application/zip\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun safeFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString(base.length) {
            for (ch in base) {
                append(if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_')
            }
        }.trim(' ', '.', '_')
        return if (cleaned.isEmpty()) "diag.zip" else cleaned.take(120)
    }

    private fun jsonOk(body: String): Boolean {
        return try {
            JSONObject(body).optBoolean("ok", false)
        } catch (e: Exception) {
            false
        }
    }

    private fun httpFailure(code: Int, body: String, token: String): String {
        val desc = try {
            JSONObject(body).optString("description")
        } catch (e: Exception) {
            ""
        }
        val detail = redact(desc.ifBlank { body }, token).take(180)
        return if (detail.isBlank()) "http=$code" else "http=$code $detail"
    }

    private fun <T> failureOf(error: Exception, token: String): Result<T> {
        val detail = redact(error.message, token).take(180)
        val message = if (detail.isBlank()) {
            error.javaClass.simpleName
        } else {
            "${error.javaClass.simpleName}: $detail"
        }
        Log.w("sendDocument failed bot<REDACTED> $message")
        return Result.failure(IOException(message))
    }

    private fun readLimited(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            val buf = ByteArray(4096)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < BODY_LIMIT) {
                val n = input.read(buf, 0, minOf(buf.size, BODY_LIMIT - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    private fun redact(raw: String?, token: String): String {
        if (raw.isNullOrEmpty()) return ""
        var text = raw
        if (token.isNotEmpty()) text = text.replace(token, "<REDACTED>")
        text = text.replace(Regex("bot[0-9]+:[A-Za-z0-9_-]+"), "bot<REDACTED>")
        return text
    }
}
