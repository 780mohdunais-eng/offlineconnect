package com.unais.offlineconnect.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.unais.offlineconnect.protocol.DuplicateCache
import com.unais.offlineconnect.protocol.MessageType
import com.unais.offlineconnect.protocol.Packet
import com.unais.offlineconnect.protocol.PacketCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID

/**
 * Owns the actual android.bluetooth.* objects and RFCOMM socket lifecycle.
 * One app-wide instance; UI observes the exposed StateFlows.
 *
 * App identity (a fixed UUID) is what both the server and client use to open the
 * RFCOMM channel - this is the "unique local device/app identifier" from spec
 * section 5. It does not leak anything beyond "an OfflineConnect app is listening".
 */
class BluetoothTransport(private val context: Context) {

    companion object {
        // Fixed app-specific service UUID (spec 5/9). Randomly generated once for this
        // project; every install of this app uses the same UUID to find every other install.
        val APP_UUID: UUID = UUID.fromString("8f3a2b10-6c2e-4b8a-9c3d-1a2b3c4d5e6f")
        const val SERVICE_NAME = "OfflineConnectRFCOMM"
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _incomingPackets = MutableStateFlow<Packet?>(null)
    val incomingPackets: StateFlow<Packet?> = _incomingPackets.asStateFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var lastConnectedAddress: String? = null

    private val dupeCache = DuplicateCache()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private var autoReconnectJob: Job? = null

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    // ---- Discovery (spec section 5, 4) ----

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?: return
                    val name = device.name ?: "Unknown Device"
                    val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                    val current = _discovered.value.toMutableList()
                    if (current.none { it.address == device.address }) {
                        current.add(DiscoveredDevice(name, device.address, bonded))
                        _discovered.value = current
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_connectionState.value == ConnectionState.SCANNING) {
                        _connectionState.value = ConnectionState.IDLE
                    }
                }
            }
        }
    }

    private var receiverRegistered = false

    fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(discoveryReceiver, filter)
        receiverRegistered = true
    }

    fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: IllegalArgumentException) {
            // already unregistered - not a crash-worthy condition
        }
        receiverRegistered = false
    }

    /** Requires BLUETOOTH_SCAN (API 31+) or BLUETOOTH_ADMIN (legacy) already granted. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        val bt = adapter ?: return
        _discovered.value = bondedDevices()
        if (bt.isDiscovering) bt.cancelDiscovery()
        _connectionState.value = ConnectionState.SCANNING
        bt.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        adapter?.let { if (it.isDiscovering) it.cancelDiscovery() }
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.IDLE
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<DiscoveredDevice> {
        val bt = adapter ?: return emptyList()
        return bt.bondedDevices.map { DiscoveredDevice(it.name ?: it.address, it.address, true) }
    }

    // ---- Server (listens for incoming connections) ----

    @SuppressLint("MissingPermission")
    fun startServer() {
        scope.launch {
            try {
                val bt = adapter ?: return@launch
                val server = bt.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                serverSocket = server
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        break // server socket closed
                    }
                    onSocketConnected(socket)
                }
            } catch (e: IOException) {
                _connectionState.value = ConnectionState.FAILED
            }
        }
    }

    fun stopServer() {
        try {
            serverSocket?.close()
        } catch (e: IOException) { /* ignore */ }
        serverSocket = null
    }

    // ---- Client (initiates connection to a chosen device) ----

    @SuppressLint("MissingPermission")
    fun connectTo(address: String) {
        stopScan()
        lastConnectedAddress = address
        _connectionState.value = ConnectionState.CONNECTING
        scope.launch {
            val bt = adapter ?: run {
                _connectionState.value = ConnectionState.FAILED
                return@launch
            }
            val device = try {
                bt.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                _connectionState.value = ConnectionState.FAILED
                return@launch
            }
            var socket: BluetoothSocket? = null
            try {
                socket = device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
                bt.cancelDiscovery()
                socket.connect()
                onSocketConnected(socket)
            } catch (e: IOException) {
                try { socket?.close() } catch (ignored: IOException) {}
                _connectionState.value = ConnectionState.FAILED
                scheduleReconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onSocketConnected(socket: BluetoothSocket) {
        // If we're already connected to someone, don't silently drop them for a new comer.
        if (activeSocket != null) {
            try { socket.close() } catch (ignored: IOException) {}
            return
        }
        activeSocket = socket
        lastConnectedAddress = socket.remoteDevice.address
        _connectedDeviceName.value = socket.remoteDevice.name ?: socket.remoteDevice.address
        _connectionState.value = ConnectionState.CONNECTED
        autoReconnectJob?.cancel()

        readJob = scope.launch {
            val input = try { socket.inputStream } catch (e: IOException) { null }
            if (input == null) { handleDisconnect(); return@launch }
            while (isActive) {
                val packet = try {
                    PacketCodec.readOne(input)
                } catch (e: IOException) {
                    null
                }
                if (packet == null) {
                    handleDisconnect()
                    break
                }
                if (!dupeCache.isDuplicate(packet.packetId)) {
                    _incomingPackets.value = packet
                }
            }
        }
    }

    private fun handleDisconnect() {
        try { activeSocket?.close() } catch (ignored: IOException) {}
        activeSocket = null
        _connectedDeviceName.value = null
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        scheduleReconnect()
    }

    /** Spec section 19: controlled-interval retry, not an aggressive infinite loop. */
    private fun scheduleReconnect() {
        val address = lastConnectedAddress ?: return
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            _connectionState.value = ConnectionState.RECONNECTING
            val delays = listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
            for (d in delays) {
                delay(d)
                if (activeSocket != null) return@launch // reconnected via server accept meanwhile
                connectTo(address)
                delay(3_000L) // give connectTo a moment to succeed/fail before next attempt
                if (activeSocket != null) return@launch
            }
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    fun cancelAutoReconnect() {
        autoReconnectJob?.cancel()
        lastConnectedAddress = null
    }

    // ---- Sending ----

    fun send(packet: Packet): Boolean {
        val socket = activeSocket ?: return false
        return try {
            PacketCodec.write(socket.outputStream, packet)
            true
        } catch (e: IOException) {
            handleDisconnect()
            false
        }
    }

    fun disconnect() {
        cancelAutoReconnect()
        readJob?.cancel()
        try { activeSocket?.close() } catch (ignored: IOException) {}
        activeSocket = null
        _connectedDeviceName.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun shutdown() {
        disconnect()
        stopServer()
        unregisterReceiver()
        scope.cancel()
    }
}
