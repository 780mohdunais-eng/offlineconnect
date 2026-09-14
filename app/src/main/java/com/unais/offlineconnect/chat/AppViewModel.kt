package com.unais.offlineconnect.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.unais.offlineconnect.bluetooth.BluetoothTransport
import com.unais.offlineconnect.bluetooth.ChatMessage
import com.unais.offlineconnect.bluetooth.ConnectionState
import com.unais.offlineconnect.bluetooth.DiscoveredDevice
import com.unais.offlineconnect.protocol.MessageType
import com.unais.offlineconnect.protocol.Packet
import com.unais.offlineconnect.storage.LocalStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val transport = BluetoothTransport(app)
    private val store = LocalStore(app)

    // Stable per-install identifier used as senderId in packets (spec section 5).
    // Not tied to any hardware ID - just a random UUID generated once.
    private val deviceId: String by lazy { UUID.randomUUID().toString() }

    val discovered: StateFlow<List<DiscoveredDevice>> = transport.discovered
    val connectionState: StateFlow<ConnectionState> = transport.connectionState
    val connectedDeviceName: StateFlow<String?> = transport.connectedDeviceName
    val bluetoothEnabled: Boolean get() = transport.isBluetoothEnabled

    private val _displayName = MutableStateFlow("Unais")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var currentPeerAddress: String? = null

    init {
        transport.registerReceiver()
        transport.startServer()

        viewModelScope.launch {
            store.displayName.collect { saved ->
                if (saved != null) _displayName.value = saved
            }
        }

        viewModelScope.launch {
            transport.incomingPackets.filterNotNull().collect { packet ->
                handleIncoming(packet)
            }
        }

        viewModelScope.launch {
            connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    // Announce ourselves so the peer's UI can show a real name, not just an address.
                    transport.send(
                        Packet(senderId = deviceId, type = MessageType.DEVICE_INFO, payload = _displayName.value)
                    )
                }
            }
        }
    }

    fun setDisplayName(name: String) {
        _displayName.value = name
        viewModelScope.launch { store.setDisplayName(name) }
    }

    fun startScan() = transport.startScan()
    fun stopScan() = transport.stopScan()

    fun connect(device: DiscoveredDevice) {
        currentPeerAddress = device.address
        viewModelScope.launch {
            _messages.value = store.loadHistory(device.address)
        }
        transport.connectTo(device.address)
    }

    fun disconnect() = transport.disconnect()

    fun sendText(text: String) {
        if (text.isBlank()) return
        val packet = Packet(senderId = deviceId, type = MessageType.TEXT, payload = text)
        val delivered = transport.send(packet)
        appendMessage(
            ChatMessage(
                id = packet.packetId,
                text = text,
                timestamp = packet.timestamp,
                fromMe = true,
                delivered = delivered
            )
        )
    }

    private fun handleIncoming(packet: Packet) {
        when (packet.type) {
            MessageType.TEXT -> {
                appendMessage(
                    ChatMessage(
                        id = packet.packetId,
                        text = packet.payload,
                        timestamp = packet.timestamp,
                        fromMe = false,
                        delivered = true
                    )
                )
                transport.send(Packet(senderId = deviceId, type = MessageType.ACK, payload = packet.packetId))
            }
            MessageType.PING -> {
                transport.send(Packet(senderId = deviceId, type = MessageType.PONG))
            }
            MessageType.DEVICE_INFO, MessageType.PONG, MessageType.ACK, MessageType.DISCONNECT -> {
                // DEVICE_INFO display name is surfaced via connectedDeviceName using the
                // socket's Bluetooth name already; nothing further to do for the MVP.
            }
            MessageType.VOICE_START, MessageType.VOICE_DATA, MessageType.VOICE_END,
            MessageType.VOICE_MESSAGE, MessageType.FILE -> {
                // Not implemented yet (Phase 5/7/14) - intentionally ignored rather than
                // pretending to handle it.
            }
        }
    }

    private fun appendMessage(message: ChatMessage) {
        val updated = _messages.value + message
        _messages.value = updated
        val address = currentPeerAddress ?: return
        viewModelScope.launch { store.saveHistory(address, updated) }
    }

    override fun onCleared() {
        super.onCleared()
        transport.shutdown()
    }
}
