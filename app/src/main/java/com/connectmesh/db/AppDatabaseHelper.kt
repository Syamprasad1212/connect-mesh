package com.connectmesh.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.connectmesh.classroom.ClassroomGroup
import com.connectmesh.file.FileManager
import com.connectmesh.service.MeshForegroundService.ChatMessage
import com.connectmesh.service.MeshForegroundService.DeliveryStatus
import java.io.File

enum class OutboxStatus {
    PENDING,
    ATTEMPTING,
    SENT,
    DELIVERED,
    FAILED,
    EXPIRED
}

data class OutboxEntry(
    val outboxId: Long = 0L,
    val messageId: Long,
    val senderId: Long,
    val recipientId: Long,
    val messageType: String,
    val payload: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var attemptCount: Int = 0,
    var lastAttemptTime: Long = 0L,
    var status: OutboxStatus = OutboxStatus.PENDING,
    val expiryTime: Long = 0L,
    val extraMeta: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OutboxEntry
        return messageId == other.messageId
    }

    override fun hashCode(): Int {
        return messageId.hashCode()
    }
}

class AppDatabaseHelper(private val context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "connect_mesh.db"
        private const val DATABASE_VERSION = 3

        private const val TABLE_MESSAGES = "messages"
        private const val COLUMN_ID = "id"
        private const val COLUMN_SENDER_ID = "sender_id"
        private const val COLUMN_RECIPIENT_ID = "recipient_id"
        private const val COLUMN_TEXT = "text"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_IS_DELIVERED = "is_delivered"
        private const val COLUMN_DELIVERY_STATUS = "delivery_status"
        private const val COLUMN_IS_SELF = "is_self"
        private const val COLUMN_IS_VOICE = "is_voice"
        private const val COLUMN_IS_FILE = "is_file"
        private const val COLUMN_FILE_TRANSFER_ID = "file_transfer_id"
        private const val COLUMN_FILE_NAME = "file_name"
        private const val COLUMN_FILE_SIZE = "file_size"
        private const val COLUMN_MIME_TYPE = "mime_type"
        private const val COLUMN_FILE_STATUS = "file_status"
        private const val COLUMN_FILE_PROGRESS = "file_progress"
        private const val COLUMN_LOCAL_FILE_PATH = "local_file_path"

        private const val TABLE_OUTBOX = "pending_outbox"
        private const val COLUMN_OUTBOX_ID = "outbox_id"
        private const val COLUMN_OUTBOX_MSG_ID = "message_id"
        private const val COLUMN_OUTBOX_SENDER_ID = "sender_id"
        private const val COLUMN_OUTBOX_RECIPIENT_ID = "recipient_id"
        private const val COLUMN_OUTBOX_MSG_TYPE = "message_type"
        private const val COLUMN_OUTBOX_PAYLOAD = "payload"
        private const val COLUMN_OUTBOX_CREATED_AT = "created_at"
        private const val COLUMN_OUTBOX_ATTEMPT_COUNT = "attempt_count"
        private const val COLUMN_OUTBOX_LAST_ATTEMPT = "last_attempt_time"
        private const val COLUMN_OUTBOX_STATUS = "status"
        private const val COLUMN_OUTBOX_EXPIRY = "expiry_time"
        private const val COLUMN_OUTBOX_EXTRA_META = "extra_meta"

        private const val TABLE_CLASSROOMS = "classrooms"
        private const val COLUMN_GROUP_ID = "group_id"
        private const val COLUMN_GROUP_NAME = "group_name"
        private const val COLUMN_SCOPE = "institution_scope"
        private const val COLUMN_CREATOR_ID = "created_by"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_KEY_VERSION = "group_key_version"
        private const val COLUMN_ACTIVE_KEY_HEX = "active_group_key_hex"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createMessagesTable = """
            CREATE TABLE $TABLE_MESSAGES (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_SENDER_ID INTEGER NOT NULL,
                $COLUMN_RECIPIENT_ID INTEGER NOT NULL,
                $COLUMN_TEXT TEXT,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_IS_DELIVERED INTEGER NOT NULL,
                $COLUMN_DELIVERY_STATUS TEXT NOT NULL,
                $COLUMN_IS_SELF INTEGER NOT NULL,
                $COLUMN_IS_VOICE INTEGER NOT NULL,
                $COLUMN_IS_FILE INTEGER NOT NULL,
                $COLUMN_FILE_TRANSFER_ID INTEGER NOT NULL,
                $COLUMN_FILE_NAME TEXT,
                $COLUMN_FILE_SIZE INTEGER NOT NULL,
                $COLUMN_MIME_TYPE TEXT,
                $COLUMN_FILE_STATUS TEXT NOT NULL,
                $COLUMN_FILE_PROGRESS INTEGER NOT NULL,
                $COLUMN_LOCAL_FILE_PATH TEXT
            )
        """.trimIndent()
        db.execSQL(createMessagesTable)
        createOutboxTable(db)
        createClassroomsTable(db)
    }

    private fun createOutboxTable(db: SQLiteDatabase) {
        val createOutboxTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_OUTBOX (
                $COLUMN_OUTBOX_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_OUTBOX_MSG_ID INTEGER UNIQUE NOT NULL,
                $COLUMN_OUTBOX_SENDER_ID INTEGER NOT NULL,
                $COLUMN_OUTBOX_RECIPIENT_ID INTEGER NOT NULL,
                $COLUMN_OUTBOX_MSG_TYPE TEXT NOT NULL,
                $COLUMN_OUTBOX_PAYLOAD BLOB,
                $COLUMN_OUTBOX_CREATED_AT INTEGER NOT NULL,
                $COLUMN_OUTBOX_ATTEMPT_COUNT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_OUTBOX_LAST_ATTEMPT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_OUTBOX_STATUS TEXT NOT NULL,
                $COLUMN_OUTBOX_EXPIRY INTEGER NOT NULL DEFAULT 0,
                $COLUMN_OUTBOX_EXTRA_META TEXT
            )
        """.trimIndent()
        db.execSQL(createOutboxTable)
    }

    private fun createClassroomsTable(db: SQLiteDatabase) {
        val createClassroomsTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_CLASSROOMS (
                $COLUMN_GROUP_ID TEXT PRIMARY KEY,
                $COLUMN_GROUP_NAME TEXT NOT NULL,
                $COLUMN_SCOPE TEXT NOT NULL,
                $COLUMN_CREATOR_ID INTEGER NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_KEY_VERSION INTEGER NOT NULL,
                $COLUMN_ACTIVE_KEY_HEX TEXT NOT NULL
            )
        """.trimIndent()
        db.execSQL(createClassroomsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createOutboxTable(db)
        }
        if (oldVersion < 3) {
            createClassroomsTable(db)
        }
    }

    fun insertOrUpdateMessage(msg: ChatMessage) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_ID, msg.id)
            put(COLUMN_SENDER_ID, msg.senderId)
            put(COLUMN_RECIPIENT_ID, msg.recipientId)
            put(COLUMN_TEXT, msg.text)
            put(COLUMN_TIMESTAMP, msg.timestamp)
            put(COLUMN_IS_DELIVERED, if (msg.isDelivered) 1 else 0)
            put(COLUMN_DELIVERY_STATUS, msg.deliveryStatus.name)
            put(COLUMN_IS_SELF, if (msg.isSelf) 1 else 0)
            put(COLUMN_IS_VOICE, if (msg.isVoice) 1 else 0)
            put(COLUMN_IS_FILE, if (msg.isFile) 1 else 0)
            put(COLUMN_FILE_TRANSFER_ID, msg.fileTransferId)
            put(COLUMN_FILE_NAME, msg.fileName)
            put(COLUMN_FILE_SIZE, msg.fileSize)
            put(COLUMN_MIME_TYPE, msg.mimeType)
            put(COLUMN_FILE_STATUS, msg.fileStatus.name)
            put(COLUMN_FILE_PROGRESS, msg.fileProgress)
            put(COLUMN_LOCAL_FILE_PATH, msg.localFilePath)
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllMessages(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val db = readableDatabase
        val cursor = db.query(TABLE_MESSAGES, null, null, null, null, null, "$COLUMN_TIMESTAMP ASC")

        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(COLUMN_ID))
                val senderId = c.getLong(c.getColumnIndexOrThrow(COLUMN_SENDER_ID))
                val recipientId = c.getLong(c.getColumnIndexOrThrow(COLUMN_RECIPIENT_ID))
                val text = c.getString(c.getColumnIndexOrThrow(COLUMN_TEXT)) ?: ""
                val timestamp = c.getLong(c.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                val isDelivered = c.getInt(c.getColumnIndexOrThrow(COLUMN_IS_DELIVERED)) == 1
                val deliveryStatusStr = c.getString(c.getColumnIndexOrThrow(COLUMN_DELIVERY_STATUS)) ?: "SENDING"
                val deliveryStatus = try {
                    DeliveryStatus.valueOf(deliveryStatusStr)
                } catch (e: Exception) {
                    if (isDelivered) DeliveryStatus.DELIVERED else DeliveryStatus.SENDING
                }
                val isSelf = c.getInt(c.getColumnIndexOrThrow(COLUMN_IS_SELF)) == 1
                val isVoice = c.getInt(c.getColumnIndexOrThrow(COLUMN_IS_VOICE)) == 1
                val isFile = c.getInt(c.getColumnIndexOrThrow(COLUMN_IS_FILE)) == 1
                val fileTransferId = c.getLong(c.getColumnIndexOrThrow(COLUMN_FILE_TRANSFER_ID))
                val fileName = c.getString(c.getColumnIndexOrThrow(COLUMN_FILE_NAME)) ?: ""
                val fileSize = c.getLong(c.getColumnIndexOrThrow(COLUMN_FILE_SIZE))
                val mimeType = c.getString(c.getColumnIndexOrThrow(COLUMN_MIME_TYPE)) ?: ""
                val fileStatusStr = c.getString(c.getColumnIndexOrThrow(COLUMN_FILE_STATUS)) ?: "PREPARING"
                val fileStatus = try {
                    FileManager.Status.valueOf(fileStatusStr)
                } catch (e: Exception) {
                    FileManager.Status.PREPARING
                }
                val fileProgress = c.getInt(c.getColumnIndexOrThrow(COLUMN_FILE_PROGRESS))
                val localFilePath = c.getString(c.getColumnIndexOrThrow(COLUMN_LOCAL_FILE_PATH))

                // Load voice data from file if applicable
                var voiceData: ByteArray? = null
                if (isVoice && !localFilePath.isNullOrBlank()) {
                    val file = File(localFilePath)
                    if (file.exists()) {
                        voiceData = try { file.readBytes() } catch (e: Exception) { null }
                    }
                }

                messages.add(
                    ChatMessage(
                        id = id,
                        senderId = senderId,
                        recipientId = recipientId,
                        text = text,
                        timestamp = timestamp,
                        isDelivered = isDelivered,
                        deliveryStatus = deliveryStatus,
                        isSelf = isSelf,
                        isVoice = isVoice,
                        voiceData = voiceData,
                        isFile = isFile,
                        fileTransferId = fileTransferId,
                        fileName = fileName,
                        fileSize = fileSize,
                        mimeType = mimeType,
                        fileStatus = fileStatus,
                        fileProgress = fileProgress,
                        localFilePath = localFilePath
                    )
                )
            }
        }
        return messages
    }

    fun updateDeliveryStatus(messageId: Long, isDelivered: Boolean, status: DeliveryStatus) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_IS_DELIVERED, if (isDelivered) 1 else 0)
            put(COLUMN_DELIVERY_STATUS, status.name)
        }
        db.update(TABLE_MESSAGES, values, "$COLUMN_ID = ?", arrayOf(messageId.toString()))
    }

    fun updateFileStatus(fileTransferId: Long, fileStatus: FileManager.Status, progress: Int, isDelivered: Boolean, deliveryStatus: DeliveryStatus, localPath: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_FILE_STATUS, fileStatus.name)
            put(COLUMN_FILE_PROGRESS, progress)
            put(COLUMN_IS_DELIVERED, if (isDelivered) 1 else 0)
            put(COLUMN_DELIVERY_STATUS, deliveryStatus.name)
            if (!localPath.isNullOrBlank()) {
                put(COLUMN_LOCAL_FILE_PATH, localPath)
            }
        }
        db.update(TABLE_MESSAGES, values, "$COLUMN_FILE_TRANSFER_ID = ?", arrayOf(fileTransferId.toString()))
    }

    // --- PERSISTENT STORE-AND-FORWARD OUTBOX OPERATIONS ---

    fun saveToOutbox(entry: OutboxEntry) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_OUTBOX_MSG_ID, entry.messageId)
            put(COLUMN_OUTBOX_SENDER_ID, entry.senderId)
            put(COLUMN_OUTBOX_RECIPIENT_ID, entry.recipientId)
            put(COLUMN_OUTBOX_MSG_TYPE, entry.messageType)
            put(COLUMN_OUTBOX_PAYLOAD, entry.payload)
            put(COLUMN_OUTBOX_CREATED_AT, entry.createdAt)
            put(COLUMN_OUTBOX_ATTEMPT_COUNT, entry.attemptCount)
            put(COLUMN_OUTBOX_LAST_ATTEMPT, entry.lastAttemptTime)
            put(COLUMN_OUTBOX_STATUS, entry.status.name)
            put(COLUMN_OUTBOX_EXPIRY, entry.expiryTime)
            put(COLUMN_OUTBOX_EXTRA_META, entry.extraMeta)
        }
        db.insertWithOnConflict(TABLE_OUTBOX, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getPendingOutboxEntries(): List<OutboxEntry> {
        val list = mutableListOf<OutboxEntry>()
        val db = readableDatabase
        val now = System.currentTimeMillis()
        val query = """
            SELECT * FROM $TABLE_OUTBOX 
            WHERE $COLUMN_OUTBOX_STATUS IN ('PENDING', 'ATTEMPTING', 'SENT')
            AND ($COLUMN_OUTBOX_EXPIRY = 0 OR $COLUMN_OUTBOX_EXPIRY > ?)
            ORDER BY $COLUMN_OUTBOX_CREATED_AT ASC
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(now.toString()))
        cursor.use { c ->
            while (c.moveToNext()) {
                val outboxId = c.getLong(c.getColumnIndexOrThrow(COLUMN_OUTBOX_ID))
                val messageId = c.getLong(c.getColumnIndexOrThrow(COLUMN_OUTBOX_MSG_ID))
                val senderId = c.getLong(c.getColumnIndexOrThrow(COLUMN_OUTBOX_SENDER_ID))
                val recipientId = c.getLong(c.getColumnIndexOrThrow(COLUMN_OUTBOX_RECIPIENT_ID))
                val messageType = c.getString(c.getColumnIndexOrThrow(COLUMN_OUTBOX_MSG_TYPE)) ?: "TEXT"
                val payload = c.getBlob(c.getColumnIndexOrThrow(COLUMN_OUTBOX_PAYLOAD))
                val createdAt = c.getLong(c.getColumnIndexOrThrow(COLUMN_OUTBOX_CREATED_AT))
                val attemptCount = c.getInt(c.getColumnIndexOrThrow(COLUMN_OUTBOX_ATTEMPT_COUNT))
                val lastAttemptTime = c.getLong(c.getColumnIndexOrThrow(COLUMN_OUTBOX_LAST_ATTEMPT))
                val statusStr = c.getString(c.getColumnIndexOrThrow(COLUMN_OUTBOX_STATUS)) ?: "PENDING"
                val status = try { OutboxStatus.valueOf(statusStr) } catch (e: Exception) { OutboxStatus.PENDING }
                val expiryTime = c.getLong(c.getColumnIndexOrThrow(COLUMN_OUTBOX_EXPIRY))
                val extraMeta = c.getString(c.getColumnIndexOrThrow(COLUMN_OUTBOX_EXTRA_META))

                list.add(
                    OutboxEntry(
                        outboxId = outboxId,
                        messageId = messageId,
                        senderId = senderId,
                        recipientId = recipientId,
                        messageType = messageType,
                        payload = payload,
                        createdAt = createdAt,
                        attemptCount = attemptCount,
                        lastAttemptTime = lastAttemptTime,
                        status = status,
                        expiryTime = expiryTime,
                        extraMeta = extraMeta
                    )
                )
            }
        }
        return list
    }

    fun updateOutboxAttempt(messageId: Long, attemptCount: Int, lastAttemptTime: Long, status: OutboxStatus) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_OUTBOX_ATTEMPT_COUNT, attemptCount)
            put(COLUMN_OUTBOX_LAST_ATTEMPT, lastAttemptTime)
            put(COLUMN_OUTBOX_STATUS, status.name)
        }
        db.update(TABLE_OUTBOX, values, "$COLUMN_OUTBOX_MSG_ID = ?", arrayOf(messageId.toString()))
    }

    fun markOutboxDelivered(messageId: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_OUTBOX_STATUS, OutboxStatus.DELIVERED.name)
        }
        db.update(TABLE_OUTBOX, values, "$COLUMN_OUTBOX_MSG_ID = ?", arrayOf(messageId.toString()))
        // Clean up completed outbox entry to keep outbox lean
        db.delete(TABLE_OUTBOX, "$COLUMN_OUTBOX_MSG_ID = ?", arrayOf(messageId.toString()))
    }

    fun markOutboxFailed(messageId: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_OUTBOX_STATUS, OutboxStatus.FAILED.name)
        }
        db.update(TABLE_OUTBOX, values, "$COLUMN_OUTBOX_MSG_ID = ?", arrayOf(messageId.toString()))
    }

    fun removeOutboxEntry(messageId: Long) {
        val db = writableDatabase
        db.delete(TABLE_OUTBOX, "$COLUMN_OUTBOX_MSG_ID = ?", arrayOf(messageId.toString()))
    }

    fun cleanExpiredOutboxEntries(currentTime: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.delete(
            TABLE_OUTBOX,
            "($COLUMN_OUTBOX_EXPIRY > 0 AND $COLUMN_OUTBOX_EXPIRY <= ?) OR $COLUMN_OUTBOX_STATUS IN ('DELIVERED', 'FAILED', 'EXPIRED')",
            arrayOf(currentTime.toString())
        )
    }

    // --- PERSISTENT CLASSROOM OPERATIONS ---

    fun saveClassroom(group: ClassroomGroup) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_GROUP_ID, group.groupId)
            put(COLUMN_GROUP_NAME, group.groupName)
            put(COLUMN_SCOPE, group.institutionScope)
            put(COLUMN_CREATOR_ID, group.createdByConnectMeshId)
            put(COLUMN_CREATED_AT, group.createdAt)
            put(COLUMN_KEY_VERSION, group.groupKeyVersion)
            put(COLUMN_ACTIVE_KEY_HEX, group.activeGroupKeyHex)
        }
        db.insertWithOnConflict(TABLE_CLASSROOMS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllClassrooms(): List<ClassroomGroup> {
        val classrooms = mutableListOf<ClassroomGroup>()
        val db = readableDatabase
        val cursor = db.query(TABLE_CLASSROOMS, null, null, null, null, null, "$COLUMN_CREATED_AT ASC")

        cursor.use { c ->
            while (c.moveToNext()) {
                val groupId = c.getString(c.getColumnIndexOrThrow(COLUMN_GROUP_ID))
                val groupName = c.getString(c.getColumnIndexOrThrow(COLUMN_GROUP_NAME))
                val scope = c.getString(c.getColumnIndexOrThrow(COLUMN_SCOPE))
                val creatorId = c.getLong(c.getColumnIndexOrThrow(COLUMN_CREATOR_ID))
                val createdAt = c.getLong(c.getColumnIndexOrThrow(COLUMN_CREATED_AT))
                val keyVersion = c.getInt(c.getColumnIndexOrThrow(COLUMN_KEY_VERSION))
                val keyHex = c.getString(c.getColumnIndexOrThrow(COLUMN_ACTIVE_KEY_HEX))

                classrooms.add(
                    ClassroomGroup(
                        groupId = groupId,
                        groupName = groupName,
                        institutionScope = scope,
                        createdByConnectMeshId = creatorId,
                        createdAt = createdAt,
                        groupKeyVersion = keyVersion,
                        activeGroupKeyHex = keyHex
                    )
                )
            }
        }
        return classrooms
    }
}
