package com.connectmesh.broadcast

enum class BroadcastType {
    ANNOUNCEMENT,
    ALERT,
    EMERGENCY
}

enum class BroadcastPriority {
    NORMAL,
    HIGH,
    CRITICAL
}

data class CampusEnrollmentDetails(
    val campusScope: String,
    val authorityIdHex: String,
    val authorityPublicKeyBase64: String
) {
    fun toCopyableString(): String {
        return "$campusScope|$authorityIdHex|$authorityPublicKeyBase64"
    }

    companion object {
        fun parse(input: String): CampusEnrollmentDetails? {
            val parts = input.trim().split("|")
            if (parts.size == 3) {
                val scope = parts[0].trim()
                val idHex = parts[1].trim()
                val pubKey = parts[2].trim()
                if (scope.isNotBlank() && idHex.isNotBlank() && pubKey.isNotBlank()) {
                    return CampusEnrollmentDetails(
                        campusScope = scope,
                        authorityIdHex = idHex,
                        authorityPublicKeyBase64 = pubKey
                    )
                }
            }
            return null
        }
    }
}

