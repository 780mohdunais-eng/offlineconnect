package com.unais.offlineconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.unais.offlineconnect.bluetooth.ChatMessage
import com.unais.offlineconnect.bluetooth.ConnectionState
import com.unais.offlineconnect.bluetooth.DiscoveredDevice
import com.unais.offlineconnect.chat.AppViewModel
import java.text.SimpleDateFormat
import java.util.*

private enum class Screen { DEVICES, CHAT }

@Composable
fun OfflineConnectApp(viewModel: AppViewModel) {
    var screen by remember { mutableStateOf(Screen.DEVICES) }
    val connectionState by viewModel.connectionState.collectAsState()

    // Auto-jump to chat once a connection is live; drop back to the device list on disconnect.
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) screen = Screen.CHAT
    }

    when (screen) {
        Screen.DEVICES -> DevicesScreen(
            viewModel = viewModel,
            onOpenChat = { screen = Screen.CHAT }
        )
        Screen.CHAT -> ChatScreen(
            viewModel = viewModel,
            onBack = { screen = Screen.DEVICES }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesScreen(viewModel: AppViewModel, onOpenChat: () -> Unit) {
    val devices by viewModel.discovered.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val displayName by viewModel.displayName.collectAsState()
    var showNameEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OFFLINE CONNECT") },
                actions = {
                    TextButton(onClick = { showNameEditor = true }) { Text(displayName) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            Text(
                "Range depends on your phone's Bluetooth hardware and environment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { viewModel.startScan() },
                enabled = connectionState != ConnectionState.SCANNING,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (connectionState == ConnectionState.SCANNING) "Scanning…" else "Scan Nearby Devices")
            }

            Spacer(Modifier.height(16.dp))
            Text("Nearby Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (devices.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "No devices found yet. Make sure the other phone has OfflineConnect " +
                        "open and Bluetooth on, then scan.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(devices) { device ->
                    DeviceRow(
                        device = device,
                        connecting = connectionState == ConnectionState.CONNECTING,
                        onConnect = { viewModel.connect(device) }
                    )
                }
            }
        }
    }

    if (showNameEditor) {
        NameEditorDialog(
            current = displayName,
            onDismiss = { showNameEditor = false },
            onSave = { viewModel.setDisplayName(it); showNameEditor = false }
        )
    }
}

@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    isThisConnecting: Boolean,
    anyConnecting: Boolean,
    onConnect: () -> Unit
) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(if (device.bonded) "Paired • ${device.address}" else device.address) },
        trailingContent = {
            Button(onClick = onConnect, enabled = !anyConnecting) {
                Text(if (isThisConnecting) "Connecting…" else "Connect")
            }
        }
    )
    Divider()
}

@Composable
private fun NameEditorDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display name") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val peerName by viewModel.connectedDeviceName.collectAsState()
    var draft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(peerName ?: "Device")
                        Text(connectionStatusLabel(connectionState), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onBack()
                    }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    enabled = connectionState == ConnectionState.CONNECTED
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (draft.isNotBlank()) {
                            viewModel.sendText(draft)
                            draft = ""
                        }
                    },
                    enabled = connectionState == ConnectionState.CONNECTED
                ) { Icon(Icons.Default.Send, contentDescription = "Send") }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp),
            reverseLayout = false
        ) {
            items(messages) { msg -> MessageBubble(msg) }
        }
    }
}

private fun connectionStatusLabel(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTED -> "🟢 Connected"
    ConnectionState.CONNECTING -> "Connecting…"
    ConnectionState.RECONNECTING -> "🟠 Connection lost — retrying…"
    ConnectionState.DISCONNECTED -> "🔴 Disconnected"
    ConnectionState.FAILED -> "🔴 Connection failed"
    ConnectionState.SCANNING -> "Scanning…"
    ConnectionState.IDLE -> "Not connected"
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (message.fromMe) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(message.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    timeFmt.format(Date(message.timestamp)) + if (message.fromMe && !message.delivered) " • not sent" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
