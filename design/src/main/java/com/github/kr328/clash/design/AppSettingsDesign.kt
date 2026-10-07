package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.TelegramStore
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppSettingsDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceStore,
    behavior: Behavior,
    running: Boolean,
    onHideIconChange: (hide: Boolean) -> Unit,
) : Design<AppSettingsDesign.Request>(context) {
    enum class Request {
        ReCreateAllActivities,
        UploadNow,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)
    private val telegram = TelegramStore(context)
    private lateinit var lastUploadPref: ClickablePreference

    override val root: View
        get() = binding.root

    fun patchUploadStatus() {
        if (!::lastUploadPref.isInitialized) return
        lastUploadPref.summary = formatUploadStatus(context, telegram)
    }

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.behavior)

            switch(
                value = behavior::autoRestart,
                icon = R.drawable.ic_baseline_restore,
                title = R.string.auto_restart,
                summary = R.string.allow_clash_auto_restart,
            )

            category(R.string.interface_)

            selectableList(
                value = uiStore::darkMode,
                values = DarkMode.values(),
                valuesText = arrayOf(
                    R.string.follow_system_android_10,
                    R.string.always_light,
                    R.string.always_dark
                ),
                icon = R.drawable.ic_baseline_brightness_4,
                title = R.string.dark_mode
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            switch(
                value = uiStore::hideAppIcon,
                icon = R.drawable.ic_baseline_hide,
                title = R.string.hide_app_icon_title,
                summary = R.string.hide_app_icon_desc,
            ) {
                listener = OnChangedListener {
                    onHideIconChange(uiStore::hideAppIcon.get())
                }
            }

            switch(
                value = uiStore::hideFromRecents,
                icon = R.drawable.ic_baseline_stack,
                title = R.string.hide_from_recents_title,
                summary = R.string.hide_from_recents_desc,
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            category(R.string.service)

            switch(
                value = srvStore::dynamicNotification,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.show_traffic,
                summary = R.string.show_traffic_summary
            ) {
                enabled = !running
            }

            category(R.string.telegram)

            tips(R.string.tg_settings_hint)

            val extraViews = ArrayList<View>()

            fun showTelegramExtras(show: Boolean) {
                val vis = if (show) View.VISIBLE else View.GONE
                extraViews.forEach { it.visibility = vis }
            }

            val tokenHolder = object {
                var botToken: String
                    get() = telegram.botToken
                    set(value) {
                        telegram.botToken = value
                        launch(Dispatchers.Main) {
                            showTelegramExtras(value.trim().isNotEmpty())
                            patchUploadStatus()
                        }
                    }
            }

            editableText(
                value = tokenHolder::botToken,
                adapter = trimmedText,
                title = R.string.bot_token,
                icon = R.drawable.ic_baseline_key,
                empty = R.string.not_set,
                maskSummary = true,
            )

            editableText(
                value = telegram::chatId,
                adapter = trimmedText,
                title = R.string.chat_id,
                icon = R.drawable.ic_baseline_assignment,
                empty = R.string.not_set,
            )

            val autoPref = switch(
                value = telegram::autoUpload,
                icon = R.drawable.ic_baseline_publish,
                title = R.string.auto_upload,
                summary = R.string.auto_upload_summary,
            ) {
                listener = OnChangedListener { patchUploadStatus() }
            }
            extraViews.add(autoPref.view)

            val intervalPref = selectableList(
                value = telegram::uploadInterval,
                values = TelegramStore.UploadInterval.values(),
                valuesText = arrayOf(
                    R.string.upload_interval_6h,
                    R.string.upload_interval_12h,
                    R.string.upload_interval_24h,
                ),
                icon = R.drawable.ic_baseline_update,
                title = R.string.upload_interval,
            ) {
                listener = OnChangedListener { patchUploadStatus() }
            }
            extraViews.add(intervalPref.view)

            val stallPref = switch(
                value = telegram::uploadOnStall,
                icon = R.drawable.ic_baseline_flash_on,
                title = R.string.upload_on_stall,
                summary = R.string.upload_on_stall_summary,
            )
            extraViews.add(stallPref.view)

            lastUploadPref = clickable(
                title = R.string.last_upload,
                icon = R.drawable.ic_baseline_info,
            )
            lastUploadPref.view.isClickable = false
            lastUploadPref.view.isFocusable = false
            extraViews.add(lastUploadPref.view)
            patchUploadStatus()

            val nowPref = clickable(
                title = R.string.upload_now,
                icon = R.drawable.ic_baseline_send,
            ) {
                clicked {
                    requests.trySend(Request.UploadNow)
                }
            }
            extraViews.add(nowPref.view)

            showTelegramExtras(telegram.botToken.trim().isNotEmpty())
        }

        binding.content.addView(screen.root)
    }
}

private val trimmedText = object : NullableTextAdapter<String> {
    override fun from(value: String): String = value
    override fun to(text: String?): String = text?.trim().orEmpty()
}

private fun formatUploadStatus(context: Context, store: TelegramStore): String {
    val last = store.lastUploadAt
    val err = store.lastUploadError
    val parts = ArrayList<String>()
    if (last <= 0L && err.isEmpty()) {
        parts.add(context.getString(R.string.last_upload_never))
    } else {
        if (last > 0L) {
            parts.add(
                context.getString(
                    R.string.last_upload_summary,
                    formatWhen(last),
                    formatSize(store.lastUploadBytes),
                )
            )
        }
        if (err.isNotEmpty()) {
            parts.add(context.getString(R.string.last_upload_failed, err.take(120)))
        }
    }
    if (store.autoUpload) {
        val next = if (store.lastAttemptAt <= 0L) 0L else store.lastAttemptAt + store.uploadInterval.millis
        if (next <= 0L || next <= System.currentTimeMillis()) {
            parts.add(context.getString(R.string.next_upload_soon))
        } else {
            parts.add(context.getString(R.string.next_upload_summary, formatWhen(next)))
        }
    }
    return parts.joinToString("\n")
}

private fun formatWhen(ms: Long): String {
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
