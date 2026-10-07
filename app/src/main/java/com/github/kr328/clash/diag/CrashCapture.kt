package com.github.kr328.clash.diag

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 未捕获异常写一条到 filesDir/diag/crashes.jsonl,然后由调用方转交原来的 handler。
 * 崩溃当次只追加这一行,不访问网络,也不读大文件。超过 200 条的裁剪放在下次启动。
 */
internal object CrashCapture {
    const val MAX_RECORDS = 200

    private const val MAX_MESSAGE = 500
    private const val MAX_STACK = 4096
    private const val MAX_MAINTAIN_BYTES = 1024 * 1024
    private val watching = AtomicBoolean(false)
    private val lastScreen = AtomicReference("")
    private val lock = Any()

    /** 正常打包时若已经问过本地控制器,崩溃路径只读这个缓存,不再发请求。 */
    internal object KernelVersionCache {
        @Volatile
        var value: String = ""
    }

    fun watch(app: Application) {
        if (!watching.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                remember(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                remember(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        Thread({
            try {
                maintain(app)
            } catch (_: Throwable) {
            }
        }, "diag-crash-trim").apply { isDaemon = true }.start()
    }

    fun record(context: Context, thread: Thread, throwable: Throwable) {
        val line = buildLine(context, thread, throwable)
        val dir = File(context.filesDir, "diag")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return
        synchronized(lock) {
            val raf = try {
                RandomAccessFile(File(dir, "crashes.jsonl.lock"), "rw")
            } catch (_: Throwable) {
                appendUnlocked(dir, line)
                return
            }
            try {
                val fileLock = try {
                    raf.channel.lock()
                } catch (_: Throwable) {
                    null
                }
                try {
                    appendUnlocked(dir, line)
                } finally {
                    try {
                        fileLock?.release()
                    } catch (_: Throwable) {
                    }
                }
            } finally {
                try {
                    raf.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun remember(activity: Activity) {
        val name = activity.javaClass.simpleName.ifEmpty {
            activity.javaClass.name.substringAfterLast('.')
        }
        if (name.isNotEmpty() && name.length <= 80 && name.all { it.isLetterOrDigit() || it == '_' }) {
            lastScreen.set(name)
        }
    }

    private fun buildLine(context: Context, thread: Thread, throwable: Throwable): String {
        val obj = JSONObject()
        obj.put("ts", DiagJson.formatTs(System.currentTimeMillis()))
        obj.put("thread", clip(DiagRedact.secrets(thread.name ?: ""), 80))
        obj.put("exception", throwable.javaClass.name)
        obj.put("message", clip(DiagRedact.secrets(throwable.message ?: ""), MAX_MESSAGE))
        obj.put("stack", clip(DiagRedact.secrets(DiagRedact.stackSkeleton(throwable)), MAX_STACK))
        obj.put("app_version", appVersion(context))
        obj.put("kernel_version", clip(DiagRedact.secrets(KernelVersionCache.value), 80))
        obj.put("screen", screen(context))
        obj.put("last_screen", lastScreen.get())
        return obj.toString().replace("\r", "").replace("\n", "")
    }

    private fun appendUnlocked(dir: File, line: String) {
        val file = File(dir, "crashes.jsonl")
        val fresh = !file.exists() || file.length() == 0L
        FileOutputStream(file, true).use { out ->
            if (fresh) out.write(metaBytes())
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
        }
    }

    /** 启动时把环形收到 200 条。不在崩溃当次调用。 */
    private fun maintain(context: Context) {
        val dir = File(context.filesDir, "diag")
        val file = File(dir, "crashes.jsonl")
        if (!file.isFile || file.length() == 0L) return
        synchronized(lock) {
            RandomAccessFile(File(dir, "crashes.jsonl.lock"), "rw").use { raf ->
                val fileLock = raf.channel.lock()
                try {
                    val truncated = file.length() > MAX_MAINTAIN_BYTES
                    val text = if (truncated) {
                        String(readTail(file, MAX_MAINTAIN_BYTES), Charsets.UTF_8)
                    } else {
                        file.readText(Charsets.UTF_8)
                    }
                    val rebuilt = rebuild(text, truncated) ?: return
                    writeAtomically(dir, file, rebuilt)
                } finally {
                    try {
                        fileLock.release()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun rebuild(text: String, force: Boolean): String? {
        val data = ArrayList<String>()
        var hasMeta = false
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            if (line.trimStart().startsWith("{\"_meta\"")) {
                hasMeta = true
                continue
            }
            if (line.startsWith("{")) data.add(line)
        }
        if (!force && hasMeta && data.size <= MAX_RECORDS) return null
        val kept = if (data.size > MAX_RECORDS) data.takeLast(MAX_RECORDS) else data
        return buildString {
            append(DiagJson.CRASHES_META)
            append('\n')
            for (row in kept) {
                append(row)
                append('\n')
            }
        }
    }

    private fun writeAtomically(dir: File, file: File, text: String) {
        val tmp = File(dir, "crashes.jsonl.tmp")
        FileOutputStream(tmp, false).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        if (file.exists() && !file.delete()) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
            return
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun readTail(file: File, maxBytes: Int): ByteArray {
        val len = file.length()
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

    private fun metaBytes(): ByteArray {
        val line = DiagJson.CRASHES_META
        val text = if (line.endsWith("\n")) line else line + "\n"
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun appVersion(context: Context): String {
        return try {
            val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            clip(DiagRedact.secrets(name ?: ""), 80)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun screen(context: Context): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "off"
            if (pm.isInteractive) "on" else "off"
        } catch (_: Throwable) {
            "off"
        }
    }

    private fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.substring(0, max)
    }
}
