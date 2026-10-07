package com.github.kr328.clash.design.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

/**
 * 诊断包直传用的 Bot Token / Chat ID。
 * 文件名是 [FILE_NAME]，备份规则排除 telegram_upload.xml，不进云备份。
 */
class TelegramStore(context: Context) {
    private val store = Store(
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
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

    companion object {
        const val FILE_NAME = "telegram_upload"
    }
}
