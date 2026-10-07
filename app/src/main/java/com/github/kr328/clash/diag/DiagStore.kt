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
                    Log.w("diag: rotate $fileName failed")
                }
            }
            val prefix = if (!active.exists() || active.length() == 0L) metaBytes() else null
            FileOutputStream(active, true).use { out ->
                if (prefix != null) out.write(prefix)
                out.write(payload.toByteArray(Charsets.UTF_8))
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

}
