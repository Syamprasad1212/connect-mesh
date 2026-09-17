package com.connectmesh.service

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.connectmesh.auth.*
import com.connectmesh.broadcast.*
import com.connectmesh.classroom.*
import com.connectmesh.crypto.CryptoManager
import com.connectmesh.crypto.PeerAuthenticator
import com.connectmesh.crypto.SessionManager
import com.connectmesh.db.AppDatabaseHelper
import com.connectmesh.db.OutboxEntry
import com.connectmesh.db.OutboxStatus
import com.connectmesh.diagnostics.NetworkEventLogger
import com.connectmesh.file.FileManager
import com.connectmesh.identity.CryptoIdentityManager
import com.connectmesh.identity.DeviceIdentity
import com.connectmesh.identity.PeerAuthState
import com.connectmesh.identity.PeerIdentity
import com.connectmesh.mesh.*
import com.connectmesh.protocol.*
import com.connectmesh.relay.*
import com.connectmesh.voice.VoiceManager
import com.connectmesh.voice.VoiceReassembler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

class MeshForegroundService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var deviceIdentity: DeviceIdentity
        private set
    lateinit var cryptoManager: CryptoManager
        private set
    lateinit var peerManager: PeerManager
        private set
    lateinit var routeTable: RouteTable
        private set
    lateinit var relayManager: RelayManager
        private set
    lateinit var deduplicationManager: DeduplicationManager
        private set
    lateinit var meshRouter: MeshRouter
        private set
    lateinit var voiceManager: VoiceManager
        private set
    private lateinit var voiceReassembler: VoiceReassembler

    // Institutional Authorization, Classroom, Campus Broadcast & Static Relay Managers
    lateinit var authorizationManager: AuthorizationManager
        private set
    lateinit var classroomManager: ClassroomManager
        private set
    lateinit var campusBroadcastManager: CampusBroadcastManager
        private set
    lateinit var staticRelayController: StaticRelayController
        private set

    var enrolledCampusScope: String? = "COLLEGE:CAMPUS_01"
        private set

    // Local SQLite Persistence Helper
    private lateinit var dbHelper: AppDatabaseHelper

    private lateinit var gattServerManager: BleGattServerManager
    private lateinit var connectionManager: BleConnectionManager
    private lateinit var advertiser: BleAdvertiser
    private lateinit var scanner: BleScanner

    var isBleStackRunning = false
        private set
    private var routeAnnounceJob: Job? = null
    private var ackRetryJob: Job? = null

    // Active File Transfers Tracking Map
    val activeTransfers = ConcurrentHashMap<Long, FileManager.TransferState>()

    data class PendingPacketOp(
        val data: ByteArray,
        val transferId: Long? = null,
        val priority: BleOperationQueue.Priority = BleOperationQueue.Priority.HIGH,
        val isCancel: Boolean = false
    )

    // Pending packets queued while connection reaches READY
    private val pendingPacketsMap = ConcurrentHashMap<Long, MutableList<PendingPacketOp>>()

    // Challenge-Response Peer Authentication Challenges Map
    private val sentChallengesMap = ConcurrentHashMap<Long, ByteArray>()

    // Bounded ACK Retry Map for Outgoing Messages/Transfers
    data class RetryTask(
        val message: ChatMessage,
        var attemptCount: Int = 0,
        var lastAttemptTime: Long = System.currentTimeMillis()
    )
    private val pendingRetriesMap = ConcurrentHashMap<Long, RetryTask>()
    private val fileAckTimeoutJobs = ConcurrentHashMap<Long, Job>()
    private val fileSendJobs = ConcurrentHashMap<Long, Job>()

    fun cancelPendingPacketsForTransfer(peerId: Long, transferId: Long) {
        pendingPacketsMap[peerId]?.removeIf { it.transferId == transferId && !it.isCancel }
    }

    companion object {
        const val ACTION_STOP_MESH = "com.connectmesh.ACTION_STOP_MESH"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_INTERVAL_MS = 10_000L // 10 seconds
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        NetworkEventLogger.log("CONNECT_MESH_BLE: Broadcast received: Bluetooth turned ON")
                        startBleStack()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        NetworkEventLogger.log("CONNECT_MESH_BLE: Broadcast received: Bluetooth turned OFF")
                        stopBleStack()
                    }
                }
            }
        }
    }

    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messagesFlow: StateFlow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    private val _sosAlertsFlow = MutableStateFlow<List<SosAlert>>(emptyList())
    val sosAlertsFlow: StateFlow<List<SosAlert>> = _sosAlertsFlow.asStateFlow()

    private val _classroomsFlow = MutableStateFlow<List<ClassroomGroup>>(emptyList())
    val classroomsFlow: StateFlow<List<ClassroomGroup>> = _classroomsFlow.asStateFlow()

    private val _classroomMessagesUpdateFlow = MutableStateFlow<Long>(0L)
    val classroomMessagesUpdateFlow: StateFlow<Long> = _classroomMessagesUpdateFlow.asStateFlow()

    private val _campusBroadcastsFlow = MutableStateFlow<List<CollegeBroadcast>>(emptyList())
    val campusBroadcastsFlow: StateFlow<List<CollegeBroadcast>> = _campusBroadcastsFlow.asStateFlow()

    enum class DeliveryStatus {
        QUEUED,
        SENDING,
        RECEIVING,
        PROCESSING,
        DELIVERED,
        FAILED,
        CANCELLED
    }

    data class ChatMessage(
        val id: Long,
        val senderId: Long,
        val recipientId: Long,
        val text: String,
        val timestamp: Long,
        val isDelivered: Boolean = false,
        val deliveryStatus: DeliveryStatus = if (isDelivered) DeliveryStatus.DELIVERED else DeliveryStatus.SENDING,
        val isSelf: Boolean = false,
        val isVoice: Boolean = false,
        val voiceData: ByteArray? = null,
        val isFile: Boolean = false,
        val fileTransferId: Long = 0L,
        val fileName: String = "",
        val fileSize: Long = 0L,
        val mimeType: String = "",
        val fileStatus: FileManager.Status = FileManager.Status.PREPARING,
        val fileProgress: Int = 0,
        val localFilePath: String? = null
    )

    data class SosAlert(
        val packetId: Long,
        val senderId: Long,
        val senderNickname: String,
        val message: String,
        val timestamp: Long,
        val hopCount: Int = 1,
        val acknowledgedBy: Set<Long> = emptySet(),
        val isAcknowledgedByMe: Boolean = false
    ) {
        val acknowledgedCount: Int
            get() = acknowledgedBy.size
    }

    data class VoiceFragmentData(
        val transferId: Long,
        val index: Short,
        val total: Short,
        val crc32: Int,
        val data: ByteArray
    )

    inner class LocalBinder : Binder() {
        fun getService(): MeshForegroundService = this@MeshForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MESH) {
            stopMeshService()
            return START_NOT_STICKY
        }
        NetworkEventLogger.log("CONNECT_MESH_SERVICE: STARTED (startId=$startId)")
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        NetworkEventLogger.log("CONNECT_MESH_SERVICE: Task removed by user (app swiped away from Recents)")
        stopMeshService()
    }

    override fun onCreate() {
        super.onCreate()

        deviceIdentity = DeviceIdentity(this)
        cryptoManager = CryptoManager(this)
        peerManager = PeerManager()
        routeTable = RouteTable()
        relayManager = RelayManager()
        deduplicationManager = DeduplicationManager()
        voiceManager = VoiceManager(this)
        voiceReassembler = VoiceReassembler()
        dbHelper = AppDatabaseHelper(this)

        val localPubKey = CryptoIdentityManager.getInstance().publicKey

        // Initialize Authorization & Institutional Managers
        authorizationManager = AuthorizationManager()
        authorizationManager.initializeLocalIdentity(deviceIdentity.deviceId, localPubKey)

        // Register local identity as authorized Admin for institutional demonstration
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry("connect_mesh_identity_key", null) as? KeyStore.PrivateKeyEntry
        val signingPrivateKey = entry?.privateKey

        if (signingPrivateKey != null) {
            val selfAdminCred = RoleCredentialIssuer.issueCredential(
                issuerId = deviceIdentity.deviceId,
                issuerPrivateKey = signingPrivateKey,
                subjectConnectMeshId = deviceIdentity.deviceId,
                subjectPublicKeyBytes = localPubKey,
                role = UserRole.ADMIN,
                scope = "COLLEGE:CAMPUS_01"
            )
            if (selfAdminCred != null) {
                authorizationManager.registerTrustedIssuer(deviceIdentity.deviceId, localPubKey)
                authorizationManager.setLocalCredential(selfAdminCred)
            }
        }

        // Restore persisted trusted campus issuers from SharedPreferences
        try {
            val prefs = getSharedPreferences("connect_mesh_trusted_issuers", Context.MODE_PRIVATE)
            prefs.all.forEach { (key, value) ->
                val savedIssuerId = key.toLongOrNull()
                val hexKey = value as? String
                if (savedIssuerId != null && !hexKey.isNullOrBlank()) {
                    val keyBytes = authorizationManager.decodePublicKey(hexKey)
                    if (keyBytes != null) {
                        authorizationManager.registerTrustedIssuer(savedIssuerId, keyBytes)
                    }
                }
            }
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: ERROR restoring trusted issuers from prefs: ${e.message}")
        }

        // Restore persisted campus config from SharedPreferences
        try {
            val campusPrefs = getSharedPreferences("connect_mesh_campus_config", Context.MODE_PRIVATE)
            val savedScope = campusPrefs.getString("enrolled_campus_scope", "COLLEGE:CAMPUS_01")
            enrolledCampusScope = if (savedScope.isNullOrBlank()) null else savedScope
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: ERROR restoring campus scope: ${e.message}")
        }

        classroomManager = ClassroomManager(authorizationManager)
        campusBroadcastManager = CampusBroadcastManager(authorizationManager)
        staticRelayController = StaticRelayController(deduplicationManager, relayManager)

        // Restore persisted classrooms from SQLite Database
        val savedClassrooms = dbHelper.getAllClassrooms()
        if (savedClassrooms.isNotEmpty()) {
            savedClassrooms.forEach { classroomManager.registerClassroom(it) }
        } else {
            val demo = classroomManager.createClassroom("CSE-A", "COLLEGE:CAMPUS_01", deviceIdentity.deviceId, localPubKey)
            if (demo != null) {
                dbHelper.saveClassroom(demo)
            }
        }
        _classroomsFlow.value = classroomManager.getAllClassrooms()

        // Restore persisted campus broadcasts from SQLite Database
        val savedBroadcasts = dbHelper.getAllVerifiedBroadcasts()
        savedBroadcasts.forEach { campusBroadcastManager.addVerifiedBroadcast(it) }
        _campusBroadcastsFlow.value = campusBroadcastManager.getAllVerifiedBroadcasts()

        NetworkEventLogger.log("CONNECT_MESH_BLE: SERVICE_START - My Device ID = 0x${deviceIdentity.deviceId.toString(16).uppercase()}")

        // Restore persisted messages from SQLite Database
        val restoredMessages = dbHelper.getAllMessages()
        _messagesFlow.value = restoredMessages
        NetworkEventLogger.log("CONNECT_MESH_DB: RESTORED ${restoredMessages.size} messages from SQLite storage")

        // Clean expired or completed outbox records
        dbHelper.cleanExpiredOutboxEntries()

        // Restore persistent store-and-forward outbox entries
        val pendingOutbox = dbHelper.getPendingOutboxEntries()
        NetworkEventLogger.log("CONNECT_MESH_OUTBOX: RESTORED ${pendingOutbox.size} pending outbox items from SQLite storage")

        pendingOutbox.forEach { outbox ->
            val msg = restoredMessages.find { it.id == outbox.messageId || it.fileTransferId == outbox.messageId }
            if (msg != null && !msg.isDelivered) {
                pendingRetriesMap[msg.id] = RetryTask(
                    message = msg,
                    attemptCount = outbox.attemptCount,
                    lastAttemptTime = outbox.lastAttemptTime
                )
            }
        }

        // Initial cleaning of any stale state in memory
        routeTable.cleanExpiredRoutes(deviceIdentity.deviceId, 0L)
        peerManager.cleanExpiredPeers(deviceIdentity.deviceId, 0L)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        // Peripheral GATT Server
        gattServerManager = BleGattServerManager(this, bluetoothManager) { _, value ->
            val packet = PacketDecoder.decode(value)
            if (packet != null) {
                NetworkEventLogger.log("CONNECT_MESH_BLE: GATT_SERVER_RX: Type=${packet.header.packetType}, From=0x${packet.header.sourceId.toString(16).uppercase()}")
                meshRouter.handleIncomingPacket(packet)
            }
        }

        // Central GATT Connection Manager
        connectionManager = BleConnectionManager(
            context = this,
            bluetoothAdapter = bluetoothAdapter,
            scope = serviceScope,
            relayManager = relayManager,
            onPacketReceived = { _, packetBytes ->
                val packet = PacketDecoder.decode(packetBytes)
                if (packet != null) {
                    meshRouter.handleIncomingPacket(packet)
                }
            },
            onPeerStateChanged = { peerId, state ->
                if (state == BleConnectionState.READY) {
                    val challenge = PeerAuthenticator.generateChallenge()
                    sentChallengesMap[peerId] = challenge
                    val authHeader = PacketHeader(
                        packetType = PacketType.AUTH_REQUEST,
                        packetId = System.nanoTime(),
                        sourceId = deviceIdentity.deviceId,
                        destinationId = peerId,
                        payloadLength = challenge.size.toShort(),
                        ttl = 5
                    )
                    val authPacket = Packet(authHeader, payload = challenge)
                    connectionManager.sendPacket(peerId, PacketEncoder.encode(authPacket))

                    val pendingList = pendingPacketsMap.remove(peerId)
                    if (!pendingList.isNullOrEmpty()) {
                        pendingList.forEach { op ->
                            connectionManager.sendPacket(peerId, op.data, op.transferId, op.priority, op.isCancel)
                        }
                    }
                } else if (state == BleConnectionState.DISCONNECTED) {
                    activeTransfers.values.filter { it.metadata.recipientId == peerId || it.metadata.senderId == peerId }.forEach { transferState ->
                        val tId = transferState.transferId
                        if (transferState.status == FileManager.Status.SENDING ||
                            transferState.status == FileManager.Status.RECEIVING ||
                            transferState.status == FileManager.Status.WAITING_FOR_ACK) {

                            NetworkEventLogger.log("CONNECT_MESH_FILE: DISCONNECT_FAIL transferId=$tId peer=0x${peerId.toString(16).uppercase()}")
                            transferState.status = FileManager.Status.FAILED
                            cancelWaitingForAckTimeout(tId)
                            connectionManager.cancelTransferOperations(peerId, tId)
                            dbHelper.updateFileStatus(tId, FileManager.Status.FAILED, transferState.progressPercentage, false, DeliveryStatus.FAILED)
                            _messagesFlow.value = _messagesFlow.value.map {
                                if (it.fileTransferId == tId || it.id == tId) {
                                    it.copy(fileStatus = FileManager.Status.FAILED, deliveryStatus = DeliveryStatus.FAILED)
                                } else it
                            }
                            activeTransfers.remove(tId)
                        }
                    }
                }
            }
        )

        // Distance-Vector Mesh Router
        meshRouter = MeshRouter(
            localPeerId = deviceIdentity.deviceId,
            deduplicationManager = deduplicationManager,
            routeTable = routeTable,
            relayManager = relayManager,
            scope = serviceScope,
            sendPacketToPeer = { nextHop, packetBytes ->
                dispatchOrQueuePacket(nextHop, packetBytes)
            },
            broadcastPacket = { packetBytes ->
                broadcastPacketToAll(packetBytes)
            },
            onLocalMessageReceived = { packet ->
                when (packet.header.packetType) {
                    PacketType.AUTH_REQUEST -> {
                        val challenge = packet.payload
                        val sigBytes = PeerAuthenticator.signChallenge(challenge, deviceIdentity.deviceId, packet.header.sourceId)
                        if (sigBytes != null) {
                            val myPubKey = CryptoIdentityManager.getInstance().publicKey
                            val respPayload = PeerAuthenticator.encodeAuthResponsePayload(myPubKey, sigBytes)
                            val header = PacketHeader(
                                packetType = PacketType.AUTH_RESPONSE,
                                packetId = System.nanoTime(),
                                sourceId = deviceIdentity.deviceId,
                                destinationId = packet.header.sourceId,
                                payloadLength = respPayload.size.toShort(),
                                ttl = 5
                            )
                            val respPacket = Packet(header, payload = respPayload)
                            dispatchOrQueuePacket(packet.header.sourceId, PacketEncoder.encode(respPacket))
                            NetworkEventLogger.log("CONNECT_MESH_AUTH: AUTH_RESPONSE_SENT to 0x${packet.header.sourceId.toString(16).uppercase()}")
                        }
                    }
                    PacketType.AUTH_RESPONSE -> {
                        val challenge = sentChallengesMap.remove(packet.header.sourceId)
                        val decodedAuth = PeerAuthenticator.decodeAuthResponsePayload(packet.payload)
                        if (challenge != null && decodedAuth != null) {
                            val isValid = PeerAuthenticator.verifyPeerAuthentication(
                                claimedPeerId = packet.header.sourceId,
                                remotePublicKeyBytes = decodedAuth.publicKeyBytes,
                                challenge = challenge,
                                localId = deviceIdentity.deviceId,
                                signatureBytes = decodedAuth.signatureBytes
                            )
                            if (isValid) {
                                peerManager.updatePeerAuthState(packet.header.sourceId, PeerAuthState.AUTHENTICATED)
                                NetworkEventLogger.log("CONNECT_MESH_AUTH: AUTHENTICATED peer=0x${packet.header.sourceId.toString(16).uppercase()}")
                                
                                val msg1 = SessionManager.initiateSession(packet.header.sourceId, cryptoManager.staticKeyPair)
                                val sessHeader = PacketHeader(
                                    packetType = PacketType.SESSION_INIT,
                                    packetId = System.nanoTime(),
                                    sourceId = deviceIdentity.deviceId,
                                    destinationId = packet.header.sourceId,
                                    payloadLength = msg1.size.toShort(),
                                    ttl = 5
                                )
                                dispatchOrQueuePacket(packet.header.sourceId, PacketEncoder.encode(Packet(sessHeader, payload = msg1)))
                            } else {
                                peerManager.updatePeerAuthState(packet.header.sourceId, PeerAuthState.AUTHENTICATION_FAILED)
                                NetworkEventLogger.log("CONNECT_MESH_AUTH: AUTHENTICATION_FAILED peer=0x${packet.header.sourceId.toString(16).uppercase()}")
                            }
                        }
                    }
                    PacketType.SESSION_INIT -> {
                        val msg2 = SessionManager.handleSessionInit(packet.header.sourceId, cryptoManager.staticKeyPair, packet.payload)
                        val header = PacketHeader(
                            packetType = PacketType.SESSION_FINISH,
                            packetId = System.nanoTime(),
                            sourceId = deviceIdentity.deviceId,
                            destinationId = packet.header.sourceId,
                            payloadLength = msg2.size.toShort(),
                            ttl = 5
                        )
                        dispatchOrQueuePacket(packet.header.sourceId, PacketEncoder.encode(Packet(header, payload = msg2)))
                        NetworkEventLogger.log("CONNECT_MESH_SESSION: SESSION_ESTABLISHED responder for 0x${packet.header.sourceId.toString(16).uppercase()}")
                    }
                    PacketType.SESSION_FINISH -> {
                        val msg3 = SessionManager.handleSessionFinish(packet.header.sourceId, packet.payload)
                        if (msg3 != null) {
                            val header = PacketHeader(
                                packetType = PacketType.SESSION_FINISH,
                                packetId = System.nanoTime(),
                                sourceId = deviceIdentity.deviceId,
                                destinationId = packet.header.sourceId,
                                payloadLength = msg3.size.toShort(),
                                ttl = 5
                            )
                            dispatchOrQueuePacket(packet.header.sourceId, PacketEncoder.encode(Packet(header, payload = msg3)))
                        }
                    }
                    PacketType.MESSAGE -> {
                        val session = SessionManager.getSession(packet.header.sourceId)
                        val decryptedPacket = if (session != null) {
                            val aad = packet.header.constructAad()
                            val macTag = packet.macTag
                            if (macTag.size == BleConstants.MAC_TAG_SIZE) {
                                val decryptedTextBytes = session.decryptPayloadWithMacAndAad(packet.payload, macTag, aad)
                                if (decryptedTextBytes != null) {
                                    packet.copy(payload = decryptedTextBytes)
                                } else {
                                    NetworkEventLogger.log("CONNECT_MESH_CRYPTO: REJECTED_AEAD_MAC_FAILED from=0x${packet.header.sourceId.toString(16).uppercase()}")
                                    null
                                }
                            } else null
                        } else {
                            NetworkEventLogger.log("CONNECT_MESH_CRYPTO: REJECTED_ENCRYPTED_MESSAGE_NO_SESSION from=0x${packet.header.sourceId.toString(16).uppercase()}")
                            null
                        }

                        if (decryptedPacket != null) {
                            val textMsg = String(decryptedPacket.payload, Charsets.UTF_8)
                            val msg = ChatMessage(
                                id = decryptedPacket.header.packetId,
                                senderId = decryptedPacket.header.sourceId,
                                recipientId = decryptedPacket.header.destinationId,
                                text = textMsg,
                                timestamp = decryptedPacket.header.timestamp,
                                isSelf = false,
                                deliveryStatus = DeliveryStatus.DELIVERED
                            )
                            _messagesFlow.value = _messagesFlow.value + msg
                            dbHelper.insertOrUpdateMessage(msg)

                            val ackPacket = AckManager.createAckPacket(
                                originalPacketId = decryptedPacket.header.packetId,
                                localPeerId = deviceIdentity.deviceId,
                                targetPeerId = decryptedPacket.header.sourceId
                            )
                            val ackBytes = PacketEncoder.encode(ackPacket)
                            val nextHop = routeTable.getNextHop(decryptedPacket.header.sourceId) ?: decryptedPacket.header.sourceId
                            dispatchOrQueuePacket(nextHop, ackBytes)
                        }
                    }
                    PacketType.VOICE_FRAGMENT -> {
                        val fragHeader = packet.fragmentHeader
                        val session = SessionManager.getSession(packet.header.sourceId)
                        val targetPacket = if (fragHeader != null && session != null) {
                            val aad = constructVoiceFragmentAad(packet.header, fragHeader)
                            val macTag = packet.macTag
                            if (macTag.size == BleConstants.MAC_TAG_SIZE) {
                                val decryptedFragBytes = session.decryptPayloadWithMacAndAad(packet.payload, macTag, aad)
                                if (decryptedFragBytes != null) {
                                    packet.copy(payload = decryptedFragBytes)
                                } else {
                                    NetworkEventLogger.log("CONNECT_MESH_CRYPTO: REJECTED_VOICE_FRAGMENT_AEAD_MAC_FAILED from=0x${packet.header.sourceId.toString(16).uppercase()}")
                                    null
                                }
                            } else null
                        } else packet

                        if (targetPacket != null) {
                            val result = voiceReassembler.handleVoiceFragment(targetPacket)
                            if (result != null) {
                                val (voiceTransferId, voiceData) = result
                                val receivedDir = File(cacheDir, "received").apply { if (!exists()) mkdirs() }
                                val voiceFile = File(receivedDir, "voice_$voiceTransferId.amr")
                                try { voiceFile.writeBytes(voiceData) } catch (e: Exception) {}

                                val msg = ChatMessage(
                                    id = voiceTransferId,
                                    senderId = targetPacket.header.sourceId,
                                    recipientId = targetPacket.header.destinationId,
                                    text = "🎤 Voice Note (${voiceData.size} bytes)",
                                    timestamp = targetPacket.header.timestamp,
                                    isSelf = false,
                                    isVoice = true,
                                    voiceData = voiceData,
                                    deliveryStatus = DeliveryStatus.DELIVERED,
                                    localFilePath = voiceFile.absolutePath
                                )
                                _messagesFlow.value = _messagesFlow.value + msg
                                dbHelper.insertOrUpdateMessage(msg)

                                val ackPacket = AckManager.createAckPacket(
                                    originalPacketId = voiceTransferId,
                                    localPeerId = deviceIdentity.deviceId,
                                    targetPeerId = targetPacket.header.sourceId
                                )
                                val ackBytes = PacketEncoder.encode(ackPacket)
                                val nextHop = routeTable.getNextHop(targetPacket.header.sourceId) ?: targetPacket.header.sourceId
                                dispatchOrQueuePacket(nextHop, ackBytes)
                            }
                        }
                    }
                    PacketType.SOS -> {
                        val payloadStr = String(packet.payload, Charsets.UTF_8)
                        val parts = payloadStr.split("|", limit = 2)
                        val senderNick = parts.getOrNull(0) ?: "Peer ...${packet.header.sourceId.toString(16).takeLast(4).uppercase()}"
                        val msgText = parts.getOrNull(1) ?: "Emergency Alert!"
                        val hops = maxOf(1, 6 - packet.header.ttl.toInt())

                        if (hops <= RouteTable.MAX_MESH_HOPS) {
                            val sosAlert = SosAlert(
                                packetId = packet.header.packetId,
                                senderId = packet.header.sourceId,
                                senderNickname = senderNick,
                                message = msgText,
                                timestamp = packet.header.timestamp,
                                hopCount = hops,
                                acknowledgedBy = emptySet(),
                                isAcknowledgedByMe = false
                            )
                            _sosAlertsFlow.value = _sosAlertsFlow.value + sosAlert
                            NetworkEventLogger.log("CONNECT_MESH_SOS: SOS_RECEIVED from 0x${packet.header.sourceId.toString(16).uppercase()} packetId=${packet.header.packetId}")
                        }
                    }
                    PacketType.CLASSROOM_MSG -> {
                        val fullPayload = packet.payload
                        val payloadStr = try { String(fullPayload, Charsets.UTF_8) } catch (e: Exception) { "" }
                        val firstPipe = payloadStr.indexOf('|')
                        val secondPipe = if (firstPipe != -1) payloadStr.indexOf('|', firstPipe + 1) else -1

                        val groupId: String
                        val keyVersion: Int
                        val encryptedPart: ByteArray

                        if (firstPipe != -1 && secondPipe != -1) {
                            groupId = payloadStr.substring(0, firstPipe)
                            val keyVerStr = payloadStr.substring(firstPipe + 1, secondPipe)
                            keyVersion = keyVerStr.toIntOrNull() ?: 1
                            val headerBytesLen = (groupId + "|" + keyVerStr + "|").toByteArray(Charsets.UTF_8).size
                            encryptedPart = fullPayload.copyOfRange(headerBytesLen, fullPayload.size)
                        } else {
                            groupId = "GRP-CSE-A"
                            keyVersion = 1
                            encryptedPart = fullPayload
                        }

                        if (encryptedPart.size >= 16) {
                            val seq = packet.header.packetId
                            val macTag = encryptedPart.takeLast(16).toByteArray()
                            val cipherText = encryptedPart.dropLast(16).toByteArray()
                            val headerAad = "AAD_CLASSROOM".toByteArray(Charsets.UTF_8)

                            if (classroomManager.isMember(groupId, deviceIdentity.deviceId)) {
                                val plainText = classroomManager.decryptGroupPayload(
                                    groupId = groupId,
                                    keyVersion = keyVersion,
                                    sequence = seq,
                                    memberConnectMeshId = deviceIdentity.deviceId,
                                    ciphertext = cipherText,
                                    macTag = macTag,
                                    headerAad = headerAad
                                )
                                if (plainText != null) {
                                    val text = String(plainText, Charsets.UTF_8)
                                    val msg = ClassroomMessage(
                                        id = seq,
                                        groupId = groupId,
                                        senderId = packet.header.sourceId,
                                        text = text,
                                        timestamp = packet.header.timestamp,
                                        groupKeyVersion = keyVersion,
                                        isSelf = (packet.header.sourceId == deviceIdentity.deviceId)
                                    )
                                    classroomManager.addMessage(msg)
                                    _classroomMessagesUpdateFlow.value = System.currentTimeMillis()
                                    NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: CLASSROOM_MSG_DECRYPTED_SUCCESS id=$seq groupId=$groupId")
                                }
                            } else {
                                NetworkEventLogger.log("CONNECT_MESH_CLASSROOM: MSG_DROPPED_NON_MEMBER groupId=$groupId sender=0x${packet.header.sourceId.toString(16).uppercase()}")
                            }
                        }
                    }
                    PacketType.COLLEGE_BROADCAST -> {
                        val bc = CollegeBroadcast.fromWirePayload(packet.payload)
                        if (bc != null) {
                            val trustedAdminKey = authorizationManager.getTrustedIssuerKey(bc.senderConnectMeshId)
                                ?: (if (bc.senderConnectMeshId == deviceIdentity.deviceId) CryptoIdentityManager.getInstance().publicKey else null)

                            val verResult = if (trustedAdminKey != null) {
                                CollegeBroadcastVerifier.verifyBroadcast(
                                    broadcast = bc,
                                    actualSenderId = packet.header.sourceId,
                                    trustedAdminPublicKeyBytes = trustedAdminKey,
                                    requiredScope = null,
                                    revocationManager = authorizationManager.revocationManager
                                )
                            } else {
                                CollegeBroadcastVerifier.VerificationResult.REJECTED_UNTRUSTED_ISSUER
                            }

                            if (verResult == CollegeBroadcastVerifier.VerificationResult.AUTHORIZED) {
                                if (!campusBroadcastManager.isDuplicateOrAdd(bc.broadcastId)) {
                                    if (CollegeBroadcastVerifier.isCampusScopeMatching(bc.institutionScope, enrolledCampusScope)) {
                                        campusBroadcastManager.addVerifiedBroadcast(bc)
                                        dbHelper.saveBroadcast(bc)
                                        _campusBroadcastsFlow.value = campusBroadcastManager.getAllVerifiedBroadcasts()
                                        NetworkEventLogger.log("CONNECT_MESH_BROADCAST: COLLEGE_BROADCAST_RECEIVED_VERIFIED id=${bc.broadcastId} title='${bc.title}' scope=${bc.institutionScope}")
                                    } else {
                                        NetworkEventLogger.log("CONNECT_MESH_BROADCAST: IGNORED_SCOPE_MISMATCH id=${bc.broadcastId} broadcastScope=${bc.institutionScope} enrolledScope=$enrolledCampusScope")
                                    }
                                } else {
                                    NetworkEventLogger.log("CONNECT_MESH_BROADCAST: SUPPRESSED_DUPLICATE_BROADCAST id=${bc.broadcastId}")
                                }
                            } else {
                                NetworkEventLogger.log("CONNECT_MESH_BROADCAST: REJECTED_UNVERIFIED_BROADCAST id=${bc.broadcastId} result=$verResult")
                            }
                        }
                    }
                    PacketType.FILE_START -> handleFileStart(packet)
                    PacketType.FILE_CHUNK -> handleFileChunk(packet)
                    PacketType.FILE_END -> handleFileEnd(packet)
                    PacketType.FILE_ACK -> handleFileAck(packet)
                    PacketType.FILE_CANCEL -> handleFileCancel(packet)
                    PacketType.ANNOUNCE -> processRouteAnnounce(packet)
                    else -> {}
                }
            },
            onAckReceived = { originalPacketId ->
                pendingRetriesMap.remove(originalPacketId)
                dbHelper.updateDeliveryStatus(originalPacketId, true, DeliveryStatus.DELIVERED)
                dbHelper.markOutboxDelivered(originalPacketId)

                val matchedMsg = _messagesFlow.value.find { it.id == originalPacketId || it.fileTransferId == originalPacketId }
                if (matchedMsg != null && matchedMsg.isVoice) {
                    connectionManager.cancelTransferOperations(matchedMsg.recipientId, originalPacketId)
                }

                _messagesFlow.value = _messagesFlow.value.map { msg ->
                    if (msg.id == originalPacketId || msg.fileTransferId == originalPacketId) {
                        if (msg.isVoice) {
                            NetworkEventLogger.log("CONNECT_MESH_DELIVERY: VOICE_ACK_RECEIVED id=$originalPacketId")
                            NetworkEventLogger.log("CONNECT_MESH_DELIVERY: VOICE_STATUS_UPDATED id=$originalPacketId state=DELIVERED")
                        } else {
                            NetworkEventLogger.log("CONNECT_MESH_DELIVERY: TEXT_ACK_RECEIVED id=$originalPacketId")
                            NetworkEventLogger.log("CONNECT_MESH_DELIVERY: TEXT_STATUS_UPDATED id=$originalPacketId state=DELIVERED")
                        }
                        msg.copy(isDelivered = true, deliveryStatus = DeliveryStatus.DELIVERED)
                    } else msg
                }
            },
            onSosAckReceived = { originalPacketId, ackSenderId ->
                handleSosAckReceived(originalPacketId, ackSenderId)
            }
        )

        advertiser = BleAdvertiser(bluetoothAdapter, deviceIdentity.deviceId)
        scanner = BleScanner(bluetoothAdapter) { peerId, address, rssi ->
            if (peerId != 0L && peerId != deviceIdentity.deviceId) {
                val existing = peerManager.getPeer(peerId)
                val nick = existing?.nickname ?: "Peer ...${peerId.toString(16).takeLast(4).uppercase()}"
                val peer = PeerIdentity(
                    peerId = peerId,
                    nickname = nick,
                    bleAddress = address,
                    hopCount = 1
                )
                peerManager.updatePeer(peer)
                routeTable.updateRoute(peerId, peerId, 1, deviceIdentity.deviceId)
                connectionManager.connectToPeer(peerId, address)
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)

        startForegroundServiceNotification()
        startBleStack()
        startPeriodicRouteAnnouncements()
        startAckRetryTaskScheduler()
    }

    fun createClassroom(name: String, scope: String = "COLLEGE:CAMPUS_01"): ClassroomGroup? {
        val pubKey = CryptoIdentityManager.getInstance().publicKey
        val group = classroomManager.createClassroom(name, scope, deviceIdentity.deviceId, pubKey)
        if (group != null) {
            dbHelper.saveClassroom(group)
            _classroomsFlow.value = classroomManager.getAllClassrooms()
        }
        return group
    }

    fun joinClassroomByCode(code: String): ClassroomGroup? {
        val pubKey = CryptoIdentityManager.getInstance().publicKey
        val group = classroomManager.joinClassroomByCode(code, deviceIdentity.deviceId, pubKey)
        if (group != null) {
            dbHelper.saveClassroom(group)
            _classroomsFlow.value = classroomManager.getAllClassrooms()
        }
        return group
    }

    fun sendClassroomMessage(groupId: String, text: String): ClassroomMessage? {
        val plainBytes = text.toByteArray(Charsets.UTF_8)
        val headerAad = "AAD_CLASSROOM".toByteArray(Charsets.UTF_8)
        val seq = System.nanoTime()
        val encResult = classroomManager.encryptGroupPayload(groupId, seq, plainBytes, headerAad) ?: return null

        val (keyVersion, cipherPair) = encResult
        val (cipherText, macTag) = cipherPair
        val headerStr = "$groupId|$keyVersion|"
        val headerBytes = headerStr.toByteArray(Charsets.UTF_8)
        val payloadBytes = headerBytes + cipherText + macTag

        val header = PacketHeader(
            packetType = PacketType.CLASSROOM_MSG,
            packetId = seq,
            sourceId = deviceIdentity.deviceId,
            destinationId = 0L,
            payloadLength = payloadBytes.size.toShort(),
            ttl = 7
        )
        val packet = Packet(header, payload = payloadBytes)
        meshRouter.handleIncomingPacket(packet)

        val msg = ClassroomMessage(
            id = seq,
            groupId = groupId,
            senderId = deviceIdentity.deviceId,
            text = text,
            timestamp = System.currentTimeMillis(),
            groupKeyVersion = keyVersion,
            isSelf = true
        )
        classroomManager.addMessage(msg)
        _classroomMessagesUpdateFlow.value = System.currentTimeMillis()

        val outboxEntry = OutboxEntry(
            messageId = seq,
            senderId = deviceIdentity.deviceId,
            recipientId = 0L,
            messageType = "CLASSROOM",
            payload = plainBytes,
            createdAt = System.currentTimeMillis(),
            status = OutboxStatus.PENDING,
            extraMeta = groupId
        )
        dbHelper.saveToOutbox(outboxEntry)

        return msg
    }

    fun createCampusBroadcast(title: String, message: String, priority: BroadcastPriority = BroadcastPriority.NORMAL, scope: String = (enrolledCampusScope ?: "COLLEGE:CAMPUS_01")): CollegeBroadcast? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry("connect_mesh_identity_key", null) as? KeyStore.PrivateKeyEntry ?: return null
        val adminPrivateKey = entry.privateKey

        val bc = campusBroadcastManager.createAndSignBroadcast(
            adminConnectMeshId = deviceIdentity.deviceId,
            adminCredentialId = "CRED-ADMIN-LOCAL",
            adminPrivateKey = adminPrivateKey,
            institutionScope = scope,
            title = title,
            message = message,
            priority = priority
        )
        if (bc != null) {
            dbHelper.saveBroadcast(bc)
            _campusBroadcastsFlow.value = campusBroadcastManager.getAllVerifiedBroadcasts()
            val seq = System.nanoTime()
            val payloadBytes = bc.toWirePayload()
            val header = PacketHeader(
                packetType = PacketType.COLLEGE_BROADCAST,
                packetId = seq,
                sourceId = deviceIdentity.deviceId,
                destinationId = 0L,
                payloadLength = payloadBytes.size.toShort(),
                ttl = 7
            )
            val packet = Packet(header, payload = payloadBytes)
            meshRouter.handleIncomingPacket(packet)

            val outboxEntry = OutboxEntry(
                messageId = seq,
                senderId = deviceIdentity.deviceId,
                recipientId = 0L,
                messageType = "BROADCAST",
                payload = payloadBytes,
                createdAt = System.currentTimeMillis(),
                status = OutboxStatus.PENDING,
                extraMeta = scope
            )
            dbHelper.saveToOutbox(outboxEntry)
        }
        return bc
    }

    fun registerTrustedCampusIssuer(issuerId: Long, publicKeyString: String, campusScope: String = "COLLEGE:CAMPUS_01"): Boolean {
        val keyBytes = authorizationManager.decodePublicKey(publicKeyString) ?: return false
        authorizationManager.registerTrustedIssuer(issuerId, keyBytes)
        saveTrustedIssuerToPrefs(issuerId, keyBytes)
        setEnrolledCampusScope(campusScope)
        NetworkEventLogger.log("CONNECT_MESH_AUTH: TRUSTED_CAMPUS_ISSUER_ENROLLED id=0x${issuerId.toString(16).uppercase()} scope=$enrolledCampusScope")
        return true
    }

    fun setupAdminCampus(campusScope: String): CampusEnrollmentDetails? {
        var formattedScope = campusScope.trim().uppercase()
        if (formattedScope.isBlank()) formattedScope = "CAMPUS_01"
        if (!formattedScope.contains(":")) formattedScope = "COLLEGE:$formattedScope"

        val localPubKey = CryptoIdentityManager.getInstance().publicKey
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry("connect_mesh_identity_key", null) as? java.security.KeyStore.PrivateKeyEntry
        val signingPrivateKey = entry?.privateKey

        if (signingPrivateKey != null) {
            val selfAdminCred = RoleCredentialIssuer.issueCredential(
                issuerId = deviceIdentity.deviceId,
                issuerPrivateKey = signingPrivateKey,
                subjectConnectMeshId = deviceIdentity.deviceId,
                subjectPublicKeyBytes = localPubKey,
                role = UserRole.ADMIN,
                scope = formattedScope
            )
            if (selfAdminCred != null) {
                authorizationManager.registerTrustedIssuer(deviceIdentity.deviceId, localPubKey)
                authorizationManager.setLocalCredential(selfAdminCred)
                saveTrustedIssuerToPrefs(deviceIdentity.deviceId, localPubKey)
            }
        }

        setEnrolledCampusScope(formattedScope)

        val pubKeyB64 = java.util.Base64.getEncoder().encodeToString(localPubKey)
        val authorityIdHex = "0x${deviceIdentity.deviceId.toString(16).uppercase()}"

        return CampusEnrollmentDetails(
            campusScope = formattedScope,
            authorityIdHex = authorityIdHex,
            authorityPublicKeyBase64 = pubKeyB64
        )
    }

    fun getLocalCampusEnrollmentDetails(): CampusEnrollmentDetails? {
        val scope = enrolledCampusScope ?: return null
        val localPubKey = CryptoIdentityManager.getInstance().publicKey
        val pubKeyB64 = java.util.Base64.getEncoder().encodeToString(localPubKey)
        val authorityIdHex = "0x${deviceIdentity.deviceId.toString(16).uppercase()}"

        return CampusEnrollmentDetails(
            campusScope = scope,
            authorityIdHex = authorityIdHex,
            authorityPublicKeyBase64 = pubKeyB64
        )
    }

    fun setEnrolledCampusScope(scope: String?) {
        if (scope.isNullOrBlank()) {
            this.enrolledCampusScope = null
        } else {
            var formattedScope = scope.trim().uppercase()
            if (!formattedScope.contains(":")) formattedScope = "COLLEGE:$formattedScope"
            this.enrolledCampusScope = formattedScope
        }
        try {
            val prefs = getSharedPreferences("connect_mesh_campus_config", Context.MODE_PRIVATE)
            prefs.edit().putString("enrolled_campus_scope", this.enrolledCampusScope).apply()
            NetworkEventLogger.log("CONNECT_MESH_AUTH: CAMPUS_SCOPE_SET scope=$enrolledCampusScope")
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: ERROR saving campus scope: ${e.message}")
        }
    }

    private fun saveTrustedIssuerToPrefs(issuerId: Long, keyBytes: ByteArray) {
        try {
            val prefs = getSharedPreferences("connect_mesh_trusted_issuers", Context.MODE_PRIVATE)
            val hexKey = keyBytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(issuerId.toString(), hexKey).apply()
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_AUTH: ERROR saving trusted issuer to prefs: ${e.message}")
        }
    }

    fun updateNickname(newNickname: String) {
        deviceIdentity.nickname = newNickname
        broadcastNameAnnounce()
    }

    fun sendMessage(recipientId: Long, text: String): ChatMessage {
        val messageId = System.nanoTime()
        val timestamp = System.currentTimeMillis()
        val plainBytes = text.toByteArray(Charsets.UTF_8)

        val session = SessionManager.getSession(recipientId)
        val (encryptedBytes, macTag) = if (session != null) {
            val dummyHeader = PacketHeader(
                packetType = PacketType.MESSAGE,
                packetId = messageId,
                sourceId = deviceIdentity.deviceId,
                destinationId = recipientId,
                payloadLength = plainBytes.size.toShort(),
                ttl = 7,
                timestamp = timestamp
            )
            val aad = dummyHeader.constructAad()
            session.encryptPayloadWithMacAndAad(plainBytes, aad)
        } else {
            Pair(plainBytes, null)
        }

        val header = PacketHeader(
            packetType = PacketType.MESSAGE,
            packetId = messageId,
            sourceId = deviceIdentity.deviceId,
            destinationId = recipientId,
            payloadLength = encryptedBytes.size.toShort(),
            ttl = 7,
            timestamp = timestamp
        )
        val packet = Packet(header, payload = encryptedBytes, macTag = macTag ?: ByteArray(16))
        val encodedBytes = PacketEncoder.encode(packet)

        val chatMessage = ChatMessage(
            id = messageId,
            senderId = deviceIdentity.deviceId,
            recipientId = recipientId,
            text = text,
            timestamp = timestamp,
            isDelivered = false,
            deliveryStatus = DeliveryStatus.SENDING,
            isSelf = true
        )
        _messagesFlow.value = _messagesFlow.value + chatMessage
        dbHelper.insertOrUpdateMessage(chatMessage)

        val outboxEntry = OutboxEntry(
            messageId = messageId,
            senderId = deviceIdentity.deviceId,
            recipientId = recipientId,
            messageType = "TEXT",
            payload = plainBytes,
            createdAt = timestamp,
            status = OutboxStatus.PENDING
        )
        dbHelper.saveToOutbox(outboxEntry)

        val retryTask = RetryTask(chatMessage)
        pendingRetriesMap[messageId] = retryTask

        val nextHop = routeTable.getNextHop(recipientId) ?: recipientId
        dispatchOrQueuePacket(nextHop, encodedBytes)

        NetworkEventLogger.log("CONNECT_MESH_DELIVERY: TEXT_SENT id=$messageId destination=0x${recipientId.toString(16).uppercase()}")
        return chatMessage
    }

    fun sendVoiceNote(recipientId: Long, voiceData: ByteArray): ChatMessage {
        val voiceTransferId = System.nanoTime()
        val timestamp = System.currentTimeMillis()

        val receivedDir = File(cacheDir, "received").apply { if (!exists()) mkdirs() }
        val voiceFile = File(receivedDir, "voice_$voiceTransferId.amr")
        try { voiceFile.writeBytes(voiceData) } catch (e: Exception) {}

        val chatMessage = ChatMessage(
            id = voiceTransferId,
            senderId = deviceIdentity.deviceId,
            recipientId = recipientId,
            text = "🎤 Voice Note (${voiceData.size} bytes)",
            timestamp = timestamp,
            isDelivered = false,
            deliveryStatus = DeliveryStatus.SENDING,
            isSelf = true,
            isVoice = true,
            voiceData = voiceData,
            localFilePath = voiceFile.absolutePath
        )
        _messagesFlow.value = _messagesFlow.value + chatMessage
        dbHelper.insertOrUpdateMessage(chatMessage)

        val outboxEntry = OutboxEntry(
            messageId = voiceTransferId,
            senderId = deviceIdentity.deviceId,
            recipientId = recipientId,
            messageType = "VOICE",
            payload = voiceData,
            createdAt = timestamp,
            status = OutboxStatus.PENDING,
            extraMeta = voiceFile.absolutePath
        )
        dbHelper.saveToOutbox(outboxEntry)

        pendingRetriesMap[voiceTransferId] = RetryTask(chatMessage)

        val fragments = fragmentVoicePayload(voiceTransferId, recipientId, voiceData)
        val nextHop = routeTable.getNextHop(recipientId) ?: recipientId

        val session = SessionManager.getSession(recipientId)

        fragments.forEach { frag ->
            val fragHeader = FragmentHeader(
                fragmentId = frag.transferId,
                fragmentIndex = frag.index,
                totalFragments = frag.total,
                crc32 = frag.crc32
            )
            val header = PacketHeader(
                packetType = PacketType.VOICE_FRAGMENT,
                packetId = System.nanoTime(),
                sourceId = deviceIdentity.deviceId,
                destinationId = recipientId,
                payloadLength = frag.data.size.toShort(),
                ttl = 7,
                timestamp = timestamp
            )

            val (encData, macTag) = if (session != null) {
                val aad = constructVoiceFragmentAad(header, fragHeader)
                session.encryptPayloadWithMacAndAad(frag.data, aad)
            } else {
                Pair(frag.data, null)
            }

            val packet = Packet(
                header = header,
                fragmentHeader = fragHeader,
                payload = encData,
                macTag = macTag ?: ByteArray(16)
            )
            dispatchOrQueuePacket(
                nextHop,
                PacketEncoder.encode(packet),
                transferId = voiceTransferId,
                priority = BleOperationQueue.Priority.BULK
            )
        }

        NetworkEventLogger.log("CONNECT_MESH_DELIVERY: VOICE_SENT id=$voiceTransferId destination=0x${recipientId.toString(16).uppercase()} fragments=${fragments.size}")
        return chatMessage
    }

    fun sendFile(targetPeerId: Long, uri: Uri): ChatMessage? {
        val details = FileManager.getFileDetailsFromUri(this, uri)
        if (details == null) {
            NetworkEventLogger.log("CONNECT_MESH_FILE: ERROR_READING_FILE_URI")
            return null
        }

        val (fileName, fileSize) = details
        if (fileSize > FileManager.MAX_FILE_SIZE_BYTES) {
            NetworkEventLogger.log("CONNECT_MESH_FILE: FILE_TRANSFER_FAILED reason=MAX_SIZE_EXCEEDED size=$fileSize limit=${FileManager.MAX_FILE_SIZE_BYTES}")
            return null
        }

        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val transferId = System.nanoTime()
        val sha256Hex = FileManager.calculateSha256(this, uri) ?: ""

        val chunkSize = FileManager.DEFAULT_CHUNK_SIZE
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()

        val metadata = FileManager.FileMetadata(
            transferId = transferId,
            senderId = deviceIdentity.deviceId,
            recipientId = targetPeerId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            sha256Hex = sha256Hex
        )

        val receivedDir = File(cacheDir, "received").apply { if (!exists()) mkdirs() }
        val localCopyFile = File(receivedDir, "sent_$fileName")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                localCopyFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {}

        val state = FileManager.TransferState(
            transferId = transferId,
            metadata = metadata,
            status = FileManager.Status.PREPARING,
            isSelf = true,
            localFilePath = localCopyFile.absolutePath
        )
        activeTransfers[transferId] = state

        val msg = ChatMessage(
            id = transferId,
            senderId = deviceIdentity.deviceId,
            recipientId = targetPeerId,
            text = "📄 File: $fileName (${fileSize / 1024} KB)",
            timestamp = System.currentTimeMillis(),
            isDelivered = false,
            deliveryStatus = DeliveryStatus.SENDING,
            isSelf = true,
            isFile = true,
            fileTransferId = transferId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            fileStatus = FileManager.Status.PREPARING,
            fileProgress = 0,
            localFilePath = localCopyFile.absolutePath
        )

        _messagesFlow.value = _messagesFlow.value + msg
        dbHelper.insertOrUpdateMessage(msg)

        val outboxEntry = OutboxEntry(
            messageId = transferId,
            senderId = deviceIdentity.deviceId,
            recipientId = targetPeerId,
            messageType = "FILE",
            payload = null,
            createdAt = System.currentTimeMillis(),
            status = OutboxStatus.PENDING,
            extraMeta = localCopyFile.absolutePath
        )
        dbHelper.saveToOutbox(outboxEntry)

        val startPayload = FileManager.encodeFileStartPayload(metadata)
        val startHeader = PacketHeader(
            packetType = PacketType.FILE_START,
            packetId = System.nanoTime(),
            sourceId = deviceIdentity.deviceId,
            destinationId = targetPeerId,
            payloadLength = startPayload.size.toShort(),
            ttl = 7
        )
        val session = SessionManager.getSession(targetPeerId)
        val (encStartPayload, startMacTag) = if (session != null) {
            val aad = startHeader.constructAad()
            session.encryptPayloadWithMacAndAad(startPayload, aad)
        } else Pair(startPayload, null)

        val startPacket = Packet(startHeader, payload = encStartPayload, macTag = startMacTag ?: ByteArray(16))
        val nextHop = routeTable.getNextHop(targetPeerId) ?: targetPeerId
        dispatchOrQueuePacket(nextHop, PacketEncoder.encode(startPacket), transferId = transferId, priority = BleOperationQueue.Priority.HIGH)

        val sendJob = serviceScope.launch {
            try {
                state.status = FileManager.Status.SENDING
                dbHelper.updateFileStatus(transferId, FileManager.Status.SENDING, 0, false, DeliveryStatus.SENDING)
                _messagesFlow.value = _messagesFlow.value.map {
                    if (it.fileTransferId == transferId) it.copy(fileStatus = FileManager.Status.SENDING) else it
                }

                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    NetworkEventLogger.log("CONNECT_MESH_FILE: STREAM_NULL transferId=$transferId")
                    state.status = FileManager.Status.FAILED
                    dbHelper.updateFileStatus(transferId, FileManager.Status.FAILED, 0, false, DeliveryStatus.FAILED)
                    _messagesFlow.value = _messagesFlow.value.map {
                        if (it.fileTransferId == transferId) it.copy(fileStatus = FileManager.Status.FAILED, deliveryStatus = DeliveryStatus.FAILED) else it
                    }
                    return@launch
                }

                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                var chunkIndex = 0

                inputStream.use { stream ->
                    while (stream.read(buffer).also { bytesRead = it } != -1 && state.status == FileManager.Status.SENDING) {
                        val chunkData = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                        val chunkPayload = FileManager.encodeFileChunkPayload(transferId, chunkIndex, totalChunks, chunkData)

                        val session = SessionManager.getSession(targetPeerId)
                        val dummyHeader = PacketHeader(
                            packetType = PacketType.FILE_CHUNK,
                            packetId = System.nanoTime(),
                            sourceId = deviceIdentity.deviceId,
                            destinationId = targetPeerId,
                            payloadLength = chunkPayload.size.toShort(),
                            ttl = 7
                        )

                        val (encChunk, macTag) = if (session != null) {
                            val aad = dummyHeader.constructAad()
                            session.encryptPayloadWithMacAndAad(chunkPayload, aad)
                        } else {
                            Pair(chunkPayload, null)
                        }

                        val chunkPacket = Packet(
                            header = dummyHeader,
                            payload = encChunk,
                            macTag = macTag ?: ByteArray(16)
                        )

                        dispatchOrQueuePacket(
                            nextHop,
                            PacketEncoder.encode(chunkPacket),
                            transferId = transferId,
                            priority = BleOperationQueue.Priority.BULK
                        )

                        chunkIndex++
                        state.chunksTransferred = chunkIndex
                        state.bytesTransferred += chunkData.size
                        val progress = state.progressPercentage

                        dbHelper.updateFileStatus(transferId, FileManager.Status.SENDING, progress, false, DeliveryStatus.SENDING)
                        _messagesFlow.value = _messagesFlow.value.map {
                            if (it.fileTransferId == transferId) it.copy(fileProgress = progress) else it
                        }
                        delay(15)
                    }
                }

                if (state.status == FileManager.Status.SENDING) {
                    connectionManager.awaitTransferBulkDrain(targetPeerId, transferId, 15_000L)
                    val endPayload = ByteBuffer.allocate(8).putLong(transferId).array()
                    val endHeader = PacketHeader(
                        packetType = PacketType.FILE_END,
                        packetId = System.nanoTime(),
                        sourceId = deviceIdentity.deviceId,
                        destinationId = targetPeerId,
                        payloadLength = endPayload.size.toShort(),
                        ttl = 7
                    )
                    val session = SessionManager.getSession(targetPeerId)
                    val (encEndPayload, endMacTag) = if (session != null) {
                        val aad = endHeader.constructAad()
                        session.encryptPayloadWithMacAndAad(endPayload, aad)
                    } else Pair(endPayload, null)

                    val endPacket = Packet(endHeader, payload = encEndPayload, macTag = endMacTag ?: ByteArray(16))
                    dispatchOrQueuePacket(
                        nextHop,
                        PacketEncoder.encode(endPacket),
                        transferId = transferId,
                        priority = BleOperationQueue.Priority.HIGH
                    )
                    state.status = FileManager.Status.WAITING_FOR_ACK
                    dbHelper.updateFileStatus(transferId, FileManager.Status.WAITING_FOR_ACK, 100, false, DeliveryStatus.SENDING)
                    _messagesFlow.value = _messagesFlow.value.map {
                        if (it.fileTransferId == transferId) it.copy(fileStatus = FileManager.Status.WAITING_FOR_ACK, fileProgress = 100) else it
                    }
                    startWaitingForAckTimeout(targetPeerId, transferId)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NetworkEventLogger.log("CONNECT_MESH_FILE: SEND_EXCEPTION: ${e.message}")
                state.status = FileManager.Status.FAILED
                cancelWaitingForAckTimeout(transferId)
                connectionManager.cancelTransferOperations(targetPeerId, transferId)
                dbHelper.updateFileStatus(transferId, FileManager.Status.FAILED, state.progressPercentage, false, DeliveryStatus.FAILED)
                _messagesFlow.value = _messagesFlow.value.map {
                    if (it.fileTransferId == transferId) it.copy(fileStatus = FileManager.Status.FAILED, deliveryStatus = DeliveryStatus.FAILED) else it
                }
                activeTransfers.remove(transferId)
            }
        }
        fileSendJobs[transferId] = sendJob
        sendJob.invokeOnCompletion { fileSendJobs.remove(transferId) }
        return msg
    }

    private fun startWaitingForAckTimeout(targetPeerId: Long, transferId: Long, timeoutMs: Long = 30_000L) {
        cancelWaitingForAckTimeout(transferId)
        val job = serviceScope.launch {
            delay(timeoutMs)
            val state = activeTransfers[transferId]
            if (state != null && state.status == FileManager.Status.WAITING_FOR_ACK) {
                NetworkEventLogger.log("CONNECT_MESH_FILE: WAITING_FOR_ACK_TIMEOUT transferId=$transferId")
                state.status = FileManager.Status.FAILED
                connectionManager.cancelTransferOperations(targetPeerId, transferId)
                dbHelper.updateFileStatus(transferId, FileManager.Status.FAILED, state.progressPercentage, false, DeliveryStatus.FAILED)
                dbHelper.removeOutboxEntry(transferId)
                pendingRetriesMap.remove(transferId)
                _messagesFlow.value = _messagesFlow.value.map {
                    if (it.fileTransferId == transferId || it.id == transferId) {
                        it.copy(fileStatus = FileManager.Status.FAILED, deliveryStatus = DeliveryStatus.FAILED)
                    } else it
                }
                activeTransfers.remove(transferId)
            }
        }
        fileAckTimeoutJobs[transferId] = job
    }

    private fun cancelWaitingForAckTimeout(transferId: Long) {
        fileAckTimeoutJobs.remove(transferId)?.cancel()
    }

    fun cancelFileTransfer(transferId: Long) {
        fileSendJobs.remove(transferId)?.cancel()
        cancelWaitingForAckTimeout(transferId)
        val state = activeTransfers[transferId]
        if (state != null) {
            state.status = FileManager.Status.CANCELLED
            connectionManager.cancelTransferOperations(state.metadata.recipientId, transferId)
            cancelPendingPacketsForTransfer(state.metadata.recipientId, transferId)
            val cancelPayload = ByteBuffer.allocate(8).putLong(transferId).array()
            val cancelHeader = PacketHeader(
                packetType = PacketType.FILE_CANCEL,
                packetId = System.nanoTime(),
                sourceId = deviceIdentity.deviceId,
                destinationId = state.metadata.recipientId,
                payloadLength = cancelPayload.size.toShort(),
                ttl = 7
            )
            val session = SessionManager.getSession(state.metadata.recipientId)
            val (encCancelPayload, cancelMacTag) = if (session != null) {
                val aad = cancelHeader.constructAad()
                session.encryptPayloadWithMacAndAad(cancelPayload, aad)
            } else Pair(cancelPayload, null)

            val cancelPacket = Packet(cancelHeader, payload = encCancelPayload, macTag = cancelMacTag ?: ByteArray(16))
            val nextHop = routeTable.getNextHop(state.metadata.recipientId) ?: state.metadata.recipientId
            dispatchOrQueuePacket(nextHop, PacketEncoder.encode(cancelPacket), transferId = transferId, priority = BleOperationQueue.Priority.HIGH, isCancel = true)
            dbHelper.updateFileStatus(transferId, FileManager.Status.CANCELLED, state.progressPercentage, false, DeliveryStatus.CANCELLED)
            dbHelper.removeOutboxEntry(transferId)
            pendingRetriesMap.remove(transferId)
            _messagesFlow.value = _messagesFlow.value.map {
                if (it.fileTransferId == transferId) it.copy(fileStatus = FileManager.Status.CANCELLED, deliveryStatus = DeliveryStatus.CANCELLED) else it
            }
            activeTransfers.remove(transferId)
        }
    }

    fun sendSosAlert(customMessage: String) {
        val packetId = System.nanoTime()
        val payloadStr = "${deviceIdentity.nickname}|$customMessage"
        val payloadBytes = payloadStr.toByteArray(Charsets.UTF_8)

        val header = PacketHeader(
            packetType = PacketType.SOS,
            packetId = packetId,
            sourceId = deviceIdentity.deviceId,
            destinationId = 0L,
            payloadLength = payloadBytes.size.toShort(),
            ttl = 6
        )
        val packet = Packet(header, payload = payloadBytes)
        val encodedBytes = PacketEncoder.encode(packet)

        val mySos = SosAlert(
            packetId = packetId,
            senderId = deviceIdentity.deviceId,
            senderNickname = deviceIdentity.nickname,
            message = customMessage,
            timestamp = System.currentTimeMillis(),
            hopCount = 1,
            acknowledgedBy = emptySet(),
            isAcknowledgedByMe = true
        )
        _sosAlertsFlow.value = _sosAlertsFlow.value + mySos

        broadcastSosPacket(encodedBytes)
        relayManager.sosPacketsSent.incrementAndGet()
        NetworkEventLogger.log("CONNECT_MESH_SOS: SOS_SENT packetId=$packetId msg='$customMessage'")
    }

    fun sendSosAck(originalSosPacketId: Long, originatorPeerId: Long) {
        val ackPacket = AckManager.createAckPacket(
            originalPacketId = originalSosPacketId,
            localPeerId = deviceIdentity.deviceId,
            targetPeerId = originatorPeerId
        )
        val ackHeader = ackPacket.header.copy(packetType = PacketType.SOS_ACK)
        val typedAckPacket = ackPacket.copy(header = ackHeader)
        val encodedBytes = PacketEncoder.encode(typedAckPacket)

        val nextHop = routeTable.getNextHop(originatorPeerId) ?: originatorPeerId
        dispatchOrQueuePacket(nextHop, encodedBytes)

        _sosAlertsFlow.value = _sosAlertsFlow.value.map { alert ->
            if (alert.packetId == originalSosPacketId) {
                alert.copy(
                    acknowledgedBy = alert.acknowledgedBy + deviceIdentity.deviceId,
                    isAcknowledgedByMe = true
                )
            } else alert
        }
        NetworkEventLogger.log("CONNECT_MESH_SOS: SOS_ACK_SENT originalId=$originalSosPacketId target=0x${originatorPeerId.toString(16).uppercase()}")
    }

    fun dismissSosAlert(packetId: Long) {
        _sosAlertsFlow.value = _sosAlertsFlow.value.filter { it.packetId != packetId }
    }

    fun sendPing(targetPeerId: Long) {
        val header = PacketHeader(
            packetType = PacketType.PING,
            packetId = System.nanoTime(),
            sourceId = deviceIdentity.deviceId,
            destinationId = targetPeerId,
            payloadLength = 4
        )
        val packet = Packet(header, payload = "PING".toByteArray())
        val encoded = PacketEncoder.encode(packet)
        val nextHop = routeTable.getNextHop(targetPeerId) ?: targetPeerId
        dispatchOrQueuePacket(nextHop, encoded)
    }

    private fun decryptAndAuthenticatePacketPayload(packet: Packet): Packet? {
        if (packet.macTag.size == BleConstants.MAC_TAG_SIZE) {
            val session = SessionManager.getSession(packet.header.sourceId)
            if (session != null) {
                val dummyHeader = PacketHeader(
                    packetType = packet.header.packetType,
                    packetId = packet.header.packetId,
                    sourceId = packet.header.sourceId,
                    destinationId = packet.header.destinationId,
                    payloadLength = packet.payload.size.toShort(),
                    ttl = packet.header.ttl,
                    timestamp = packet.header.timestamp
                )
                val aad = dummyHeader.constructAad()
                val decPayload = session.decryptPayloadWithMacAndAad(packet.payload, packet.macTag, aad)
                if (decPayload != null) {
                    return packet.copy(payload = decPayload)
                } else {
                    NetworkEventLogger.log("CONNECT_MESH_CRYPTO: REJECTED_${packet.header.packetType}_AEAD_MAC_FAILED from=0x${packet.header.sourceId.toString(16).uppercase()}")
                    return null
                }
            }
        }
        return packet
    }

    private fun handleFileStart(packet: Packet) {
        val targetPacket = decryptAndAuthenticatePacketPayload(packet) ?: return
        val metadata = FileManager.decodeFileStartPayload(targetPacket.payload, targetPacket.header.sourceId, targetPacket.header.destinationId) ?: return
        val receivedDir = File(cacheDir, "received").apply { if (!exists()) mkdirs() }
        val saveFile = File(receivedDir, "recv_${metadata.fileName}")
        val state = FileManager.TransferState(
            transferId = metadata.transferId,
            metadata = metadata,
            status = FileManager.Status.RECEIVING,
            isSelf = false,
            localFilePath = saveFile.absolutePath
        )
        activeTransfers[metadata.transferId] = state

        val msg = ChatMessage(
            id = metadata.transferId,
            senderId = metadata.senderId,
            recipientId = metadata.recipientId,
            text = "📄 Receiving: ${metadata.fileName} (${metadata.fileSize / 1024} KB)",
            timestamp = System.currentTimeMillis(),
            isDelivered = false,
            deliveryStatus = DeliveryStatus.RECEIVING,
            isSelf = false,
            isFile = true,
            fileTransferId = metadata.transferId,
            fileName = metadata.fileName,
            fileSize = metadata.fileSize,
            mimeType = metadata.mimeType,
            fileStatus = FileManager.Status.RECEIVING,
            fileProgress = 0,
            localFilePath = saveFile.absolutePath
        )
        _messagesFlow.value = _messagesFlow.value + msg
        dbHelper.insertOrUpdateMessage(msg)
    }

    private fun verifyAndCompleteReceivedFile(state: FileManager.TransferState) {
        val filePath = state.localFilePath
        val receivedFile = if (filePath != null) File(filePath) else null
        if (receivedFile != null && receivedFile.exists()) {
            state.status = FileManager.Status.VERIFYING
            val actualHash = FileManager.calculateSha256ForFile(receivedFile)
            if (actualHash != null && actualHash.equals(state.metadata.sha256Hex, ignoreCase = true)) {
                state.status = FileManager.Status.COMPLETED
                dbHelper.updateFileStatus(state.transferId, FileManager.Status.COMPLETED, 100, true, DeliveryStatus.DELIVERED, receivedFile.absolutePath)
                _messagesFlow.value = _messagesFlow.value.map {
                    if (it.fileTransferId == state.transferId) it.copy(
                        fileStatus = FileManager.Status.COMPLETED,
                        fileProgress = 100,
                        isDelivered = true,
                        deliveryStatus = DeliveryStatus.DELIVERED,
                        text = "📄 Received File: ${state.metadata.fileName}"
                    ) else it
                }
                val ackPayload = FileManager.encodeFileAckPayload(state.transferId, 0x00.toByte(), emptyList())
                val ackHeader = PacketHeader(
                    packetType = PacketType.FILE_ACK,
                    packetId = System.nanoTime(),
                    sourceId = deviceIdentity.deviceId,
                    destinationId = state.metadata.senderId,
                    payloadLength = ackPayload.size.toShort(),
                    ttl = 7
                )
                val session = SessionManager.getSession(state.metadata.senderId)
                val (encAckPayload, ackMacTag) = if (session != null) {
                    val aad = ackHeader.constructAad()
                    session.encryptPayloadWithMacAndAad(ackPayload, aad)
                } else Pair(ackPayload, null)

                val ackPacket = Packet(ackHeader, payload = encAckPayload, macTag = ackMacTag ?: ByteArray(16))
                val nextHop = routeTable.getNextHop(state.metadata.senderId) ?: state.metadata.senderId
                dispatchOrQueuePacket(nextHop, PacketEncoder.encode(ackPacket), transferId = state.transferId, priority = BleOperationQueue.Priority.HIGH)
                activeTransfers.remove(state.transferId)
                NetworkEventLogger.log("CONNECT_MESH_FILE: VERIFY_SUCCESS transferId=${state.transferId}")
            } else {
                state.status = FileManager.Status.FAILED
                dbHelper.updateFileStatus(state.transferId, FileManager.Status.FAILED, state.progressPercentage, false, DeliveryStatus.FAILED)
                _messagesFlow.value = _messagesFlow.value.map {
                    if (it.fileTransferId == state.transferId) it.copy(fileStatus = FileManager.Status.FAILED, deliveryStatus = DeliveryStatus.FAILED) else it
                }
                val ackPayload = FileManager.encodeFileAckPayload(state.transferId, 0x02.toByte(), emptyList())
                val ackHeader = PacketHeader(
                    packetType = PacketType.FILE_ACK,
                    packetId = System.nanoTime(),
                    sourceId = deviceIdentity.deviceId,
                    destinationId = state.metadata.senderId,
                    payloadLength = ackPayload.size.toShort(),
                    ttl = 7
                )
                val session = SessionManager.getSession(state.metadata.senderId)
                val (encAckPayload, ackMacTag) = if (session != null) {
                    val aad = ackHeader.constructAad()
                    session.encryptPayloadWithMacAndAad(ackPayload, aad)
                } else Pair(ackPayload, null)

                val ackPacket = Packet(ackHeader, payload = encAckPayload, macTag = ackMacTag ?: ByteArray(16))
                val nextHop = routeTable.getNextHop(state.metadata.senderId) ?: state.metadata.senderId
                dispatchOrQueuePacket(nextHop, PacketEncoder.encode(ackPacket), transferId = state.transferId, priority = BleOperationQueue.Priority.HIGH)
                activeTransfers.remove(state.transferId)
                NetworkEventLogger.log("CONNECT_MESH_FILE: VERIFY_FAILED_HASH_MISMATCH transferId=${state.transferId}")
            }
        }
    }

    private fun handleFileChunk(packet: Packet) {
        val targetPacket = if (packet.macTag.size == BleConstants.MAC_TAG_SIZE) {
            val session = SessionManager.getSession(packet.header.sourceId)
            if (session != null) {
                val dummyHeader = PacketHeader(
                    packetType = PacketType.FILE_CHUNK,
                    packetId = packet.header.packetId,
                    sourceId = packet.header.sourceId,
                    destinationId = packet.header.destinationId,
                    payloadLength = packet.payload.size.toShort(),
                    ttl = packet.header.ttl,
                    timestamp = packet.header.timestamp
                )
                val aad = dummyHeader.constructAad()
                val decChunk = session.decryptPayloadWithMacAndAad(packet.payload, packet.macTag, aad)
                if (decChunk != null) packet.copy(payload = decChunk) else null
            } else packet
        } else packet

        if (targetPacket == null) return
        val decodedChunk = FileManager.decodeFileChunkPayload(targetPacket.payload) ?: return
        val state = activeTransfers[decodedChunk.transferId] ?: return
        val filePath = state.localFilePath ?: return
        val file = File(filePath)

        try {
            RandomAccessFile(file, "rw").use { raf ->
                val seekOffset = (decodedChunk.chunkIndex * state.metadata.chunkSize).toLong()
                raf.seek(seekOffset)
                raf.write(decodedChunk.chunkData)
            }
            if (state.receivedChunkIndices.add(decodedChunk.chunkIndex)) {
                state.chunksTransferred++
                state.bytesTransferred += decodedChunk.chunkData.size
            }
            val progress = state.progressPercentage
            val currentStatus = if (state.receivedChunkIndices.size < state.metadata.totalChunks) FileManager.Status.RECEIVING else FileManager.Status.VERIFYING
            state.status = currentStatus

            dbHelper.updateFileStatus(state.metadata.transferId, currentStatus, progress, false, DeliveryStatus.RECEIVING, file.absolutePath)
            _messagesFlow.value = _messagesFlow.value.map {
                if (it.fileTransferId == state.metadata.transferId) it.copy(fileProgress = progress, fileStatus = currentStatus) else it
            }

            if (state.receivedChunkIndices.size >= state.metadata.totalChunks && (state.status == FileManager.Status.RECEIVING || state.status == FileManager.Status.VERIFYING)) {
                verifyAndCompleteReceivedFile(state)
            }
        } catch (e: Exception) {
            NetworkEventLogger.log("CONNECT_MESH_FILE: WRITE_CHUNK_ERROR: ${e.message}")
        }
    }

    private fun handleFileEnd(packet: Packet) {
        val targetPacket = decryptAndAuthenticatePacketPayload(packet) ?: return
        val transferId = try { ByteBuffer.wrap(targetPacket.payload).long } catch (e: Exception) { return }
        val state = activeTransfers[transferId]
        if (state == null) {
            val existingMsg = _messagesFlow.value.find { it.fileTransferId == transferId || it.id == transferId }
            if (existingMsg != null && existingMsg.fileStatus == FileManager.Status.COMPLETED) {
                NetworkEventLogger.log("CONNECT_MESH_FILE: DUPLICATE_FILE_END_RECEIVED_RESENDING_ACK transferId=$transferId")
                val ackPayload = FileManager.encodeFileAckPayload(transferId, 0x00.toByte(), emptyList())
                val ackHeader = PacketHeader(
                    packetType = PacketType.FILE_ACK,
                    packetId = System.nanoTime(),
                    sourceId = deviceIdentity.deviceId,
                    destinationId = targetPacket.header.sourceId,
                    payloadLength = ackPayload.size.toShort(),
                    ttl = 7
                )
                val session = SessionManager.getSession(targetPacket.header.sourceId)
                val (encAckPayload, ackMacTag) = if (session != null) {
                    val aad = ackHeader.constructAad()
                    session.encryptPayloadWithMacAndAad(ackPayload, aad)
                } else Pair(ackPayload, null)

                val ackPacket = Packet(ackHeader, payload = encAckPayload, macTag = ackMacTag ?: ByteArray(16))
                val nextHop = routeTable.getNextHop(targetPacket.header.sourceId) ?: targetPacket.header.sourceId
                dispatchOrQueuePacket(nextHop, PacketEncoder.encode(ackPacket), transferId = transferId, priority = BleOperationQueue.Priority.HIGH)
            }
            return
        }
        if (state.status == FileManager.Status.RECEIVING || state.status == FileManager.Status.MISSING_CHUNKS) {
            val totalChunks = state.metadata.totalChunks
            val missing = (0 until totalChunks).filter { it !in state.receivedChunkIndices }
            if (missing.isNotEmpty()) {
                state.status = FileManager.Status.MISSING_CHUNKS
                NetworkEventLogger.log("CONNECT_MESH_FILE: FILE_MISSING_CHUNKS transferId=$transferId missingCount=${missing.size} total=$totalChunks")
                val nackPayload = FileManager.encodeFileAckPayload(transferId, 0x01.toByte(), missing)
                val ackHeader = PacketHeader(
                    packetType = PacketType.FILE_ACK,
                    packetId = System.nanoTime(),
                    sourceId = deviceIdentity.deviceId,
                    destinationId = state.metadata.senderId,
                    payloadLength = nackPayload.size.toShort(),
                    ttl = 7
                )
                val session = SessionManager.getSession(state.metadata.senderId)
                val (encAckPayload, ackMacTag) = if (session != null) {
                    val aad = ackHeader.constructAad()
                    session.encryptPayloadWithMacAndAad(nackPayload, aad)
                } else Pair(nackPayload, null)

                val ackPacket = Packet(ackHeader, payload = encAckPayload, macTag = ackMacTag ?: ByteArray(16))
                val nextHop = routeTable.getNextHop(state.metadata.senderId) ?: state.metadata.senderId
                dispatchOrQueuePacket(nextHop, PacketEncoder.encode(ackPacket), transferId = transferId, priority = BleOperationQueue.Priority.HIGH)
            } else {
                state.status = FileManager.Status.VERIFYING
                verifyAndCompleteReceivedFile(state)
            }
        }
    }

    private fun handleFileAck(packet: Packet) {
        val targetPacket = decryptAndAuthenticatePacketPayload(packet) ?: return
        val ack = FileManager.decodeFileAckPayload(targetPacket.payload) ?: return
        val transferId = ack.transferId
        val state = activeTransfers[transferId]
        if (state == null) {
            NetworkEventLogger.log("CONNECT_MESH_FILE: FILE_ACK_REJECTED_UNKNOWN_TRANSFER transferId=$transferId")
            return
        }
        val peerId = targetPacket.header.sourceId

        if (ack.statusByte == 0x00.toByte()) {
            cancelWaitingForAckTimeout(transferId)
            state.status = FileManager.Status.COMPLETED
            connectionManager.cancelTransferOperations(peerId, transferId)
            dbHelper.updateFileStatus(transferId, FileManager.Status.COMPLETED, 100, true, DeliveryStatus.DELIVERED)
            dbHelper.markOutboxDelivered(transferId)
            pendingRetriesMap.remove(transferId)

            _messagesFlow.value = _messagesFlow.value.map {
                if (it.fileTransferId == transferId || it.id == transferId) {
                    it.copy(fileStatus = FileManager.Status.COMPLETED, fileProgress = 100, isDelivered = true, deliveryStatus = DeliveryStatus.DELIVERED)
                } else it
            }
            activeTransfers.remove(transferId)
            NetworkEventLogger.log("CONNECT_MESH_FILE: FILE_ACK_PROCESSED transferId=$transferId state=COMPLETED")
        } else if (ack.statusByte == 0x01.toByte() && ack.missingIndices.isNotEmpty()) {
            NetworkEventLogger.log("CONNECT_MESH_FILE: FILE_ACK_NACK_RECEIVED transferId=$transferId missingCount=${ack.missingIndices.size}")
            val localPath = state.localFilePath ?: return
            val localFile = File(localPath)
            if (!localFile.exists()) return

            state.status = FileManager.Status.SENDING
            val missingList = ack.missingIndices.toList()
            val chunkSize = state.metadata.chunkSize
            val totalChunks = state.metadata.totalChunks
            val recipientId = state.metadata.recipientId
            val nextHop = routeTable.getNextHop(recipientId) ?: recipientId

            serviceScope.launch {
                try {
                    RandomAccessFile(localFile, "r").use { raf ->
                        for (chunkIdx in missingList) {
                            val seekOffset = (chunkIdx * chunkSize).toLong()
                            if (seekOffset >= localFile.length()) continue
                            val bytesToRead = minOf(chunkSize.toLong(), localFile.length() - seekOffset).toInt()
                            val chunkBuf = ByteArray(bytesToRead)
                            raf.seek(seekOffset)
                            raf.readFully(chunkBuf)

                            val chunkPayload = FileManager.encodeFileChunkPayload(transferId, chunkIdx, totalChunks, chunkBuf)
                            val session = SessionManager.getSession(recipientId)
                            val dummyHeader = PacketHeader(
                                packetType = PacketType.FILE_CHUNK,
                                packetId = System.nanoTime(),
                                sourceId = deviceIdentity.deviceId,
                                destinationId = recipientId,
                                payloadLength = chunkPayload.size.toShort(),
                                ttl = 7
                            )
                            val (encChunk, macTag) = if (session != null) {
                                val aad = dummyHeader.constructAad()
                                session.encryptPayloadWithMacAndAad(chunkPayload, aad)
                            } else Pair(chunkPayload, null)

                            val chunkPacket = Packet(dummyHeader, payload = encChunk, macTag = macTag ?: ByteArray(16))
                            dispatchOrQueuePacket(nextHop, PacketEncoder.encode(chunkPacket), transferId = transferId, priority = BleOperationQueue.Priority.BULK)
                            NetworkEventLogger.log("CONNECT_MESH_FILE: RETRANSMITTING_CHUNK transferId=$transferId chunk=$chunkIdx")
                            delay(15)
                        }
                    }

                    connectionManager.awaitTransferBulkDrain(recipientId, transferId, 15_000L)
                    val endPayload = ByteBuffer.allocate(8).putLong(transferId).array()
                    val endHeader = PacketHeader(
                        packetType = PacketType.FILE_END,
                        packetId = System.nanoTime(),
                        sourceId = deviceIdentity.deviceId,
                        destinationId = recipientId,
                        payloadLength = endPayload.size.toShort(),
                        ttl = 7
                    )
                    val session = SessionManager.getSession(recipientId)
                    val (encEndPayload, endMacTag) = if (session != null) {
                        val aad = endHeader.constructAad()
                        session.encryptPayloadWithMacAndAad(endPayload, aad)
                    } else Pair(endPayload, null)

                    val endPacket = Packet(endHeader, payload = encEndPayload, macTag = endMacTag ?: ByteArray(16))
                    dispatchOrQueuePacket(nextHop, PacketEncoder.encode(endPacket), transferId = transferId, priority = BleOperationQueue.Priority.HIGH)
                    state.status = FileManager.Status.WAITING_FOR_ACK
                    startWaitingForAckTimeout(recipientId, transferId)
                } catch (e: Exception) {
                    NetworkEventLogger.log("CONNECT_MESH_FILE: RETRANSMIT_ERROR: ${e.message}")
                }
            }
        } else if (ack.statusByte == 0x02.toByte()) {
            cancelWaitingForAckTimeout(transferId)
            state.status = FileManager.Status.FAILED
            connectionManager.cancelTransferOperations(peerId, transferId)
            dbHelper.updateFileStatus(transferId, FileManager.Status.FAILED, state.progressPercentage, false, DeliveryStatus.FAILED)
            dbHelper.removeOutboxEntry(transferId)
            pendingRetriesMap.remove(transferId)

            _messagesFlow.value = _messagesFlow.value.map {
                if (it.fileTransferId == transferId || it.id == transferId) {
                    it.copy(fileStatus = FileManager.Status.FAILED, deliveryStatus = DeliveryStatus.FAILED)
                } else it
            }
            activeTransfers.remove(transferId)
            NetworkEventLogger.log("CONNECT_MESH_FILE: FILE_ACK_HASH_MISMATCH transferId=$transferId state=FAILED")
        }
    }

    private fun handleFileCancel(packet: Packet) {
        val targetPacket = decryptAndAuthenticatePacketPayload(packet) ?: return
        val transferId = try { ByteBuffer.wrap(targetPacket.payload).long } catch (e: Exception) { return }
        val senderId = targetPacket.header.sourceId
        val recipientId = targetPacket.header.destinationId

        fileSendJobs.remove(transferId)?.cancel()
        cancelWaitingForAckTimeout(transferId)
        connectionManager.cancelTransferOperations(recipientId, transferId)
        connectionManager.cancelTransferOperations(senderId, transferId)
        cancelPendingPacketsForTransfer(recipientId, transferId)
        cancelPendingPacketsForTransfer(senderId, transferId)

        val state = activeTransfers[transferId]
        if (state != null) {
            state.status = FileManager.Status.CANCELLED
            val filePath = state.localFilePath
            if (filePath != null) {
                try { File(filePath).delete() } catch (e: Exception) {}
            }
        }
        dbHelper.updateFileStatus(transferId, FileManager.Status.CANCELLED, state?.progressPercentage ?: 0, false, DeliveryStatus.CANCELLED)
        dbHelper.removeOutboxEntry(transferId)
        pendingRetriesMap.remove(transferId)
        _messagesFlow.value = _messagesFlow.value.map {
            if (it.fileTransferId == transferId || it.id == transferId) it.copy(fileStatus = FileManager.Status.CANCELLED, deliveryStatus = DeliveryStatus.CANCELLED) else it
        }
        activeTransfers.remove(transferId)
        NetworkEventLogger.log("CONNECT_MESH_FILE: FILE_CANCEL_PROCESSED transferId=$transferId")
    }

    private fun handleSosAckReceived(originalPacketId: Long, ackSenderId: Long) {
        _sosAlertsFlow.value = _sosAlertsFlow.value.map { alert ->
            if (alert.packetId == originalPacketId) {
                alert.copy(acknowledgedBy = alert.acknowledgedBy + ackSenderId)
            } else alert
        }
    }

    fun broadcastNameAnnounce() {
        if (!isBleStackRunning) return
        val nameBytes = deviceIdentity.nickname.toByteArray(Charsets.UTF_8)
        val announcePacket = PacketHeader(
            packetType = PacketType.ANNOUNCE,
            packetId = System.nanoTime(),
            sourceId = deviceIdentity.deviceId,
            destinationId = 0L,
            payloadLength = nameBytes.size.toShort(),
            flags = 1.toByte()
        )
        broadcastPacketToAll(PacketEncoder.encode(Packet(announcePacket, payload = nameBytes)))
    }

    private fun processRouteAnnounce(packet: Packet) {
        val hopCount = packet.header.flags.toInt() and 0xFF
        val senderId = packet.header.sourceId
        val nextHop = senderId

        if (senderId != deviceIdentity.deviceId) {
            routeTable.updateRoute(senderId, nextHop, maxOf(1, hopCount), deviceIdentity.deviceId)
            val existing = peerManager.getPeer(senderId)
            val payloadNick = if (packet.payload.isNotEmpty()) {
                String(packet.payload, Charsets.UTF_8).trim()
            } else ""
            val nick = when {
                payloadNick.isNotBlank() -> payloadNick
                existing != null && existing.nickname.isNotBlank() && existing.nickname != "Nearby device" -> existing.nickname
                else -> "Nearby device"
            }
            val peer = PeerIdentity(
                peerId = senderId,
                nickname = nick,
                bleAddress = existing?.bleAddress ?: "",
                hopCount = maxOf(1, hopCount)
            )
            peerManager.updatePeer(peer)
            updateNotificationContent()
        }
    }

    private fun fragmentVoicePayload(transferId: Long, recipientId: Long, voiceData: ByteArray): List<VoiceFragmentData> {
        val mtu = connectionManager.getConnectionInfo(recipientId)?.mtu ?: BleConstants.DEFAULT_MTU
        // Safety calculations: MTU - 24 (Header) - 16 (FragHeader) - 16 (MAC) - 16 (Margin) = MTU - 72
        val chunkSize = (mtu - 72).coerceIn(180, 440)
        val totalFrags = ((voiceData.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        val crc = CRC32()
        crc.update(voiceData)
        val crcValue = crc.value.toInt()

        val result = mutableListOf<VoiceFragmentData>()
        for (i in 0 until totalFrags) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, voiceData.size)
            val chunk = voiceData.copyOfRange(start, end)
            result.add(VoiceFragmentData(transferId, i.toShort(), totalFrags.toShort(), crcValue, chunk))
        }
        return result
    }

    private fun constructVoiceFragmentAad(header: PacketHeader, fragHeader: FragmentHeader): ByteArray {
        val packetAad = header.constructAad()
        val fragBuf = ByteBuffer.allocate(16).apply {
            order(ByteOrder.BIG_ENDIAN)
            putLong(fragHeader.fragmentId)
            putShort(fragHeader.fragmentIndex)
            putShort(fragHeader.totalFragments)
            putInt(fragHeader.crc32)
        }.array()
        return packetAad + fragBuf
    }

    private fun broadcastPacketToAll(packetBytes: ByteArray) {
        connectionManager.getAllConnections()
            .filter { it.state == BleConnectionState.READY }
            .forEach { conn -> connectionManager.sendPacket(conn.peerId, packetBytes) }
    }

    private fun broadcastSosPacket(packetBytes: ByteArray) {
        val readyConnections = connectionManager.getAllConnections().filter { it.state == BleConnectionState.READY }
        val readyPeerIds = readyConnections.map { it.peerId }.toSet()
        readyConnections.forEach { conn ->
            connectionManager.sendPacket(conn.peerId, packetBytes, priority = BleOperationQueue.Priority.HIGH)
        }

        val nonReadyConnections = connectionManager.getAllConnections().filter { it.state != BleConnectionState.READY }
        val directPeers = peerManager.getAllPeers().filter { it.hopCount == 1 && !readyPeerIds.contains(it.peerId) }

        val targetPeerIds = (nonReadyConnections.map { it.peerId } + directPeers.map { it.peerId }).toSet()

        targetPeerIds.forEach { peerId ->
            if (!readyPeerIds.contains(peerId)) {
                dispatchOrQueuePacket(peerId, packetBytes, priority = BleOperationQueue.Priority.HIGH)
            }
        }
    }

    private fun dispatchOrQueuePacket(
        targetPeerId: Long,
        packetBytes: ByteArray,
        transferId: Long? = null,
        priority: BleOperationQueue.Priority = BleOperationQueue.Priority.HIGH,
        isCancel: Boolean = false
    ) {
        val info = connectionManager.getConnectionInfo(targetPeerId)
        val sent = if (info != null && info.state == BleConnectionState.READY) {
            connectionManager.sendPacket(targetPeerId, packetBytes, transferId, priority, isCancel)
        } else false

        if (!sent) {
            val pendingList = pendingPacketsMap.computeIfAbsent(targetPeerId) { mutableListOf() }
            pendingList.add(PendingPacketOp(packetBytes, transferId, priority, isCancel))
            val peer = peerManager.getPeer(targetPeerId)
            if (peer != null && peer.bleAddress.isNotBlank() && (info == null || info.state == BleConnectionState.DISCONNECTED)) {
                connectionManager.connectToPeer(targetPeerId, peer.bleAddress)
            }
        }
    }

    fun startBleStack() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            NetworkEventLogger.log("CONNECT_MESH_BLE: Cannot start BLE stack - Bluetooth Adapter is NULL or DISABLED")
            return
        }

        if (!isBleStackRunning) {
            val serverStarted = gattServerManager.startGattServer()
            NetworkEventLogger.log("CONNECT_MESH_BLE: GATT Server started = $serverStarted")
            advertiser.startAdvertising()
            scanner.startScanning()
            isBleStackRunning = true
            NetworkEventLogger.log("CONNECT_MESH_BLE: BLE Stack STARTED successfully")
            updateNotificationContent()
        }
    }

    fun stopBleStack() {
        if (isBleStackRunning) {
            advertiser.stopAdvertising()
            scanner.stopScanning()
            gattServerManager.stopGattServer()
            connectionManager.disconnectAll()
            isBleStackRunning = false
            NetworkEventLogger.log("CONNECT_MESH_BLE: BLE Stack STOPPED")
        }
    }

    private fun startPeriodicRouteAnnouncements() {
        routeAnnounceJob?.cancel()
        routeAnnounceJob = serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                if (isBleStackRunning) {
                    broadcastNameAnnounce()
                }
            }
        }
    }

    private fun startAckRetryTaskScheduler() {
        ackRetryJob?.cancel()
        ackRetryJob = serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                val now = System.currentTimeMillis()
                pendingRetriesMap.forEach { (packetId, retryTask) ->
                    if (now - retryTask.lastAttemptTime >= RETRY_INTERVAL_MS) {
                        if (retryTask.attemptCount < MAX_RETRY_ATTEMPTS) {
                            retryTask.attemptCount++
                            retryTask.lastAttemptTime = now
                            dbHelper.updateOutboxAttempt(packetId, retryTask.attemptCount, now, OutboxStatus.ATTEMPTING)
                            val recipientId = retryTask.message.recipientId
                            val nextHop = routeTable.getNextHop(recipientId) ?: recipientId

                            if (retryTask.message.isFile || retryTask.message.fileTransferId != 0L) {
                                val transferId = if (retryTask.message.fileTransferId != 0L) retryTask.message.fileTransferId else retryTask.message.id
                                val state = activeTransfers[transferId]
                                if (state != null && state.status == FileManager.Status.WAITING_FOR_ACK) {
                                    val endPayload = ByteBuffer.allocate(8).putLong(transferId).array()
                                    val endHeader = PacketHeader(
                                        packetType = PacketType.FILE_END,
                                        packetId = System.nanoTime(),
                                        sourceId = deviceIdentity.deviceId,
                                        destinationId = recipientId,
                                        payloadLength = endPayload.size.toShort(),
                                        ttl = 7
                                    )
                                    val session = SessionManager.getSession(recipientId)
                                    val (encEndPayload, endMacTag) = if (session != null) {
                                        val aad = endHeader.constructAad()
                                        session.encryptPayloadWithMacAndAad(endPayload, aad)
                                    } else Pair(endPayload, null)

                                    val endPacket = Packet(endHeader, payload = encEndPayload, macTag = endMacTag ?: ByteArray(16))
                                    dispatchOrQueuePacket(nextHop, PacketEncoder.encode(endPacket), transferId = transferId, priority = BleOperationQueue.Priority.HIGH)
                                    NetworkEventLogger.log("CONNECT_MESH_FILE: RETRY_FILE_END transferId=$transferId target=0x${recipientId.toString(16).uppercase()}")
                                } else if (state == null || state.status == FileManager.Status.COMPLETED || state.status == FileManager.Status.FAILED || state.status == FileManager.Status.CANCELLED) {
                                    pendingRetriesMap.remove(packetId)
                                }
                            } else if (retryTask.message.isVoice) {
                                val voiceBytes = retryTask.message.voiceData
                                if (voiceBytes != null) {
                                    val frags = fragmentVoicePayload(retryTask.message.id, recipientId, voiceBytes)
                                    val session = SessionManager.getSession(recipientId)
                                    frags.forEach { frag ->
                                        val fragHeader = FragmentHeader(frag.transferId, frag.index, frag.total, frag.crc32)
                                        val header = PacketHeader(
                                            packetType = PacketType.VOICE_FRAGMENT,
                                            packetId = System.nanoTime(),
                                            sourceId = deviceIdentity.deviceId,
                                            destinationId = recipientId,
                                            payloadLength = frag.data.size.toShort(),
                                            ttl = 7
                                        )
                                        val (encData, macTag) = if (session != null) {
                                            val aad = constructVoiceFragmentAad(header, fragHeader)
                                            session.encryptPayloadWithMacAndAad(frag.data, aad)
                                        } else Pair(frag.data, null)

                                        val packet = Packet(header, fragHeader, encData, macTag ?: ByteArray(16))
                                        dispatchOrQueuePacket(
                                            nextHop,
                                            PacketEncoder.encode(packet),
                                            transferId = retryTask.message.id,
                                            priority = BleOperationQueue.Priority.BULK
                                        )
                                    }
                                }
                            } else {
                                val textBytes = retryTask.message.text.toByteArray(Charsets.UTF_8)
                                val session = SessionManager.getSession(recipientId)
                                val (encBytes, macTag) = if (session != null) {
                                    val dummyHeader = PacketHeader(
                                        packetType = PacketType.MESSAGE,
                                        packetId = retryTask.message.id,
                                        sourceId = deviceIdentity.deviceId,
                                        destinationId = recipientId,
                                        payloadLength = textBytes.size.toShort(),
                                        ttl = 7
                                    )
                                    session.encryptPayloadWithMacAndAad(textBytes, dummyHeader.constructAad())
                                } else Pair(textBytes, null)

                                val packet = Packet(
                                    header = PacketHeader(
                                        packetType = PacketType.MESSAGE,
                                        packetId = retryTask.message.id,
                                        sourceId = deviceIdentity.deviceId,
                                        destinationId = recipientId,
                                        payloadLength = encBytes.size.toShort(),
                                        ttl = 7
                                    ),
                                    payload = encBytes,
                                    macTag = macTag ?: ByteArray(16)
                                )
                                dispatchOrQueuePacket(nextHop, PacketEncoder.encode(packet), priority = BleOperationQueue.Priority.HIGH)
                            }
                        } else {
                            val recipientId = retryTask.message.recipientId
                            pendingRetriesMap.remove(packetId)

                            if (retryTask.message.isVoice) {
                                connectionManager.cancelTransferOperations(recipientId, packetId)
                            }

                            dbHelper.updateDeliveryStatus(packetId, false, DeliveryStatus.FAILED)
                            dbHelper.markOutboxFailed(packetId)
                            _messagesFlow.value = _messagesFlow.value.map {
                                if (it.id == packetId) it.copy(deliveryStatus = DeliveryStatus.FAILED) else it
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val channelId = "connect_mesh_channel"
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.connectmesh.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stopIntent = Intent(this, MeshForegroundService::class.java).apply {
            action = ACTION_STOP_MESH
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("CONNECT-MESH")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Mesh", stopPendingIntent)
            .build()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "connect_mesh_channel"
        val channelName = "Connect-Mesh Foreground Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = buildNotification("Searching for nearby devices")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1001, notification)
        }
    }

    fun updateNotificationContent() {
        if (!isBleStackRunning) return
        val count = peerManager.getAllPeers().size
        val text = if (count == 0) "Searching for nearby devices" else "$count nearby device(s) connected"
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1001, notification)
    }

    fun stopMeshService() {
        NetworkEventLogger.log("CONNECT_MESH_SERVICE: User explicitly requested STOP MESH")
        stopBleStack()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    fun getPendingOutboxEntries(): List<com.connectmesh.db.OutboxEntry> {
        return if (::dbHelper.isInitialized) dbHelper.getPendingOutboxEntries() else emptyList()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleStack()
        routeAnnounceJob?.cancel()
        ackRetryJob?.cancel()
        serviceScope.cancel()
        unregisterReceiver(bluetoothReceiver)
    }
}
