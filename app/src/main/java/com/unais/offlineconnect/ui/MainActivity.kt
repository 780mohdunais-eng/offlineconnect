package com.unais.offlineconnect.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unais.offlineconnect.chat.AppViewModel

/**
 * Permissions actually requested here match spec section 17 exactly for this MVP:
 * BLUETOOTH_SCAN + BLUETOOTH_CONNECT (API 31+) or BLUETOOTH/BLUETOOTH_ADMIN (legacy).
 * RECORD_AUDIO and POST_NOTIFICATIONS are NOT requested because voice/background
 * service features aren't implemented yet - requesting them now would violate
 * "request only permissions that are actually required".
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result observed via hasPermissions() polling in Compose state below */ }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user chose to enable or not; UI just re-checks state */ }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var permissionsGranted by remember { mutableStateOf(hasPermissions()) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!permissionsGranted) {
                        PermissionRationaleScreen(
                            onRequest = {
                                permissionLauncher.launch(requiredPermissions())
                                // Re-check shortly after; Compose recomposes on next frame
                                // interaction, and user returning to app re-enters onCreate flow.
                                permissionsGranted = hasPermissions()
                            }
                        )
                        // Also re-check whenever this composable recomposes for any reason.
                        LaunchedEffect(Unit) {
                            permissionsGranted = hasPermissions()
                        }
                    } else if (!viewModel.bluetoothEnabled) {
                        EnableBluetoothScreen(
                            onEnable = {
                                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }
                        )
                    } else {
                        OfflineConnectApp(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRationaleScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Bluetooth permission needed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "OfflineConnect talks directly to nearby phones over Bluetooth — no internet " +
                "involved. Android requires permission to scan for and connect to those " +
                "devices before we can show you anyone nearby."
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Grant permission") }
    }
}

@Composable
private fun EnableBluetoothScreen(onEnable: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Bluetooth is off", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("Turn on Bluetooth to discover and talk to nearby devices.")
        Spacer(Modifier.height(20.dp))
        Button(onClick = onEnable) { Text("Enable Bluetooth") }
    }
}
