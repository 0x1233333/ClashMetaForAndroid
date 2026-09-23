package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.ProfileReceiver
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Safety net for subscription auto-update while the VPN is running.
 *
 * The stock chain (AlarmManager -> ProfileWorker) permanently breaks after a
 * single failed attempt, and inexact alarms can be deferred for hours in Doze.
 * This module periodically checks each URL profile's age and immediately
 * requests an update when its interval has elapsed.
 */
class ProfileAutoUpdateModule(service: Service) : Module<Unit>(service) {
    companion object {
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L
        private val MIN_INTERVAL = TimeUnit.MINUTES.toMillis(15)
    }

    override suspend fun run() {
        delay(TimeUnit.MINUTES.toMillis(2))

        while (true) {
            checkDueProfiles()

            delay(CHECK_INTERVAL_MS)
        }
    }

    private suspend fun checkDueProfiles() {
        runCatching {
            val now = System.currentTimeMillis()

            ImportedDao().queryAllUUIDs()
                .mapNotNull { ImportedDao().queryByUUID(it) }
                .filter { it.type != Profile.Type.File && it.interval >= MIN_INTERVAL }
                .forEach { imported: Imported ->
                    val last = service.importedDir
                        .resolve(imported.uuid.toString())
                        .resolve("config.yaml")
                        .lastModified()

                    if (last < 0 || now - last >= imported.interval) {
                        Log.i("ProfileAutoUpdate: ${imported.name} due, requesting update")

                        ProfileReceiver.schedule(service, imported)
                    }
                }
        }.onFailure {
            Log.w("ProfileAutoUpdate: check failed: ${it.message}")
        }
    }
}
