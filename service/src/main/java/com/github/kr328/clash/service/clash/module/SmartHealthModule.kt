package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Periodically runs a group-level URLTest on every smart group.
 *
 * The smart kernel filters dead nodes by their alive flag, which is only
 * updated by delay tests. Without this module the flags go stale after a
 * network switch and traffic keeps going through invalid nodes until the
 * user runs a manual latency test. This module automates exactly that:
 * an immediate test on every network change plus a periodic fallback.
 */
class SmartHealthModule(service: Service) : Module<Unit>(service) {
    companion object {
        private const val CONTROLLER = "http://127.0.0.1:9090"
        private const val INTERVAL_MS = 3 * 60 * 1000L
        private const val INITIAL_DELAY_MS = 20_000L
        private const val TEST_TIMEOUT_MS = 5000
    }

    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val networkEvents = Channel<Unit>(Channel.UNLIMITED)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkEvents.trySend(Unit)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            networkEvents.trySend(Unit)
        }
    }

    init {
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().apply {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                }.build(),
                callback
            )
        } catch (e: Exception) {
            Log.w("SmartHealth: register network callback failed", e)
        }
    }

    override suspend fun run() {
        delay(INITIAL_DELAY_MS)

        try {
            while (true) {
                checkSmartGroups()

                // re-check on network change (alive flags go stale exactly then),
                // otherwise at a fixed interval
                withTimeoutOrNull(INTERVAL_MS) { networkEvents.receive() }
                delay(2000) // let the new network settle before testing
            }
        } finally {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w("SmartHealth: unregister failed", e)
            }
        }
    }

    private suspend fun checkSmartGroups() = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet("$CONTROLLER/group") ?: return@runCatching
            val proxies = JSONObject(body).optJSONArray("proxies") ?: return@runCatching
            val smartNames = (0 until proxies.length())
                .map { proxies.getJSONObject(it) }
                .filter { it.optString("type").equals("Smart", ignoreCase = true) }
                .map { it.optString("name") }

            for (name in smartNames) {
                Clash.urlTestGroup(name)

                Log.d("SmartHealth: urltest dispatched $name")
            }

            if (smartNames.isNotEmpty()) {
                Log.i("SmartHealth: tested ${smartNames.size} smart group(s)")
            }
        }.onFailure {
            Log.w("SmartHealth: check failed: ${it.message}")
        }
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

}
