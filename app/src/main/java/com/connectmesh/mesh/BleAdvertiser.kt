package com.connectmesh.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import com.connectmesh.diagnostics.NetworkEventLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleAdvertiser(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val deviceId: Long
) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (bluetoothAdapter == null) {
            NetworkEventLogger.log("ADVERTISER_ERROR: BluetoothAdapter is NULL")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            NetworkEventLogger.log("ADVERTISER_ERROR: Bluetooth is DISABLED")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            NetworkEventLogger.log("ADVERTISER_ERROR: BluetoothLeAdvertiser not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // 1. Primary Advertising Data (Keep <= 31 bytes)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .build()

        // 2. Scan Response Data for 8-byte Device ID
        val serviceData = ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putLong(deviceId)
        }.array()

        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BleConstants.MESH_SERVICE_UUID), serviceData)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                NetworkEventLogger.log("ADVERTISE_SUCCESS: ID=0x${deviceId.toString(16).uppercase()}")
            }

            override fun onStartFailure(errorCode: Int) {
                val errorMsg = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE (1)"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS (2)"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED (3)"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR (4)"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED (5)"
                    else -> "CODE_$errorCode"
                }
                NetworkEventLogger.log("ADVERTISE_FAILED: $errorMsg")
            }
        }

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        NetworkEventLogger.log("ADVERTISE_STARTED: UUID=${BleConstants.MESH_SERVICE_UUID}")
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiseCallback?.let {
            advertiser?.stopAdvertising(it)
            NetworkEventLogger.log("ADVERTISE_STOPPED")
        }
        advertiseCallback = null
    }
}
