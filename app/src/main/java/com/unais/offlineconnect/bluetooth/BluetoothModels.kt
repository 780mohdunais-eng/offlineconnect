package com.unais.offlineconnect.bluetooth

import kotlinx.serialization.Serializable

/**
 * Connection quality is derived only from actual connection state, never invented
 * RSSI numbers (spec section 18). Classic Bluetooth RFCOMM sockets on Android do not
 * expose live signal strength, so we intentionally only show state, not a fake bar.
 */
enum class ConnectionQuality {
    CONNECTED,
    WEAK_RECENT_TIMEOUT,
    DISCONNECTED
}

enum class ConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    FAILED
}

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val bonded: Boolean
)

@Serializable
data class ChatMessage(
    val id: String,
    val text: String,
    val timestamp: Long,
    val fromMe: Boolean,
    val delivered: Boolean
)
