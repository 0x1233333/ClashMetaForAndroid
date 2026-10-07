package com.github.kr328.clash.design.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.TelegramPreferenceProvider

/**
 * 诊断包直传用的 Bot Token / Chat ID 以及自动上传开关。
 * 文件名是 [FILE_NAME]，备份规则排除 telegram_upload.xml，不进云备份。
 */
class TelegramStore(context: Context) {
    private val store = Store(
        TelegramPreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var botToken: String by store.string(
        key = "bot_token",
        defaultValue = "",
    )

    var chatId: String by store.string(
        key = "chat_id",
        defaultValue = "",
    )

    var autoUpload: Boolean by store.boolean(
        key = "auto_upload",
        defaultValue = false,
    )

    var uploadInterval: UploadInterval by store.enum(
        key = "upload_interval",
        defaultValue = UploadInterval.Hours24,
        values = UploadInterval.values(),
    )

    var uploadOnStall: Boolean by store.boolean(
        key = "upload_on_stall",
        defaultValue = false,
    )

    var selRouting: Boolean by store.boolean(
        key = "sel_routing",
        defaultValue = true,
    )

    var selEnv: Boolean by store.boolean(
        key = "sel_env",
        defaultValue = true,
    )

    var selKernelLog: Boolean by store.boolean(
        key = "sel_kernel_log",
        defaultValue = false,
    )

    var selCrashes: Boolean by store.boolean(
        key = "sel_crashes",
        defaultValue = false,
    )

    var selExtra: Boolean by store.boolean(
        key = "sel_extra",
        defaultValue = false,
    )

    var lastUploadAt: Long by store.long(
        key = "last_upload_at",
        defaultValue = 0L,
    )

    var lastUploadBytes: Long by store.long(
        key = "last_upload_bytes",
        defaultValue = 0L,
    )

    var lastUploadError: String by store.string(
        key = "last_upload_error",
        defaultValue = "",
    )

    var lastUploadRedacted: Boolean by store.boolean(
        key = "last_upload_redacted",
        defaultValue = true,
    )

    /** 逗号分隔:routing,env,kernel_log,crashes,extra。空表示还没记过。 */
    var lastUploadItems: String by store.string(
        key = "last_upload_items",
        defaultValue = "",
    )

    var lastAttemptAt: Long by store.long(
        key = "last_attempt_at",
        defaultValue = 0L,
    )

    var lastStallAttemptAt: Long by store.long(
        key = "last_stall_attempt_at",
        defaultValue = 0L,
    )

    var lastStalledGeneration: Long by store.long(
        key = "last_stalled_generation",
        defaultValue = 0L,
    )

    enum class UploadInterval(val hours: Int) {
        Hours6(6),
        Hours12(12),
        Hours24(24);

        val millis: Long get() = hours * 60L * 60L * 1000L
    }

    companion object {
        const val FILE_NAME = "telegram_upload"
    }
}
