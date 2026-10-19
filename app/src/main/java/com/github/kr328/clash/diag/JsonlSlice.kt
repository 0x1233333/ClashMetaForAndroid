package com.github.kr328.clash.diag

/**
 * jsonl 分块的行边界处理。
 *
 * 上传是按**字节**切的(单块 12MB、预览取尾部若干 MB),切点只有落在换行符之后才是干净的。
 * 切在行中间会同时产生两个问题:
 *  · 块尾留下未闭合的 JSON 片段 —— 接收端只能当坏行丢弃(实测每天 1~2 行);
 *  · 下一块的开头又是同一行的后半截,若不丢弃就会重复计入。
 *
 * 处理原则:半行一律不发,把游标停在**最后一个完整行之后**,剩余部分下一轮再取。
 * 这样既不产生坏行,也不丢数据 —— 只是晚一轮送达。
 */
internal object JsonlSlice {
    /** 换行符。 */
    const val NL: Byte = 10

    /**
     * 首部半行:[0, off) 内第一个换行符之后的偏移。
     * 整块只剩半行(没有任何换行)时返回 0,交由调用方原样发送 —— 否则游标会原地不动。
     */
    fun skipPartialHead(buf: ByteArray, off: Int): Int {
        if (off <= 1) return 0
        for (i in 0 until off) {
            if (buf[i] == NL) return if (i < off - 1) i + 1 else 0
        }
        return 0
    }

    /**
     * 尾部半行:返回「可以发送的长度」。
     *
     * 末字节是换行符 → 原样返回;否则回退到 [from, off) 内最后一个换行符之后。
     * 整段一个换行都没有时分两种情况:
     *  · [atEof] 为真(= 这段已经读到文件末尾)→ 一个字节都不发,等写完整了下一轮再取,
     *    否则发出去的就是未闭合的 JSON 片段;
     *  · 不是文件末尾(单行比块还大,正常数据不会出现)→ 原样发送,保证游标能前进。
     */
    fun keepLength(buf: ByteArray, off: Int, from: Int = 0, atEof: Boolean = true): Int {
        if (off <= from) return off
        if (buf[off - 1] == NL) return off
        var i = off - 1
        while (i >= from) {
            if (buf[i] == NL) return i + 1
            i--
        }
        return if (atEof) from else off
    }

    /** 整段字节的尾部半行裁剪(用于整体读文件的路径)。 */
    fun trimTrailingPartial(bytes: ByteArray): ByteArray {
        val keep = keepLength(bytes, bytes.size, 0)
        return if (keep >= bytes.size) bytes else bytes.copyOfRange(0, keep)
    }

    /** 整段字节的首部半行裁剪。 */
    fun trimLeadingPartial(bytes: ByteArray): ByteArray {
        val start = skipPartialHead(bytes, bytes.size)
        return if (start <= 0) bytes else bytes.copyOfRange(start, bytes.size)
    }
}
