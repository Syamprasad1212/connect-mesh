package com.connectmesh.auth

enum class UserRole {
    USER,
    TEACHER,
    ADMIN,
    EMERGENCY_AUTHORITY;

    fun hasPrivilegeOf(other: UserRole): Boolean {
        if (this == other) return true
        return when (this) {
            ADMIN -> other == TEACHER || other == USER
            EMERGENCY_AUTHORITY -> other == USER
            TEACHER -> other == USER
            USER -> false
        }
    }
}
