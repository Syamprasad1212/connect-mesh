package com.connectmesh.protocol

enum class PacketType(val code: Byte) {
    ANNOUNCE(0x01.toByte()),
    MESSAGE(0x02.toByte()),
    ACK(0x03.toByte()),
    FRAGMENT(0x04.toByte()),
    VOICE_FRAGMENT(0x05.toByte()),
    PING(0x06.toByte()),
    PONG(0x07.toByte()),
    SOS(0x08.toByte()),
    SOS_ACK(0x09.toByte()),
    FILE_START(0x0A.toByte()),
    FILE_CHUNK(0x0B.toByte()),
    FILE_END(0x0C.toByte()),
    FILE_ACK(0x0D.toByte()),
    FILE_CANCEL(0x0E.toByte()),
    AUTH_REQUEST(0x0F.toByte()),
    AUTH_RESPONSE(0x10.toByte()),
    SESSION_INIT(0x11.toByte()),
    SESSION_FINISH(0x12.toByte()),
    CLASSROOM_MSG(0x13.toByte()),
    CLASSROOM_KEY_SYNC(0x14.toByte()),
    COLLEGE_BROADCAST(0x15.toByte());

    companion object {
        fun fromCode(code: Byte): PacketType? {
            return values().find { it.code == code }
        }
    }
}
