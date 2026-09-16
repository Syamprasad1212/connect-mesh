package com.connectmesh.classroom

data class ClassroomMessage(
    val id: Long,
    val groupId: String,
    val senderId: Long,
    val text: String,
    val timestamp: Long,
    val groupKeyVersion: Int,
    val isSelf: Boolean = false,
    val deliveryStatus: String = "DELIVERED"
)
