package com.connectmesh.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.connectmesh.diagnostics.NetworkEventLogger
import com.connectmesh.service.MeshForegroundService

class MainActivity : ComponentActivity() {

    private var meshService: MeshForegroundService? = null
    private var isBound = false
    private var showBluetoothPrompt by mutableStateOf(false)

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (btAdapter?.isEnabled == true) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: Bluetooth ENABLED by user via system dialog")
            showBluetoothPrompt = false
            if (hasAllPermissions()) {
                meshService?.startBleStack()
            }
        } else {
            NetworkEventLogger.log("CONNECT_MESH_BLE: Bluetooth enable request DENIED or cancelled")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: All permissions GRANTED by user")
            startAndBindService()
            checkBluetoothStatus()
            if (!showBluetoothPrompt) {
                meshService?.startBleStack()
            }
        } else {
            val denied = permissions.filter { !it.value }.keys.joinToString(", ")
            NetworkEventLogger.log("CONNECT_MESH_BLE: Permissions DENIED: $denied")
            startAndBindService() // Still start service to allow user to see UI state
            checkBluetoothStatus()
            if (!showBluetoothPrompt) {
                meshService?.startBleStack()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshForegroundService.LocalBinder
            meshService = binder.getService()
            isBound = true
            NetworkEventLogger.log("CONNECT_MESH_BLE: Service BOUND to MainActivity")
            checkBluetoothStatus()
            if (!showBluetoothPrompt) {
                meshService?.startBleStack()
            }
            updateUiContent()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isBound = false
            NetworkEventLogger.log("CONNECT_MESH_BLE: Service UNBOUND")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (hasAllPermissions()) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: Permissions already GRANTED")
            startAndBindService()
            checkBluetoothStatus()
        } else {
            requestPermissions()
        }

        updateUiContent()
    }

    override fun onResume() {
        super.onResume()
        checkBluetoothStatus()
        if (hasAllPermissions() && !showBluetoothPrompt) {
            meshService?.startBleStack()
        }
    }

    private fun checkBluetoothStatus() {
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        showBluetoothPrompt = btAdapter != null && !btAdapter.isEnabled
    }

    private fun requestEnableBluetooth() {
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(intent)
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: Unable to launch ACTION_REQUEST_ENABLE: ${e.message}")
        }
    }

    private fun updateUiContent() {
        setContent {
            ConnectMeshApp(
                service = meshService,
                showBluetoothPrompt = showBluetoothPrompt,
                onRequestEnableBluetooth = { requestEnableBluetooth() },
                onDismissBluetoothPrompt = { showBluetoothPrompt = false }
            )
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, MeshForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        if (!isBound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun hasAllPermissions(): Boolean {
        val required = getRequiredPermissions()
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val required = getRequiredPermissions()
        NetworkEventLogger.log("CONNECT_MESH_BLE: Requesting runtime permissions: ${required.joinToString(", ")}")
        permissionLauncher.launch(required.toTypedArray())
    }

    private fun getRequiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        list.add(Manifest.permission.RECORD_AUDIO) // Audio Recording permission for Voice Notes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
