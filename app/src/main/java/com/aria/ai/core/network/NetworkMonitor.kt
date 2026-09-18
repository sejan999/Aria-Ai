package com.aria.ai.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live connectivity state for the cloud adapters.
 *
 * Aria degrades gracefully: when the device drops the network the Home screen
 * shows "Offline" and the registry refuses cloud calls instead of letting requests
 * hang until timeout. Uses only the platform [ConnectivityManager].
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val manager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(isCurrentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var metered = true

    /** Registers the system network callback (idempotent). */
    fun start() {
        val cm = manager ?: return
        if (callback != null) return

        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh()
            }

            override fun onLost(network: Network) {
                refresh()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                metered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                _online.value = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }

        runCatching { cm.registerDefaultNetworkCallback(registered) }
            .onSuccess { callback = registered }
            .onFailure { _online.value = isCurrentlyOnline() }
    }

    fun stop() {
        val cm = manager ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    fun isCurrentlyOnline(): Boolean {
        val cm = manager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isUnmetered(): Boolean {
        isCurrentlyOnline()
        return !metered
    }

    fun refresh() {
        _online.value = isCurrentlyOnline()
    }

    /** Human-readable status for the HUD. */
    fun describe(): String = when {
        !isCurrentlyOnline() -> "Offline"
        metered -> "Online"
        else -> "Online · unmetered"
    }
}