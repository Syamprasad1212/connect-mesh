package com.connectmesh.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.connectmesh.diagnostics.NetworkEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class BleConnectionManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val scope: CoroutineScope,
    private val relayManager: RelayManager,
    private val onPacketReceived: (peerId: Long, bytes: ByteArray) -> Unit,
    private val onPeerStateChanged: (peerId: Long, state: BleConnectionState) -> Unit
) {
    data class ConnectionInfo(
        val deviceAddress: String,
        val peerId: Long,
        var gatt: BluetoothGatt? = null,
        var state: BleConnectionState = BleConnectionState.DISCONNECTED,
        var mtu: Int = BleConstants.DEFAULT_MTU,
        var backoffMs: Long = 1000L,
        var lastDisconnectMs: Long = 0L,
        val queue: BleOperationQueue
    )

    private val connections = ConcurrentHashMap<Long, ConnectionInfo>()
    private val addressToPeerId = ConcurrentHashMap<String, Long>()

    @SuppressLint("MissingPermission")
    fun connectToPeer(peerId: Long, deviceAddress: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        val existing = connections[peerId]
        val now = System.currentTimeMillis()

        // 1. ABSOLUTE RULE: Single connection check per peer
        if (existing != null && existing.state != BleConnectionState.DISCONNECTED) {
            // Already connecting or connected. Do NOT call connectGatt again!
            return
        }

        // 2. Exponential backoff cooldown check
        if (existing != null) {
            val elapsedSinceDisconnect = now - existing.lastDisconnectMs
            if (elapsedSinceDisconnect < existing.backoffMs) {
                // Backoff active, skip this scan result trigger
                return
            }
        }

        val device = bluetoothAdapter.getRemoteDevice(deviceAddress) ?: return
        val currentBackoff = existing?.backoffMs ?: 1000L
        NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_CONNECTING: Addr=$deviceAddress, ID=0x${peerId.toString(16).uppercase()}")

        val info = ConnectionInfo(
            deviceAddress = deviceAddress,
            peerId = peerId,
            state = BleConnectionState.CONNECTING,
            backoffMs = currentBackoff,
            queue = BleOperationQueue(scope)
        )
        connections[peerId] = info
        addressToPeerId[deviceAddress] = peerId

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_STATE_ERROR: Addr=$deviceAddress, status=$status, newState=$newState")
                }

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    info.state = BleConnectionState.CONNECTED
                    NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_CONNECTED: Addr=$deviceAddress")
                    onPeerStateChanged(peerId, info.state)
                    
                    info.state = BleConnectionState.DISCOVERING_SERVICES
                    NetworkEventLogger.log("CONNECT_MESH_BLE: DISCOVERING_SERVICES: Addr=$deviceAddress")
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    info.state = BleConnectionState.DISCONNECTED
                    info.lastDisconnectMs = System.currentTimeMillis()
                    // Exponential backoff escalation (1s -> 2s -> 4s -> max 8s)
                    info.backoffMs = minOf(info.backoffMs * 2, 8000L)
                    NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_DISCONNECTED_CLEANUP: Addr=$deviceAddress, status=$status, Backoff=${info.backoffMs}ms")
                    
                    onPeerStateChanged(peerId, info.state)
                    info.queue.clear()
                    
                    // Close and release GATT client handle to prevent leaks!
                    try {
                        gatt?.disconnect()
                        gatt?.close()
                    } catch (e: Exception) {}
                    info.gatt = null
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                    val service = gatt.getService(BleConstants.MESH_SERVICE_UUID)
                    if (service != null) {
                        NetworkEventLogger.log("CONNECT_MESH_BLE: SERVICES_DISCOVERED: Service Found for 0x${peerId.toString(16).uppercase()}")
                        info.state = BleConnectionState.MTU_NEGOTIATING
                        gatt.requestMtu(512)
                    } else {
                        NetworkEventLogger.log("CONNECT_MESH_BLE: SERVICES_DISCOVERED_ERROR: Service NOT found on 0x${peerId.toString(16).uppercase()}")
                        disconnectAndClean(info)
                    }
                } else {
                    NetworkEventLogger.log("CONNECT_MESH_BLE: DISCOVER_SERVICES_FAILED: status=$status")
                    disconnectAndClean(info)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    info.mtu = mtu
                    NetworkEventLogger.log("CONNECT_MESH_BLE: MTU_NEGOTIATED: Negotiated ATT MTU=$mtu for 0x${peerId.toString(16).uppercase()}")
                } else {
                    NetworkEventLogger.log("CONNECT_MESH_BLE: MTU_NEGOTIATION_FAILED: status=$status")
                }
                enableNotifications(gatt, info)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    info.state = BleConnectionState.READY
                    info.backoffMs = 1000L // Reset backoff on stable READY connection!
                    NetworkEventLogger.log("CONNECT_MESH_BLE: CCCD_CONFIGURED: GATT_READY for Peer 0x${info.peerId.toString(16).uppercase()}")
                    onPeerStateChanged(info.peerId, info.state)
                } else {
                    NetworkEventLogger.log("CONNECT_MESH_BLE: CCCD_WRITE_FAILED: status=$status")
                    disconnectAndClean(info)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_WRITE_SUCCESS: Char=${characteristic?.uuid}")
                } else {
                    NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_WRITE_FAILED: status=$status")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == BleConstants.TX_CHARACTERISTIC_UUID) {
                    relayManager.packetsReceived.incrementAndGet()
                    NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_NOTIFY_RECEIVED: Bytes=${value.size}")
                    onPacketReceived(peerId, value)
                }
            }
        }

        // Store GATT instance immediately so it can be cleanly closed on disconnect
        val gattInstance = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        info.gatt = gattInstance
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAndClean(info: ConnectionInfo) {
        try {
            info.gatt?.disconnect()
            info.gatt?.close()
        } catch (e: Exception) {}
        info.gatt = null
        info.state = BleConnectionState.DISCONNECTED
        onPeerStateChanged(info.peerId, info.state)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt?, info: ConnectionInfo) {
        val service = gatt?.getService(BleConstants.MESH_SERVICE_UUID) ?: return
        val txChar = service.getCharacteristic(BleConstants.TX_CHARACTERISTIC_UUID) ?: return
        gatt.setCharacteristicNotification(txChar, true)

        val descriptor = txChar.getDescriptor(BleConstants.CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            NetworkEventLogger.log("CONNECT_MESH_BLE: CCCD_ENABLE_START: Writing CCCD descriptor...")
        } else {
            info.state = BleConnectionState.READY
            info.backoffMs = 1000L
            NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_READY (No CCCD): Peer 0x${info.peerId.toString(16).uppercase()}")
            onPeerStateChanged(info.peerId, info.state)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendPacket(
        peerId: Long,
        data: ByteArray,
        transferId: Long? = null,
        priority: BleOperationQueue.Priority = BleOperationQueue.Priority.HIGH,
        isCancel: Boolean = false
    ): Boolean {
        val info = connections[peerId]
        if (info == null || info.state != BleConnectionState.READY) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: SEND_PACKET_QUEUED_NOT_READY: Peer 0x${peerId.toString(16).uppercase()}")
            return false
        }

        val gatt = info.gatt ?: return false
        val service = gatt.getService(BleConstants.MESH_SERVICE_UUID) ?: return false
        val rxChar = service.getCharacteristic(BleConstants.RX_CHARACTERISTIC_UUID) ?: return false

        info.queue.enqueue(data, transferId, priority, isCancel) { bytesToWrite, onComplete ->
            scope.launch {
                var written = false
                try {
                    for (attempt in 1..5) {
                        written = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val statusCode = gatt.writeCharacteristic(
                                    rxChar,
                                    bytesToWrite,
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                )
                                statusCode == BluetoothStatusCodes.SUCCESS
                            } else {
                                rxChar.value = bytesToWrite
                                rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                gatt.writeCharacteristic(rxChar)
                            }
                        } catch (e: Exception) {
                            NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_WRITE_EXCEPTION: ${e.message}")
                            false
                        }
                        if (written) {
                            relayManager.packetsSent.incrementAndGet()
                            NetworkEventLogger.log("CONNECT_MESH_BLE: BLE_WRITE_SUCCESS (attempt $attempt): Bytes=${bytesToWrite.size} to 0x${peerId.toString(16).uppercase()}")
                            break
                        }
                        kotlinx.coroutines.delay(35L)
                    }
                    if (!written) {
                        NetworkEventLogger.log("CONNECT_MESH_BLE: BLE_WRITE_FAILED after 5 attempts for 0x${peerId.toString(16).uppercase()}")
                    }
                } catch (e: Exception) {
                    NetworkEventLogger.log("CONNECT_MESH_BLE: WRITE_JOB_EXCEPTION: ${e.message}")
                } finally {
                    onComplete(written)
                }
            }
        }
        return true
    }

    fun cancelTransferOperations(peerId: Long, transferId: Long) {
        connections[peerId]?.queue?.cancelTransfer(transferId)
    }

    suspend fun awaitTransferBulkDrain(peerId: Long, transferId: Long, timeoutMs: Long = 10_000L): Boolean {
        val queue = connections[peerId]?.queue ?: return true
        return queue.awaitTransferBulkDrain(transferId, timeoutMs)
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        connections.values.forEach {
            try {
                it.gatt?.disconnect()
                it.gatt?.close()
            } catch (e: Exception) {}
            it.queue.clear()
        }
        connections.clear()
        addressToPeerId.clear()
    }

    fun getConnectionInfo(peerId: Long): ConnectionInfo? = connections[peerId]

    fun getAllConnections(): List<ConnectionInfo> = connections.values.toList()
}
