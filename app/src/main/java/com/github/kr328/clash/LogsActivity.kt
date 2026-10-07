package com.github.kr328.clash

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setFileName
import com.github.kr328.clash.design.LogsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.diag.DiagExporter
import com.github.kr328.clash.util.logsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File

class LogsActivity : BaseActivity<LogsDesign>() {

    override suspend fun main() {
        val design = LogsDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            val files = withContext(Dispatchers.IO) {
                                loadFiles()
                            }

                            design.patchLogs(files)
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        LogsDesign.Request.StartLogcat -> {
                            startActivity(LogcatActivity::class.intent)
                            finish()
                        }
                        LogsDesign.Request.DeleteAll -> {
                            if (design.requestDeleteAll()) {
                                withContext(Dispatchers.IO) {
                                    deleteAllLogs()
                                }

                                events.trySend(Event.ActivityStart)
                            }
                        }
                        is LogsDesign.Request.OpenFile -> {
                            startActivity(LogcatActivity::class.intent.setFileName(it.file.fileName))
                        }
                        is LogsDesign.Request.Share -> {
                            shareFile(design, it.file)
                        }
                        LogsDesign.Request.ExportDiag -> {
                            val zip = try {
                                withContext(Dispatchers.IO) {
                                    DiagExporter.buildBundle(this@LogsActivity)
                                }
                            } catch (e: Exception) {
                                design.showToast(R.string.share_failed, ToastDuration.Long)
                                null
                            }
                            if (zip != null) shareFile(design, zip)
                        }
                    }
                }
            }
        }
    }

    private suspend fun shareFile(design: LogsDesign, file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = if (file.extension.equals("zip", ignoreCase = true)) {
                "application/zip"
            } else {
                "text/plain"
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (_: Exception) {
            design.showToast(R.string.share_failed, ToastDuration.Long)
        }
    }

    private fun loadFiles(): List<LogFile> {
        val list = cacheDir.resolve("logs").listFiles()?.toList() ?: emptyList()

        return list.mapNotNull { LogFile.parseFromFileName(it.name) }
    }

    private fun deleteAllLogs() {
        logsDir.deleteRecursively()
    }
}