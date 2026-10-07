package com.github.kr328.clash.diag

import android.content.Context
import com.github.kr328.clash.LogcatService
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.store.TelegramStore

/**
 * 勾选「内核日志」时，用和日志页 Tap to start 相同的方式拉起 [LogcatService]。
 * 取消勾选不调用 stop：用户手动开着的采集留着。
 */
internal object KernelCapture {
    fun startIfEnabled(context: Context) {
        val store = try {
            TelegramStore(context)
        } catch (e: Exception) {
            Log.w("diag: kernel capture prefs unreadable: ${e.javaClass.simpleName}")
            return
        }
        if (!store.selKernelLog) return
        if (LogcatService.running) return
        try {
            context.applicationContext.startForegroundServiceCompat(LogcatService::class.intent)
        } catch (e: Exception) {
            Log.w("diag: kernel capture start failed: ${e.javaClass.simpleName}")
        }
    }
}
