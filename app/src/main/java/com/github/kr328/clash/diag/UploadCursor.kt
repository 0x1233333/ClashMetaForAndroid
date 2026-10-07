package com.github.kr328.clash.diag

import android.content.Context
import com.github.kr328.clash.common.log.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * 上传游标:记录每个数据文件「已确认送达」的字节位置(参照 Filebeat 的 registry 思路)。
 *
 * 每次上传只发送每个文件 [cursor, cursor+chunk] 的区间;上传成功后才推进游标。
 * 因此:本地保留全量、上传零丢失、失败可续传,且不必一次搬运几十 MB。
 */
internal object UploadCursor {
    private const val FILE_NAME = "upload_cursor.json"

    /** 单次上传的原始字节上限(压缩前)。Telegram 机器人下载上限 20MB,留足余量。 */
    const val CHUNK_BYTES = 12L * 1024L * 1024L

    private fun file(context: Context): File = File(File(context.filesDir, "diag"), FILE_NAME)

    fun read(context: Context): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        try {
            val f = file(context)
            if (!f.isFile) return out
            val json = JSONObject(f.readText(Charsets.UTF_8))
            for (key in json.keys()) {
                val v = json.optLong(key, 0L)
                if (v > 0L) out[key] = v
            }
        } catch (e: Exception) {
            Log.w("diag: read cursor failed ${e.javaClass.simpleName}")
        }
        return out
    }

    fun write(context: Context, cursor: Map<String, Long>) {
        try {
            val f = file(context)
            f.parentFile?.mkdirs()
            val json = JSONObject()
            for ((k, v) in cursor) if (v > 0L) json.put(k, v)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w("diag: write cursor failed ${e.javaClass.simpleName}")
        }
    }

    /** 某文件当前长度(不存在按 0)。 */
    fun size(context: Context, name: String): Long {
        val f = File(File(context.filesDir, "diag"), name)
        return if (f.isFile) f.length() else 0L
    }

    /**
     * 读取 [from, from+limit) 区间。返回字节与「实际结束位置」。
     * 若从中间截断,会**丢弃首个半行**(避免出现未闭合的 JSON 片段)。
     */
    fun readRange(context: Context, name: String, from: Long, limit: Long): Pair<ByteArray, Long> {
        val f = File(File(context.filesDir, "diag"), name)
        if (!f.isFile) return ByteArray(0) to from
        val len = f.length()
        // 文件缩短(轮转/被清理)时游标可能越过文件末尾 —— 此时从 0 重新开始,
        // 否则该文件将永远无法再上传(游标只增不减会卡死)
        val effectiveFrom = if (from > len) 0L else from
        if (effectiveFrom >= len) return ByteArray(0) to effectiveFrom
        val end = minOf(len, effectiveFrom + limit)
        val want = (end - effectiveFrom).toInt()
        if (want <= 0) return ByteArray(0) to effectiveFrom
        val buf = ByteArray(want)
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(effectiveFrom)
            var off = 0
            while (off < want) {
                val n = raf.read(buf, off, want - off)
                if (n <= 0) break
                off += n
            }
            if (off < want) {
                return buf.copyOf(off) to (effectiveFrom + off)
            }
        }
        // 只有当 from 落在【行中间】时才需要丢掉首个半行:
        // 判断依据 = from 前一个字节不是换行(且 from > 0)
        if (from > 0L) {
            val prev = ByteArray(1)
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(from - 1)
                raf.readFully(prev)
            }
            if (prev[0] != '\n'.code.toByte()) {
                val nl = buf.indexOf('\n'.code.toByte())
                if (nl in 0 until buf.size - 1) {
                    // 半行丢弃(不发送);但【游标必须推进到本块末尾】—— 因为剩下的部分已经全部发出
                    return buf.copyOfRange(nl + 1, buf.size) to end
                }
            }
        }
        return buf to end
    }
}
