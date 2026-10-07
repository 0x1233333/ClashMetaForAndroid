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
    /** 单文件上限;routing 用 40MB(实测 354B/条 × 10s ≈ 2.9MB/天,7 天 20.4MB),weights 很小 */
    private val maxBytes: Long = 40L * 1024L * 1024L,
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
            if (active.length() >= maxBytes) {
                if (rotated.exists() && !rotated.delete()) {
                    Log.w("diag: delete ${rotated.name} failed")
                }
                if (!active.renameTo(rotated)) {
                    Log.w("diag: rotate $fileName failed")
                }
            }
            FileOutputStream(active, true).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }
        }
    }

}
