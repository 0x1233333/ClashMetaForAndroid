package com.github.kr328.clash.diag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream

/**
 * 把用户选中的文件复制到 filesDir/diag/extra/。
 * 同名加序号,不覆盖。目录在打包时若不存在则跳过。
 */
internal object ExtraFiles {
    fun copyIn(context: Context, uris: List<Uri>): Int {
        val dir = File(context.applicationContext.filesDir, "diag/extra")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return 0
        var added = 0
        for (uri in uris) {
            val dest = allocate(dir, displayName(context, uri))
            try {
                val input = context.contentResolver.openInputStream(uri) ?: continue
                input.use { src ->
                    FileOutputStream(dest).use { out -> src.copyTo(out) }
                }
                added++
            } catch (e: CancellationException) {
                if (dest.exists()) dest.delete()
                throw e
            } catch (_: Exception) {
                if (dest.exists()) dest.delete()
            }
        }
        return added
    }

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val value = cursor.getString(index)
                        if (!value.isNullOrBlank()) return value
                    }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun allocate(dir: File, rawName: String): File {
        val safe = sanitize(rawName)
        val first = File(dir, safe)
        if (!first.exists()) return first
        val dot = safe.lastIndexOf('.')
        val stem: String
        val ext: String
        if (dot > 0) {
            stem = safe.substring(0, dot)
            ext = safe.substring(dot)
        } else {
            stem = safe
            ext = ""
        }
        var i = 1
        while (i < 10000) {
            val candidate = File(dir, "$stem-$i$ext")
            if (!candidate.exists()) return candidate
            i++
        }
        return File(dir, "$stem-${System.currentTimeMillis()}$ext")
    }

    private fun sanitize(raw: String): String {
        var name = raw.trim().replace('\\', '/')
        val slash = name.lastIndexOf('/')
        if (slash >= 0) name = name.substring(slash + 1)
        name = name.replace(Regex("""[\u0000-\u001F\u007F]"""), "")
        if (name.isEmpty() || name == "." || name == "..") name = "file"
        if (name.length > 120) {
            val dot = name.lastIndexOf('.')
            name = if (dot in 1 until 120 && name.length - dot <= 16) {
                val ext = name.substring(dot)
                name.substring(0, 120 - ext.length).trimEnd('.') + ext
            } else {
                name.take(120)
            }
        }
        return name
    }
}
