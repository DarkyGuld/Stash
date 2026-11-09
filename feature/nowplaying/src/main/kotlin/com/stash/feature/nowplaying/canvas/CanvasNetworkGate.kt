package com.stash.feature.nowplaying.canvas

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.stash.core.common.MeteredNetwork
import com.stash.core.common.isActiveNetworkMetered
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Canvas may use the active network. Canvas videos are an optional decoration, so they
 * never spend mobile data: only a metered-free network (Wi-Fi, Ethernet or a hotspot the user did
 * not mark metered) opens this gate. It follows the default network, so walking off Wi-Fi stops the
 * video at once rather than at the next song.
 */
interface CanvasNetworkGate {

    /** The active network's meteredness, now and on every change. True means Canvas may run. */
    val unmetered: Flow<Boolean>

    /** The same answer for a decision made in this instant (a ViewModel's initial value). */
    fun isUnmeteredNow(): Boolean

    /** Always unmetered: the default for tests and previews. */
    object Unrestricted : CanvasNetworkGate {
        override val unmetered: Flow<Boolean> = flowOf(true)
        override fun isUnmeteredNow(): Boolean = true
    }
}

/** The system's default network, watched through [ConnectivityManager] for as long as it is collected. */
@Singleton
class SystemCanvasNetworkGate @Inject constructor(
    @ApplicationContext private val context: Context,
) : CanvasNetworkGate {

    private val connectivity: ConnectivityManager?
        get() = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isUnmeteredNow(): Boolean = !isActiveNetworkMetered(context)

    override val unmetered: Flow<Boolean> = callbackFlow {
        val cm = connectivity
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(!MeteredNetwork.isMetered(caps))
            }
        }
        val registered = cm != null && runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        // A view model may start while no callback has fired yet: answer for the network in hand.
        trySend(isUnmeteredNow())
        awaitClose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
