package com.connectmesh.identity

import android.content.Context
import android.content.SharedPreferences

class DeviceIdentity(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("connect_mesh_identity", Context.MODE_PRIVATE)

    val cryptoIdentityManager = CryptoIdentityManager.getInstance()

    val deviceId: Long
    var nickname: String
        get() = prefs.getString("nickname", "User_${deviceId.toString(16).takeLast(4)}") ?: "Peer"
        set(value) {
            prefs.edit().putString("nickname", value).apply()
        }

    init {
        var id = prefs.getLong("device_id", 0L)
        if (id == 0L) {
            id = cryptoIdentityManager.connectMeshId
            prefs.edit().putLong("device_id", id).apply()
        }
        deviceId = id
    }
}
