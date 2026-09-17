package com.connectmesh.classroom

/**
 * Data model for an offline Classroom Group (e.g. "CSE-A").
 * Cryptographically bound to institution scope and creator identity.
 */
data class ClassroomGroup(
    val groupId: String,              // Stable unique identifier, e.g. "GRP-CSEA7K4P"
    val groupName: String,            // Visible Name, e.g. "CSE-A"
    val institutionScope: String,     // e.g. "COLLEGE:CAMPUS_01" or "CLASSROOM:CSE-A"
    val createdByConnectMeshId: Long, // Teacher/Admin 64-bit Connect-Mesh ID
    val createdAt: Long,              // Creation timestamp (ms)
    var groupKeyVersion: Int = 1,     // Active Group Key Version
    var activeGroupKeyHex: String,    // 256-Bit Hex Symmetric Key
    val joinCode: String = ""         // Human-friendly short join code, e.g. "CSEA7K4P"
) {
    val displayJoinCode: String
        get() = if (joinCode.isNotBlank()) joinCode else groupId.removePrefix("GRP-")
}

