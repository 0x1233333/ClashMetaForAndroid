package com.github.kr328.clash.diag

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.store.TelegramStore
import com.github.kr328.clash.service.StatusProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 定期/卡顿自动上传诊断包。由 SmartHealthModule 的 10/30 分钟 tick 询问「是否到点」，
 * 不另起 AlarmManager / JobScheduler / WakeLock。
 * Bot Token 为空时立即返回，不会发任何网络请求。
 */
object AutoUploader {
    private const val MAX_ZIP_BYTES = 8L * 1024L * 1024L
    private const val STALL_THROTTLE_MS = 30L * 60L * 1000L
    private const val auto_upload = "auto_upload"
    private const val upload_interval = "upload_interval"
    private const val upload_on_stall = "upload_on_stall"
    private const val last_upload = "last_upload"

    private val busy = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed class Outcome {
        data class Uploaded(val bytes: Long) : Outcome()
        data class Failed(val reason: String) : Outcome()
        object NotConfigured : Outcome()
        object SkippedEmpty : Outcome()
        object SkippedTunnel : Outcome()
        object Busy : Outcome()
        object NotDue : Outcome()
    }

    fun tick(context: Context) {
        val store = TelegramStore(context)
        if (store.botToken.trim().isEmpty()) return
        if (!store.autoUpload && !store.uploadOnStall) return
        val prefs = context
        scope.launch {
            runCatching { upload(prefs, force = false) }
                .onFailure { Log.w("auto sendDocument bot<REDACTED> ${it.javaClass.simpleName}") }
        }
        keys()
    }

    @Suppress("unused")
    private fun keys(): String = "$auto_upload $upload_interval $upload_on_stall $last_upload"

    fun uploadNow(context: Context): Outcome {
        return upload(context, force = true)
    }

    private fun upload(context: Context, force: Boolean): Outcome {
        val store = TelegramStore(context)
        val token = store.botToken.trim()
        val chatId = store.chatId.trim()
        if (token.isEmpty() || chatId.isEmpty()) return Outcome.NotConfigured
        if (!force && !store.autoUpload && !store.uploadOnStall) return Outcome.NotDue

        val now = System.currentTimeMillis()
        val stallNow = newStall(store)
        if (!force) {
            val intervalDue = store.autoUpload &&
                (store.lastAttemptAt <= 0L || now - store.lastAttemptAt >= store.uploadInterval.millis)
            val stallDue = store.uploadOnStall && stallNow &&
                (store.lastStallAttemptAt <= 0L || now - store.lastStallAttemptAt >= STALL_THROTTLE_MS)
            if (!intervalDue && !stallDue) return Outcome.NotDue
            if (!tunnelRunning()) return Outcome.SkippedTunnel
        }

        if (DiagExporter.sourcePayloadBytes(context) <= 0L) return Outcome.SkippedEmpty
        if (!busy.compareAndSet(false, true)) return Outcome.Busy
        try {
            val zip = try {
                buildUploadZip(context)
            } catch (e: Exception) {
                val reason = e.message?.take(180) ?: e.javaClass.simpleName
                store.lastUploadError = reason
                return Outcome.Failed(reason)
            } ?: return Outcome.SkippedEmpty

            store.lastAttemptAt = now
            if (force || stallNow) store.lastStallAttemptAt = now

            Log.i("auto sendDocument bot<REDACTED> redact=true size=${zip.length()}")
            val result = TelegramUploader.upload(context, zip, token, chatId)
            return if (result.isSuccess) {
                store.lastUploadAt = System.currentTimeMillis()
                store.lastUploadBytes = zip.length()
                store.lastUploadError = ""
                store.lastUploadRedacted = true
                store.lastStalledGeneration = RoutingSampler.stalledGeneration()
                Outcome.Uploaded(zip.length())
            } else {
                val reason = result.exceptionOrNull()?.message?.take(180) ?: "error"
                store.lastUploadError = reason
                Outcome.Failed(reason)
            }
        } finally {
            busy.set(false)
        }
    }

    private fun buildUploadZip(context: Context): File? {
        val full = DiagExporter.buildBundle(context, redact = true, includeKernel = true)
        if (full.length() <= 0L) {
            full.delete()
            return null
        }
        if (full.length() <= MAX_ZIP_BYTES) return full
        full.delete()
        val slim = DiagExporter.buildBundle(context, redact = true, includeKernel = false)
        if (slim.length() <= 0L) {
            slim.delete()
            return null
        }
        return slim
    }

    private fun newStall(store: TelegramStore): Boolean {
        val gen = RoutingSampler.stalledGeneration()
        val last = store.lastStalledGeneration
        if (gen < last) {
            store.lastStalledGeneration = gen
            return false
        }
        return gen > last
    }

    private fun tunnelRunning(): Boolean {
        return try {
            StatusProvider.shouldStartClashOnBoot
        } catch (e: Exception) {
            false
        }
    }
}
