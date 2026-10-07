package com.github.kr328.clash

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setFileName
import com.github.kr328.clash.design.LogsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.withModelProgressBar
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.store.TelegramStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.diag.DiagExporter
import com.github.kr328.clash.diag.TelegramUploader
import com.github.kr328.clash.util.logsDir
import kotlinx.coroutines.CancellationException
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
                            val packed = try {
                                withContext(Dispatchers.IO) {
                                    DiagExporter.pack(this@LogsActivity, redact = true)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                design.showToast(R.string.share_failed, ToastDuration.Long)
                                null
                            }
                            if (packed != null) {
                                when (design.requestExportChoice()) {
                                    LogsDesign.ExportChoice.Share -> shareFile(design, packed.file)
                                    LogsDesign.ExportChoice.Telegram -> {
                                        try {
                                            uploadToTelegram(design, packed.file, packed.items)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            design.showToast(R.string.tg_upload_failed, ToastDuration.Long)
                                            shareFile(design, packed.file)
                                        }
                                    }
                                    null -> Unit
                                }
                            }
                        }
                        LogsDesign.Request.SendTelegram -> {
                            val store = TelegramStore(this@LogsActivity)
                            if (store.botToken.trim().isEmpty() || store.chatId.trim().isEmpty()) {
                                design.showToast(R.string.tg_not_configured, ToastDuration.Long)
                            } else {
                                val packed = try {
                                    withContext(Dispatchers.IO) {
                                        DiagExporter.pack(this@LogsActivity, redact = true)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    design.showToast(R.string.share_failed, ToastDuration.Long)
                                    null
                                }
                                if (packed != null) {
                                    try {
                                        uploadToTelegram(design, packed.file, packed.items)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        design.showToast(R.string.tg_upload_failed, ToastDuration.Long)
                                        shareFile(design, packed.file)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun uploadToTelegram(design: LogsDesign, zip: File, items: String) {
        val store = TelegramStore(this)
        val token = store.botToken.trim()
        val chatId = store.chatId.trim()
        if (token.isEmpty() || chatId.isEmpty()) {
            design.showToast(R.string.tg_not_configured, ToastDuration.Long)
            return
        }
        var result: Result<Unit>? = null
        withModelProgressBar {
            configure {
                isIndeterminate = true
                text = getString(R.string.tg_uploading)
            }
            result = withContext(Dispatchers.IO) {
                TelegramUploader.upload(this@LogsActivity, zip, token, chatId)
            }
        }
        val upload = result ?: Result.failure(IllegalStateException("upload"))
        if (upload.isSuccess) {
            store.lastUploadAt = System.currentTimeMillis()
            store.lastUploadBytes = zip.length()
            store.lastUploadError = ""
            store.lastUploadRedacted = true
            store.lastUploadItems = items
            design.showToast(R.string.tg_upload_ok, ToastDuration.Short)
        } else {
            val reason = upload.exceptionOrNull()?.message?.take(180) ?: "error"
            design.showToast(
                getString(R.string.tg_upload_failed) + ": " + reason,
                ToastDuration.Long,
            )
            shareFile(design, zip)
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