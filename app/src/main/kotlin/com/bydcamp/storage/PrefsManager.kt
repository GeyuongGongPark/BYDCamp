package com.bydcamp.storage

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("bydcamp_prefs", Context.MODE_PRIVATE)

    // 계정 정보
    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(v) = prefs.edit().putString("username", v).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(v) = prefs.edit().putString("password", v).apply()

    var pin: String
        get() = prefs.getString("pin", "") ?: ""
        set(v) = prefs.edit().putString("pin", v).apply()

    var vin: String
        get() = prefs.getString("vin", "") ?: ""
        set(v) = prefs.edit().putString("vin", v).apply()

    var region: String
        get() = prefs.getString("region", "KR") ?: "KR"
        set(v) = prefs.edit().putString("region", v).apply()

    // 캠핑 설정
    var targetTemp: Float
        get() = prefs.getFloat("targetTemp", 24.0f)
        set(v) = prefs.edit().putFloat("targetTemp", v).apply()

    var stopBatteryPct: Int
        get() = prefs.getInt("stopBatteryPct", 30)
        set(v) = prefs.edit().putInt("stopBatteryPct", v).apply()

    var maxHours: Int
        get() = prefs.getInt("maxHours", 8)
        set(v) = prefs.edit().putInt("maxHours", v).apply()

    // 세션 토큰
    var userId: String
        get() = prefs.getString("userId", "") ?: ""
        set(v) = prefs.edit().putString("userId", v).apply()

    var signToken: String
        get() = prefs.getString("signToken", "") ?: ""
        set(v) = prefs.edit().putString("signToken", v).apply()

    var encryToken: String
        get() = prefs.getString("encryToken", "") ?: ""
        set(v) = prefs.edit().putString("encryToken", v).apply()

    fun clearSession() {
        prefs.edit()
            .remove("userId")
            .remove("signToken")
            .remove("encryToken")
            .apply()
    }

    fun isConfigured(): Boolean =
        username.isNotEmpty() && password.isNotEmpty() && pin.isNotEmpty() && vin.isNotEmpty()
}
