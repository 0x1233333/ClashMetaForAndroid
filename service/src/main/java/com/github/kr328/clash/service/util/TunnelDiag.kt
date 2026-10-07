package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log

/**
 * :service 不能依赖 :app。采样器实现留在 app,由后台进程的 Application
 * 在服务起来之前把钩子装上。钩子本身不跑定时器,start/stop 跟隧道服务走。
 */
object TunnelDiag {
    interface Hooks {
        fun start(context: Context)
        fun stop()
    }

    @Volatile
    var hooks: Hooks? = null

    fun start(context: Context) {
        val installed = hooks
        if (installed == null) {
            Log.w("diag: sampler hook missing")
            return
        }
        installed.start(context)
    }

    fun stop() {
        hooks?.stop()
    }
}
