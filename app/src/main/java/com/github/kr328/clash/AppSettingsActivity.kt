package com.github.kr328.clash

import android.content.pm.PackageManager
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.design.AppSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.withModelProgressBar
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.diag.AutoUploader
import com.github.kr328.clash.diag.DiagExporter
import com.github.kr328.clash.diag.KernelCapture
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.ApplicationObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class AppSettingsActivity : BaseActivity<AppSettingsDesign>(), Behavior {
    override suspend fun main() {
        val design = AppSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            this,
            clashRunning,
            ::onHideIconChange,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        AppSettingsDesign.Request.ReCreateAllActivities -> {
                            ApplicationObserver.createdActivities.forEach { activity ->
                                activity.recreate()
                            }
                        }
                        AppSettingsDesign.Request.StartKernelLog -> {
                            KernelCapture.startIfEnabled(this@AppSettingsActivity)
                        }
                        AppSettingsDesign.Request.PreviewUpload -> {
                            val text = try {
                                withContext(Dispatchers.IO) {
                                    DiagExporter.previewText(this@AppSettingsActivity, redact = true)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                design.showToast(R.string.share_failed, ToastDuration.Long)
                                null
                            }
                            if (text != null) {
                                design.showTextPage(R.string.preview_upload, text)
                            }
                        }
                        AppSettingsDesign.Request.UploadNow -> {
                            val outcome = try {
                                withModelProgressBar {
                                    configure {
                                        isIndeterminate = true
                                        text = getString(R.string.tg_uploading)
                                    }
                                    withContext(Dispatchers.IO) {
                                        AutoUploader.uploadNow(this@AppSettingsActivity)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AutoUploader.Outcome.Failed(e.message ?: e.javaClass.simpleName)
                            }
                            design.patchUploadStatus()
                            when (outcome) {
                                is AutoUploader.Outcome.Uploaded ->
                                    design.showToast(R.string.tg_upload_ok, ToastDuration.Short)
                                is AutoUploader.Outcome.Failed ->
                                    design.showToast(
                                        getString(R.string.tg_upload_failed) + ": " + outcome.reason,
                                        ToastDuration.Long,
                                    )
                                AutoUploader.Outcome.NotConfigured ->
                                    design.showToast(R.string.tg_not_configured, ToastDuration.Long)
                                AutoUploader.Outcome.SkippedEmpty ->
                                    design.showToast(R.string.tg_upload_skipped_empty, ToastDuration.Long)
                                AutoUploader.Outcome.SkippedTunnel ->
                                    design.showToast(R.string.tg_upload_skipped_tunnel, ToastDuration.Long)
                                AutoUploader.Outcome.Busy ->
                                    design.showToast(R.string.tg_uploading, ToastDuration.Short)
                                AutoUploader.Outcome.NotDue ->
                                    design.showToast(R.string.tg_upload_ok, ToastDuration.Short)
                            }
                        }
                    }
                }
            }
        }
    }

    override var autoRestart: Boolean
        get() {
            val status = packageManager.getComponentEnabledSetting(
                RestartReceiver::class.componentName
            )

            return status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        set(value) {
            val status = if (value)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            packageManager.setComponentEnabledSetting(
                RestartReceiver::class.componentName,
                status,
                PackageManager.DONT_KILL_APP,
            )
        }

    private fun onHideIconChange(hide: Boolean) {
        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        packageManager.setComponentEnabledSetting(
            mainActivityAlias,
            newState,
            PackageManager.DONT_KILL_APP
        )
    }
}
