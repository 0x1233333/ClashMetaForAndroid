package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Periodically runs a group-level URLTest on every smart group while the
 * screen is on, so dead nodes are excluded from selection promptly.
 *
 * Deliberately idles while the screen is off: in Doze the tests are
 * unreliable (they would mark healthy nodes dead and trip the reload
 * watchdog) and the radio cost is real. A check runs immediately when the
 * screen turns on and after every network change.
 */
class SmartHealthModule(
    service: Service,
    private val onRequestReload: suspend () -> Unit = {}
) : Module<Unit>(service) {
    companion object {
        private const val CONTROLLER = "http://127.0.0.1:9090"
        private const val INTERVAL_MS = 3 * 60 * 1000L
        private const val SCREEN_OFF_INTERVAL_MS = 60_000L
        private const val INITIAL_DELAY_MS = 20_000L
        private const val TEST_TIMEOUT_MS = 5000
        private const val RELOAD_MIN_GAP_MS = 30 * 60 * 1000L
        private const val FAIL_STREAK_LIMIT = 3
    }

    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val power = service.getSystemService<PowerManager>()!!
    private val networkEvents = Channel<Unit>(Channel.UNLIMITED)
    private val screenEvents = receiveBroadcast(false, Channel.CONFLATED) {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkEvents.trySend(Unit)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            networkEvents.trySend(Unit)
        }
    }

    @Volatile private var failStreak = 0
    @Volatile private var lastReloadAt = 0L

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
                if (power.isInteractive) {
                    checkSmartGroups()
                    waitEvents(INTERVAL_MS)
                } else {
                    // 熄屏:Doze 下测速结果不可靠且费电,只等亮屏/网络事件
                    waitEvents(SCREEN_OFF_INTERVAL_MS)
                }
            }
        } finally {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w("SmartHealth: unregister failed", e)
            }
        }
    }

    private suspend fun waitEvents(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            select {
                networkEvents.onReceive { }
                screenEvents.onReceive { intent ->
                    if (intent.action == Intent.ACTION_SCREEN_ON) {
                        delay(3000) // 亮屏后等无线网络就绪再测
                    }
                }
            }
        }
    }

    private suspend fun checkSmartGroups() = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet("$CONTROLLER/group") ?: return@runCatching
            val proxies = JSONObject(body).optJSONArray("proxies") ?: return@runCatching
            val smartGroups = (0 until proxies.length())
                .map { proxies.getJSONObject(it) }
                .filter { it.optString("type").equals("Smart", ignoreCase = true) }
                .map { it.optString("name") to it }
            // 成员数多的组先测(如"总的"组);成员已被覆盖的组跳过,避免同一节点被反复测速
            val smartNames = smartGroups.sortedByDescending { it.second.optJSONArray("all")?.length() ?: 0 }
                .map { it.first }

            val testedMembers = mutableSetOf<String>()
            var skipped = 0
            var failures = 0
            for (name in smartNames) {
                val members = smartGroups.firstOrNull { it.first == name }
                    ?.second?.optJSONArray("all")
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    ?: emptyList()

                if (members.isNotEmpty() && members.all { it in testedMembers }) {
                    skipped++

                    Log.d("SmartHealth: skip $name (members already tested)")

                    continue
                }

                try {
                    // 必须带超时:urlTestGroup 是打给内核的异步调用,内核卡住时 await() 永不返回,
                    // 这个守护协程就此挂死(旧代码声明了 TEST_TIMEOUT_MS 却从未使用)。
                    val finished = withTimeoutOrNull(TEST_TIMEOUT_MS.toLong()) {
                        Clash.urlTestGroup(name).await()

                        true
                    }

                    if (finished == null) {
                        failures++

                        Log.w("SmartHealth: urltest $name timed out (${TEST_TIMEOUT_MS}ms)")
                    } else {
                        Log.d("SmartHealth: urltest $name ok")
                    }
                } catch (e: Exception) {
                    failures++

                    Log.w("SmartHealth: urltest $name failed: ${e.message}")
                }

                testedMembers.addAll(members)
            }

            if (smartNames.isNotEmpty()) {
                Log.i("SmartHealth: tested ${smartNames.size - skipped} smart group(s) " +
                      "($skipped covered by larger groups), failures=$failures")
            }

            if (smartNames.isNotEmpty() && failures == smartNames.size) {
                failStreak++

                Log.w("SmartHealth: all groups failed ($failStreak streak)")

                if (failStreak >= FAIL_STREAK_LIMIT && power.isInteractive &&
                    System.currentTimeMillis() - lastReloadAt >= RELOAD_MIN_GAP_MS) {
                    failStreak = 0
                    lastReloadAt = System.currentTimeMillis()

                    Log.w("SmartHealth: total failure x$FAIL_STREAK_LIMIT while screen on, requesting profile reload")

                    onRequestReload()
                }
            } else if (failures < smartNames.size) {
                failStreak = 0
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
