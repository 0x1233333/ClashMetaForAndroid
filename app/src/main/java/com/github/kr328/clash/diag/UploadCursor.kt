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
     *
     * 两端都要落在行边界上:开头若切在行中间,丢弃首个半行;结尾若切在行中间,同样不回传这半行,
     * 并把结束位置停在最后一个换行符之后 —— 下一轮从这里继续读,那半行到那时已经写完整。
     * 只丢开头不丢结尾时,块尾会留下未闭合的 JSON 片段,接收端只能当坏行扔掉(实测每天 1~2 行)。
     */
    fun readRange(context: Context, name: String, from: Long, limit: Long): Pair<ByteArray, Long> =
        readRange(File(File(context.filesDir, "diag"), name), from, limit)

    /** 同上,直接对文件操作(不依赖 Android 上下文,便于本地用真实数据复现切块行为)。 */
    fun readRange(file: File, from: Long, limit: Long): Pair<ByteArray, Long> {
        if (!file.isFile) return ByteArray(0) to from
        val len = file.length()
        // 文件缩短(轮转/被清理)时游标可能越过文件末尾 —— 此时从 0 重新开始,
        // 否则该文件将永远无法再上传(游标只增不减会卡死)
        // 游标恰好等于文件长度时也必须回到开头:
        // 文件若因轮转/异常停在某个长度,游标也正好卡在那个位置,用 ">" 判断会永远返回空,
        // 该文件就再也传不出任何内容(实测选路数据因此断流数天)。
        val effectiveFrom = if (from >= len) 0L else from
        if (len == 0L) return ByteArray(0) to 0L
        val end = minOf(len, effectiveFrom + limit)
        val want = (end - effectiveFrom).toInt()
        if (want <= 0) return ByteArray(0) to effectiveFrom
        val buf = ByteArray(want)
        var off = 0
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(effectiveFrom)
            while (off < want) {
                val n = raf.read(buf, off, want - off)
                if (n <= 0) break
                off += n
            }
        }
        if (off <= 0) return ByteArray(0) to effectiveFrom

        // 只有当 from 落在【行中间】时才需要丢掉首个半行:判断依据 = from 前一个字节不是换行
        var start = 0
        if (effectiveFrom > 0L) {
            val prev = ByteArray(1)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(effectiveFrom - 1)
                raf.readFully(prev)
            }
            if (prev[0] != JsonlSlice.NL) start = JsonlSlice.skipPartialHead(buf, off)
        }

        // 尾部半行不发,游标停在最后一个完整行之后;剩余部分下一轮再传。
        // atEof:本段是否已经读到文件末尾 —— 到末尾且末行没写完时,宁可这一轮什么都不发,
        // 也不能把半行当成完整记录送出去(接收端只能当坏行丢弃,且那半行的后半截再也取不回来)。
        val atEof = effectiveFrom + off >= len
        val keep = JsonlSlice.keepLength(buf, off, start, atEof)
        if (keep < off) {
            Log.w("diag: partial tail line trimmed (jsonl), resend next round")
        }
        return buf.copyOfRange(start, keep) to (effectiveFrom + keep)
    }
}
