package com.connectmesh.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.connectmesh.diagnostics.NetworkEventLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleScanner(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val onPeerDiscovered: (peerId: Long, deviceAddress: String, rssi: Int) -> Unit
) {
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (bluetoothAdapter == null) {
            NetworkEventLogger.log("SCANNER_ERROR: BluetoothAdapter is NULL")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            NetworkEventLogger.log("SCANNER_ERROR: Bluetooth is DISABLED")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            NetworkEventLogger.log("SCANNER_ERROR: BluetoothLeScanner not supported")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { res ->
                    val serviceData = res.scanRecord?.getServiceData(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
                    var peerId: Long = 0L
                    if (serviceData != null && serviceData.size >= 8) {
                        peerId = ByteBuffer.wrap(serviceData).apply { order(ByteOrder.BIG_ENDIAN) }.long
                    }
                    if (peerId == 0L) {
                        peerId = (res.device.address.hashCode().toLong() and 0x7FFFFFFFFFFFFFFF)
                    }

                    val address = res.device.address
                    val rssi = res.rssi
                    NetworkEventLogger.log("SCAN_RESULT: Addr=$address, ID=0x${peerId.toString(16).uppercase()}, RSSI=$rssi")
                    onPeerDiscovered(peerId, address, rssi)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                NetworkEventLogger.log("SCAN_FAILED: errorCode=$errorCode")
            }
        }

        scanner?.startScan(listOf(filter), settings, scanCallback)
        NetworkEventLogger.log("SCAN_STARTED: Filter UUID=${BleConstants.MESH_SERVICE_UUID}")
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        scanCallback?.let {
            scanner?.stopScan(it)
            NetworkEventLogger.log("SCAN_STOPPED")
        }
        scanCallback = null
    }
}
