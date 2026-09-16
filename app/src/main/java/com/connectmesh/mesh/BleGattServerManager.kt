package com.connectmesh.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.connectmesh.diagnostics.NetworkEventLogger
import java.util.concurrent.ConcurrentHashMap

class BleGattServerManager(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val onPacketReceived: (device: BluetoothDevice, value: ByteArray) -> Unit
) {
    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    @SuppressLint("MissingPermission")
    fun startGattServer(): Boolean {
        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                super.onConnectionStateChange(device, status, newState)
                if (device != null) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connectedDevices[device.address] = device
                        NetworkEventLogger.log("CONNECT_MESH_BLE: SERVER_DEVICE_CONNECTED: ${device.address}")
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connectedDevices.remove(device.address)
                        NetworkEventLogger.log("CONNECT_MESH_BLE: SERVER_DEVICE_DISCONNECTED: ${device.address}")
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
                if (responseNeeded && device != null && gattServer != null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                if (characteristic?.uuid == BleConstants.RX_CHARACTERISTIC_UUID && value != null && device != null) {
                    NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_SERVER_WRITE_RECEIVED: From=${device.address}, Bytes=${value.size}")
                    onPacketReceived(device, value)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                descriptor: BluetoothGattDescriptor?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
                if (responseNeeded && device != null && gattServer != null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                if (descriptor?.uuid == BleConstants.CCCD_UUID && device != null) {
                    NetworkEventLogger.log("CONNECT_MESH_BLE: SERVER_CCCD_WRITTEN: From=${device.address}")
                }
            }
        }

        gattServer = bluetoothManager.openGattServer(context, callback)
        if (gattServer == null) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: ERROR - openGattServer returned NULL")
            return false
        }

        val service = BluetoothGattService(BleConstants.MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val rxChar = BluetoothGattCharacteristic(
            BleConstants.RX_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val txChar = BluetoothGattCharacteristic(
            BleConstants.TX_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txChar.addDescriptor(cccd)

        val controlChar = BluetoothGattCharacteristic(
            BleConstants.CONTROL_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)
        service.addCharacteristic(controlChar)

        val added = gattServer?.addService(service) ?: false
        NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_SERVER_SERVICE_ADDED = $added")
        return added
    }

    @SuppressLint("MissingPermission")
    fun notifyAllConnectedDevices(value: ByteArray) {
        val server = gattServer ?: return
        val service = server.getService(BleConstants.MESH_SERVICE_UUID) ?: return
        val txChar = service.getCharacteristic(BleConstants.TX_CHARACTERISTIC_UUID) ?: return

        connectedDevices.values.forEach { device ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, txChar, false, value)
            } else {
                txChar.value = value
                server.notifyCharacteristicChanged(device, txChar, false)
            }
            NetworkEventLogger.log("CONNECT_MESH_BLE: SERVER_NOTIFY_SENT: To=${device.address}, Bytes=${value.size}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopGattServer() {
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
    }
}
