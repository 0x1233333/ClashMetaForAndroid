package com.github.kr328.clash.diag

import android.content.Context
import com.github.kr328.clash.common.log.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 追加写 filesDir/diag/ 下的一个 jsonl。
 * 单文件超过 20MB 时滚到同名加 .1,旧的 .1 删掉。每个实例按自己的文件长度滚动。
 */
internal class DiagStore(
    context: Context,
    private val fileName: String,
    /** 单文件上限;配合游标增量上传,routing 放宽到 200MB(约 60 天)(实测 354B/条 × 10s ≈ 2.9MB/天,7 天 20.4MB),weights 很小 */
    private val maxBytes: Long = 200L * 1024L * 1024L,
    /** 非空时保证文件第一行是这一条,后续追加不再重复写。 */
    private val metaLine: String? = null,
) {
    private val dir = File(context.applicationContext.filesDir, "diag")
    private val active = File(dir, fileName)
    private val rotated = File(dir, "$fileName.1")
    private val lock = Any()

    init {
        // 启动自愈:上一轮若因轮转失败/异常导致文件越过上限,这里直接截断。
        // 否则该文件会既写不进去(超限)、又轮转不成(异常路径),上传永远是空的
        // ——实测曾因此让选路数据断流数天,而只在 append 里兜底是救不回来的。
        try {
            if (active.isFile && active.length() >= maxBytes) {
                Log.w("diag: $fileName over cap at startup (${active.length()} bytes), truncating")
                FileOutputStream(active, false).use { }
            }
        } catch (e: Exception) {
            Log.w("diag: startup truncate $fileName failed: ${e.javaClass.simpleName}")
        }
    }

    fun appendAll(lines: List<String>) {
        if (lines.isEmpty()) return
        val payload = buildString {
            for (line in lines) {
                if (line.isEmpty()) continue
                append(line)
                if (!line.endsWith("\n")) append('\n')
            }
        }
        if (payload.isEmpty()) return
        synchronized(lock) {
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                Log.w("diag: mkdir ${dir.absolutePath} failed")
                return
            }
            ensureMetaOnDisk()
            if (active.length() >= maxBytes) {
                if (rotated.exists() && !rotated.delete()) {
                    Log.w("diag: delete ${rotated.name} failed")
                }
                if (!active.renameTo(rotated)) {
                    // 轮转失败时兜底截断:文件已超上限,若继续追加会让它无限增长,
                    // 而轮转又永远做不成(实测曾导致该文件卡死不再更新、上传恒为空)。
                    Log.w("diag: rotate $fileName failed, truncating instead")
                    try {
                        java.io.FileOutputStream(active, false).use { }
                    } catch (e: Exception) {
                        Log.w("diag: truncate $fileName failed: ${e.javaClass.simpleName}")
                    }
                }
            }
            val prefix = if (!active.exists() || active.length() == 0L) metaBytes() else null
            val body = payload.toByteArray(Charsets.UTF_8)
            val before = if (active.exists()) active.length() else 0L
            FileOutputStream(active, true).use { out ->
                if (prefix != null) out.write(prefix)
                out.write(body)
            }
            // 写入后校验落盘结果。实测曾出现文件长度不再增长(routing 卡在 72MiB 数天),
            // 此时采集协程仍在跑、也仍在 append,但上传端每轮拿到的都是同一份旧文件,
            // 于是数据静默断流而没有任何报错。发现没写进去就把旧文件移走重建一次。
            val after = if (active.exists()) active.length() else 0L
            val expect = before + body.size + (prefix?.size ?: 0)
            if (after < expect) {
                Log.w("diag: $fileName append not persisted (before=$before after=$after expect=$expect), rebuilding")
                try {
                    if (rotated.exists()) rotated.delete()
                    if (!active.renameTo(rotated)) {
                        FileOutputStream(active, false).use { }
                    }
                } catch (e: Exception) {
                    Log.w("diag: rebuild $fileName failed: ${e.javaClass.simpleName}")
                }
                try {
                    val p2 = metaBytes()
                    FileOutputStream(active, true).use { out ->
                        if (p2 != null) out.write(p2)
                        out.write(body)
                    }
                } catch (e: Exception) {
                    Log.w("diag: retry append $fileName failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** 旧文件缺说明时补到第一行,只做一次。 */
    private fun ensureMetaOnDisk() {
        val prefix = metaBytes() ?: return
        if (!active.isFile || active.length() == 0L) return
        val first = active.bufferedReader(Charsets.UTF_8).use { it.readLine() } ?: return
        if (first.contains("\"_meta\"")) return
        val existing = active.readBytes()
        FileOutputStream(active, false).use { out ->
            out.write(prefix)
            out.write(existing)
        }
    }

    private fun metaBytes(): ByteArray? {
        val line = metaLine ?: return null
        val text = if (line.endsWith("\n")) line else line + "\n"
        return text.toByteArray(Charsets.UTF_8)
    }


    companion object {
        /** 采集文件上限,与实例默认值保持一致。 */
        private const val CAP: Long = 200L * 1024L * 1024L

        /**
         * 启动自愈:把超过上限的采集文件截断。
         * 必须在 App 启动时无条件调用 —— 只写在实例 init 里不够,因为实例要等隧道起来才构造,
         * 一旦文件因轮转失败卡在超限状态,采集会长期停更而自愈永远不会执行。
         */
        @JvmStatic
        fun selfHealAll(context: Context) {
            val dir = File(context.applicationContext.filesDir, "diag")
            for (name in listOf("routing.jsonl", "weights.jsonl", "state.jsonl", "crashes.jsonl")) {
                try {
                    val f = File(dir, name)
                    if (f.isFile && f.length() >= CAP) {
                        Log.w("diag: $name over cap at startup (${f.length()} bytes), truncating")
                        FileOutputStream(f, false).use { }
                    }
                } catch (e: Exception) {
                    Log.w("diag: selfHeal $name failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }
}
