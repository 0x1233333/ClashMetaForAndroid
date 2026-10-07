package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.adapter.LogFileAdapter
import com.github.kr328.clash.design.databinding.DesignLogsBinding
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class LogsDesign(context: Context) : Design<LogsDesign.Request>(context) {
    sealed class Request {
        object StartLogcat : Request()
        object DeleteAll : Request()
        object ExportDiag : Request()

        data class OpenFile(val file: LogFile) : Request()
        data class Share(val file: File) : Request()
    }

    private val binding = DesignLogsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = LogFileAdapter(context) {
        requests.trySend(Request.OpenFile(it))
    }

    override val root: View
        get() = binding.root

    suspend fun patchLogs(logs: List<LogFile>) {
        adapter.patchDataSet(adapter::logs, logs, false, LogFile::fileName)
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

    fun shareLatest() {
        launch(Dispatchers.IO) {
            try {
                val latest = File(context.cacheDir, "logs").listFiles()
                    ?.mapNotNull { file ->
                        if (!file.isFile) return@mapNotNull null
                        val parsed = LogFile.parseFromFileName(file.name) ?: return@mapNotNull null
                        file to parsed.date.time
                    }
                    ?.maxByOrNull { it.second }
                    ?.first
                if (latest == null) {
                    showToast(R.string.no_logs_to_share, ToastDuration.Short)
                    return@launch
                }
                requestShare(latest)
            } catch (_: Exception) {
                showToast(R.string.share_failed, ToastDuration.Long)
            }
        }
    }

    suspend fun requestDeleteAll(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.delete_all_logs)
                    .setMessage(R.string.delete_all_logs_warn)
                    .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
            }
        }
    }

    private fun installShareLongPress(row: View) {
        val listener = View.OnLongClickListener {
            showShareMenu(row)
        }
        fun walk(view: View) {
            view.setOnLongClickListener(listener)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index))
                }
            }
        }
        walk(row)
    }

    private fun showShareMenu(row: View): Boolean {
        val position = binding.recyclerList.getChildAdapterPosition(row)
        val log = adapter.logs.getOrNull(position) ?: return false
        PopupMenu(context, row).apply {
            menu.add(0, 0, 0, R.string.share)
            setOnMenuItemClickListener {
                requestShare(File(context.cacheDir, "logs").resolve(log.fileName))
                true
            }
        }.show()
        return true
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

        binding.activityBarLayout.applyFrom(context)

        binding.recyclerList.applyLinearAdapter(context, adapter)
        binding.recyclerList.addOnChildAttachStateChangeListener(object :
            RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                installShareLongPress(view)
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
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
