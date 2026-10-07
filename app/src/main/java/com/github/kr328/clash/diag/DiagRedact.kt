package com.github.kr328.clash.diag

import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃堆栈和 extra 文本共用的脱敏。
 * 只抹本地路径(含用户名段)、订阅/网址、UUID、内网 IP。类名和方法名留着。
 */
internal object DiagRedact {
    private val URL = Regex(
        """(?i)\b(?:https?|hysteria2|hysteria|vmess|vless|trojan|socks5|socks|ssr|ss|tuic|wireguard|snell|anytls|mieru|juicity|hy2|clashmeta|clash|file|content)://[^\s"'<>]+""",
    )
    private val URL_TRAIL = charArrayOf('.', ',', ';', ':', ')', ']', '}', '>')
    private val UUID = Regex(
        """(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""",
    )
    private val IPV4 = Regex(
        """\b(?:(?:10|127)(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|169\.254(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})(?::\d{1,5})?\b""",
    )
    private val IPV6 = Regex(
        """(?i)(?<![0-9A-Fa-f:])(?:(?:fe[89ab][0-9a-f]|fc[0-9a-f]{2}|fd[0-9a-f]{2}):[0-9a-f:.]*|::1)(?![0-9A-Fa-f:])""",
    )
    private val UNIX_PATH = Regex(
        """(?:/Users|/home|/data/data|/data/user/\d+|/storage/emulated/\d+|/storage/self/primary|/sdcard)(?:/[^\s"'<>]*)""",
    )
    private val WIN_USER = Regex("""(?i)[A-Za-z]:\\Users\\[^\s"'<>]+""")
    private val AT = Regex("""^\s*at\s+(\S+)\((.*)\)\s*$""")
    private val CAUSE = Regex("""^\s*(Caused by:|Suppressed:)\s+([\w.$]+).*$""")
    private val MORE = Regex("""^\s*\.\.\.\s+\d+\s+more\s*$""")
    private val LOC = Regex("""^([\w$+\-.]+):(\d+)$""")

    fun secrets(text: String): String {
        if (text.isEmpty()) return text
        var out = URL.replace(text) { replaceUrl(it.value) }
        out = UNIX_PATH.replace(out, "<path>")
        out = WIN_USER.replace(out, "<path>")
        out = UUID.replace(out, "<uuid>")
        out = IPV6.replace(out, "<ip>")
        out = IPV4.replace(out, "<ip>")
        return out
    }

    /** 只留类名、方法名、行号。异常消息不放进堆栈。 */
    fun stackSkeleton(throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val out = ArrayList<String>(48)
        for (raw in sw.toString().lineSequence()) {
            if (out.size >= 60) {
                out.add("...")
                break
            }
            val line = raw.trimEnd()
            val at = AT.matchEntire(line)
            if (at != null) {
                out.add("at ${at.groupValues[1]}(${location(at.groupValues[2])})")
                continue
            }
            if (MORE.matchEntire(line) != null) {
                out.add(line.trimStart())
                continue
            }
            val cause = CAUSE.matchEntire(line)
            if (cause != null) {
                out.add("${cause.groupValues[1]} ${cause.groupValues[2]}")
            }
        }
        return out.joinToString("\n")
    }

    private fun replaceUrl(raw: String): String {
        var end = raw.length
        while (end > 0 && raw[end - 1] in URL_TRAIL) end--
        val scheme = raw.indexOf("://")
        if (scheme < 0 || end <= scheme + 3) return "<url>"
        return "<url>" + raw.substring(end)
    }

    private fun location(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "Native Method" || trimmed == "Unknown Source") return trimmed
        val base = trimmed.substringAfterLast('/').substringAfterLast('\\')
        val match = LOC.matchEntire(base) ?: return "Unknown Source"
        return "${match.groupValues[1]}:${match.groupValues[2]}"
    }
}
