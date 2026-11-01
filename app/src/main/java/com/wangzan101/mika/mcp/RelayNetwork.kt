package com.wangzan101.mika.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** 只在远程访问开启期间观察默认网络,不创建 VPN、不绑定或改变其他模块的网络。 */
internal class RelayNetwork(context: Context, private val changed: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized fun start() {
        if (callback != null) return
        val observer = object : ConnectivityManager.NetworkCallback() {
            private var initialized = false
            private var current: Network? = null
            override fun onAvailable(network: Network) {
                val different = initialized && current != network
                initialized = true; current = network
                if (different) changed()
            }
            override fun onLost(network: Network) { if (current == network) { current = null; changed() } }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(observer); callback = observer }
    }

    @Synchronized fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }
}
