package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.adapter.LogMessageAdapter
import com.github.kr328.clash.design.databinding.DesignLogcatBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogcatDesign(
    context: Context,
    private val streaming: Boolean,
) : Design<LogcatDesign.Request>(context) {
    sealed class Request {
        data class Share(val file: File) : Request()

        private object CloseAction : Request()
        private object DeleteAction : Request()
        private object ExportAction : Request()

        companion object {
            // design_logcat.xml offers these names as static fields.
            @JvmField val Close: Request = CloseAction
            @JvmField val Delete: Request = DeleteAction
            @JvmField val Export: Request = ExportAction
        }
    }

    fun requestShare(file: File) {
        launch(Dispatchers.IO) {
            try {
                requests.trySend(Request.Share(ensureShareable(file)))
            } catch (_: Exception) {
                showToast(R.string.share_failed, ToastDuration.Long)
            }
        }
    }

    fun shareCapturedLog() {
        launch {
            try {
                val messages = withContext(Dispatchers.Main) { adapter.messages.toList() }
                val file = withContext(Dispatchers.IO) { writeCapturedLog(messages) }
                requestShare(file)
            } catch (_: Exception) {
                showToast(R.string.share_failed, ToastDuration.Long)
            }
        }
    }

    private val binding = DesignLogcatBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = LogMessageAdapter(context) {
        launch {
            val data = ClipData.newPlainText("log_message", it.message)

            context.getSystemService<ClipboardManager>()?.setPrimaryClip(data)

            showToast(R.string.copied, ToastDuration.Short)
        }
    }

    suspend fun patchMessages(messages: List<LogMessage>, removed: Int, appended: Int) {
        withContext(Dispatchers.Main) {
            adapter.messages = messages

            adapter.notifyItemRangeInserted(adapter.messages.size, appended)
            adapter.notifyItemRangeRemoved(0, removed)

            if (streaming && binding.recyclerList.isTop) {
                binding.recyclerList.scrollToPosition(messages.size - 1)
            }
        }
    }

    override val root: View
        get() = binding.root

    private fun writeCapturedLog(messages: List<LogMessage>): File {
        val dir = File(context.cacheDir, "export")
        dir.mkdirs()
        val file = File(dir, "clash-logcat-${System.currentTimeMillis()}.txt")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            for (message in messages) {
                val time = message.time.format(context, includeDate = false)
                writer.appendLine("%12s %7s: %s".format(time, message.level.name, message.message))
            }
            writer.flush()
        }
        return file
    }

    private fun ensureShareable(file: File): File {
        val cache = context.cacheDir.canonicalFile
        val logsRoot = File(cache, "logs").canonicalFile
        val exportRoot = File(cache, "export").canonicalFile
        val canonical = file.canonicalFile
        if (isInside(canonical, logsRoot) || isInside(canonical, exportRoot)) {
            return file
        }
        exportRoot.mkdirs()
        val dest = exportDestination(exportRoot, file.name)
        file.copyTo(dest, overwrite = true)
        return dest
    }

    init {
        binding.self = this
        binding.streaming = streaming

        binding.activityBarLayout.applyFrom(context)

        binding.recyclerList.bindAppBarElevation(binding.activityBarLayout)

        binding.recyclerList.layoutManager = LinearLayoutManager(context).apply {
            if (streaming) {
                reverseLayout = true
                stackFromEnd = true
            }
        }
        binding.recyclerList.adapter = adapter
    }

    private companion object {
        fun isInside(file: File, root: File): Boolean {
            return file.path.startsWith(root.path + File.separator)
        }

        fun exportDestination(dir: File, name: String): File {
            val safe = File(name).name
            if (safe.isBlank() || safe == "." || safe == "..") {
                return File(dir, "clash-${System.currentTimeMillis()}.txt")
            }
            val dest = File(dir, safe)
            if (!dest.exists()) return dest
            val dot = safe.lastIndexOf('.')
            val renamed = if (dot > 0) {
                safe.substring(0, dot) + "-${System.currentTimeMillis()}" + safe.substring(dot)
            } else {
                "$safe-${System.currentTimeMillis()}"
            }
            return File(dir, renamed)
        }
    }
}
