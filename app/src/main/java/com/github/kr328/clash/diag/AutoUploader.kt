package com.github.kr328.clash.diag

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.store.TelegramStore
import com.github.kr328.clash.service.StatusProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking

/**
 * 定期/卡顿自动上传诊断包。由 SmartHealthModule 的 10/30 分钟 tick 询问「是否到点」，
 * 不另起 AlarmManager / JobScheduler / WakeLock。
 * Bot Token 为空时立即返回，不会发任何网络请求。
 */
object AutoUploader {
    private const val UPLOAD_TIMEOUT_MS = 120_000L

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
        object SkippedConditions : Outcome()
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
            // 省电靠"择时":自动上传仅在充电或非计费网络(WiFi)时进行;手动触发(force)不受限
            if (!force && !conditionsOk(context)) {
                store.lastUploadError = "waiting for charging or wifi"
                busy.set(false)
                return Outcome.SkippedConditions
            }
        try {
            val packed = try {
                DiagExporter.packChunk(context, redact = true)
            } catch (e: Exception) {
                val reason = e.message?.take(180) ?: e.javaClass.simpleName
                store.lastUploadError = reason
                return Outcome.Failed(reason)
            }
            val zip = packed.file
            if (zip.length() <= 0L || zip.length() > MAX_ZIP_BYTES) {
                zip.delete()
                return Outcome.SkippedEmpty
            }

            store.lastAttemptAt = now
            if (force || stallNow) store.lastStallAttemptAt = now

            Log.i("auto sendDocument bot<REDACTED> redact=true size=${zip.length()}")
            val result = try {
                // 该函数是阻塞式调用,用 runBlocking 包住超时;整体封顶后可杜绝无限转圈
                kotlinx.coroutines.runBlocking {
                    withTimeout(UPLOAD_TIMEOUT_MS) {
                        TelegramUploader.upload(context, zip, token, chatId)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            return if (result.isSuccess) {
                DiagExporter.commitCursor(context, packed)
                zip.delete() // 上传成功即删,本地不留残留物
                store.lastUploadAt = System.currentTimeMillis()
                store.lastUploadBytes = zip.length()
                store.lastUploadError = ""
                store.lastUploadRedacted = true
                store.lastUploadItems = packed.items
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
    /** 充电中 或 接在非计费网络(WiFi/以太网)上 —— 二者满足其一即可自动上传。 */
    private fun conditionsOk(context: Context): Boolean {
        return try {
            val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val unmetered = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            charging || unmetered
        } catch (e: Exception) {
            true // 判断不了时不要卡住上传
        }
    }

}
