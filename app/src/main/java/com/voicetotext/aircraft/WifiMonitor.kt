package com.voicetotext.aircraft

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Monitors WiFi connectivity and fires callbacks when the device connects to
 * or disconnects from the configured target SSID.
 */
class WifiMonitor(private val context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    interface Listener {
        fun onTargetNetworkConnected(ssid: String)
        fun onTargetNetworkDisconnected()
    }

    fun start(targetSsid: String, listener: Listener) {
        stop()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        callback = buildCallback(targetSsid, listener)
        cm.registerNetworkCallback(request, callback!!)

        // Immediately check current state
        if (getCurrentSsid() == targetSsid) listener.onTargetNetworkConnected(targetSsid)
    }

    fun stop() {
        callback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
            callback = null
        }
    }

    fun getCurrentSsid(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
            (caps.transportInfo as? WifiInfo)?.ssid?.stripQuotes()
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext
                .getSystemService(WifiManager::class.java)
                .connectionInfo?.ssid?.stripQuotes()
        }
    }

    private fun buildCallback(
        targetSsid: String,
        listener: Listener
    ): ConnectivityManager.NetworkCallback {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) {
                    val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.stripQuotes() ?: return
                    if (ssid == targetSsid) listener.onTargetNetworkConnected(ssid)
                }
                override fun onLost(net: Network) = listener.onTargetNetworkDisconnected()
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) {
                    val ssid = getCurrentSsid() ?: return
                    if (ssid == targetSsid) listener.onTargetNetworkConnected(ssid)
                }
                override fun onLost(net: Network) = listener.onTargetNetworkDisconnected()
            }
        }
    }

    private fun String.stripQuotes() = removeSurrounding("\"").takeIf { it.isNotBlank() }
}
