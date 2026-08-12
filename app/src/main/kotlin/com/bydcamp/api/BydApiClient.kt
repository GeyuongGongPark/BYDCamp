package com.bydcamp.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class BydApiClient(context: Context, private val config: BydConfig) {

    private val codec = BangcleCodec(context)
    private val mutex = Mutex()

    var userId: String? = null
        private set
    var signToken: String? = null
        private set
    var encryToken: String? = null
        private set

    private var accountImeiMD5 = "00000000000000000000000000000000"
    private var storedUsername: String? = null
    private var storedPassword: String? = null
    private var isRelogging = false

    var onSessionUpdated: ((String, String, String) -> Unit)? = null
    var onSessionExpired: (() -> Unit)? = null

    val isLoggedIn: Boolean get() = !signToken.isNullOrEmpty()

    private val deviceProfile = mapOf(
        "ostype"          to "and",
        "imei"            to "BANGCLE01234",
        "mac"             to "00:00:00:00:00:00",
        "model"           to "POCO F1",
        "sdk"             to "35",
        "mod"             to "Xiaomi",
        "mobileBrand"     to "XIAOMI",
        "mobileModel"     to "POCO F1",
        "deviceType"      to "0",
        "networkType"     to "wifi",
        "osType"          to "15",
        "osVersion"       to "35",
        "appInnerVersion" to "322",
        "appVersion"      to "3.2.2"
    )

    fun setCredentials(username: String, password: String) {
        storedUsername = username
        storedPassword = password
        if (username.isNotEmpty()) {
            accountImeiMD5 = CryptoUtils.md5Hex(username)
        }
    }

    fun restoreSession(userId: String, signToken: String, encryToken: String) {
        this.userId = userId
        this.signToken = signToken
        this.encryToken = encryToken
    }

    // MARK: - JSON helpers

    private fun toSortedJson(map: List<Pair<String, Any?>>): String {
        val parts = map.map { (key, value) ->
            val valStr = when (value) {
                is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                null -> "null"
                else -> "$value"
            }
            "\"$key\":$valStr"
        }
        return "{${parts.joinToString(",")}}"
    }

    private fun buildInnerBase(vin: String? = null, requestSerial: String? = null): MutableList<Pair<String, Any?>> {
        val map = mutableListOf<Pair<String, Any?>>(
            "deviceType"   to (deviceProfile["deviceType"] ?: ""),
            "imeiMD5"      to accountImeiMD5,
            "networkType"  to (deviceProfile["networkType"] ?: ""),
            "random"       to CryptoUtils.md5Hex("${Math.random()}").substring(0, 16),
            "timeStamp"    to "${System.currentTimeMillis()}",
            "version"      to (deviceProfile["appInnerVersion"] ?: "")
        )
        vin?.let { map.add("vin" to it) }
        requestSerial?.let { map.add("requestSerial" to it) }
        return map
    }

    // MARK: - HTTP

    private fun postJson(endpoint: String, bodyJson: String): String {
        val url = URL(config.baseURL + endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "okhttp/4.12.0")
        conn.connectTimeout = 120_000
        conn.readTimeout = 120_000
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(bodyJson) }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }

    // MARK: - Authenticated request

    private suspend fun postTokenSecure(
        endpoint: String,
        innerMap: List<Pair<String, Any?>>,
        vin: String?
    ): Map<String, Any?> {
        val uid     = userId     ?: throw BydError.NotLoggedIn
        val signTok = signToken  ?: throw BydError.NotLoggedIn
        val encTok  = encryToken ?: throw BydError.NotLoggedIn

        val nowMs = System.currentTimeMillis()
        val reqTimestamp = "$nowMs"
        val innerJson = toSortedJson(innerMap)
        val encryData = CryptoUtils.aesEncryptHex(innerJson, CryptoUtils.md5Hex(encTok))

        val signFields = mutableMapOf<String, String>()
        for ((k, v) in innerMap) signFields[k] = "${v ?: "null"}"
        signFields["countryCode"]  = config.countryCode
        signFields["identifier"]   = uid
        signFields["imeiMD5"]      = accountImeiMD5
        signFields["language"]     = config.language
        signFields["reqTimestamp"] = reqTimestamp
        val sign = CryptoUtils.sha1Mixed(
            CryptoUtils.buildSignString(signFields, CryptoUtils.md5Hex(signTok))
        )

        val outerMap = mutableListOf(
            "countryCode"  to (config.countryCode as Any?),
            "encryData"    to encryData,
            "identifier"   to uid,
            "imeiMD5"      to accountImeiMD5,
            "language"     to config.language,
            "reqTimestamp" to reqTimestamp,
            "sign"         to sign,
            "ostype"       to deviceProfile["ostype"],
            "imei"         to deviceProfile["imei"],
            "mac"          to deviceProfile["mac"],
            "model"        to deviceProfile["model"],
            "sdk"          to deviceProfile["sdk"],
            "mod"          to deviceProfile["mod"],
            "serviceTime"  to reqTimestamp
        )
        val outerJsonNoCheck = toSortedJson(outerMap)
        val checkcode = CryptoUtils.computeCheckcode(outerJsonNoCheck)
        outerMap.add("checkcode" to checkcode)
        val finalOuterJson = toSortedJson(outerMap)

        val encodedRequest = codec.encodeEnvelope(finalOuterJson)
        val bodyJson = JSONObject().put("request", encodedRequest).toString()

        val responseText = postJson(endpoint, bodyJson)
        val bodyObj = JSONObject(responseText)
        val encodedResponse = bodyObj.getString("response")

        var decoded = codec.decodeEnvelope(encodedResponse).trim()
        if (decoded.startsWith("F{") || decoded.startsWith("F[")) decoded = decoded.substring(1)

        val outerResp = JSONObject(decoded)
        val resCode = outerResp.optString("code", "0")

        if (resCode != "0") {
            if (resCode in listOf("1002", "1005", "1010")) {
                return silentReLogin(endpoint, innerMap, vin)
            }
            throw BydError.ServerError(outerResp.optString("message", "Unknown"), resCode)
        }

        val respondData = outerResp.optString("respondData", "")
        if (respondData.isEmpty()) return outerResp.toMap()

        val innerText = try {
            CryptoUtils.aesDecryptUTF8(respondData, CryptoUtils.md5Hex(encTok))
        } catch (e: Exception) {
            Log.w("BydApiClient", "응답 복호화 실패 — 재로그인 후 재시도")
            return silentReLogin(endpoint, innerMap, vin)
        }

        if (innerText.startsWith("[")) {
            val arr = JSONArray(innerText)
            val list = (0 until arr.length()).map { arr.getJSONObject(it).toMap() }
            return mapOf("list" to list)
        }
        return JSONObject(innerText).toMap()
    }

    private suspend fun silentReLogin(
        endpoint: String,
        innerMap: List<Pair<String, Any?>>,
        vin: String?
    ): Map<String, Any?> {
        if (isRelogging) throw BydError.SessionExpired
        val user = storedUsername
        val pwd  = storedPassword
        if (user.isNullOrEmpty()) {
            onSessionExpired?.invoke()
            throw BydError.SessionExpired
        }
        isRelogging = true
        try {
            login(user, pwd ?: "")
            return postTokenSecure(endpoint, innerMap, vin)
        } finally {
            isRelogging = false
        }
    }

    // MARK: - Login

    suspend fun login(username: String, password: String): String = mutex.withLock {
        val derivedImeiMD5 = CryptoUtils.md5Hex(username)
        accountImeiMD5 = derivedImeiMD5
        val nowMs = System.currentTimeMillis()
        val reqTimestamp = "$nowMs"
        val randomHex = CryptoUtils.md5Hex("${Math.random()}")

        val innerMap = listOf(
            "agreeStatus"     to "0",
            "agreementType"   to "[1,2]",
            "appInnerVersion" to (deviceProfile["appInnerVersion"] ?: ""),
            "appVersion"      to (deviceProfile["appVersion"] ?: ""),
            "deviceName"      to "${deviceProfile["mobileBrand"] ?: ""}${deviceProfile["mobileModel"] ?: ""}",
            "deviceType"      to (deviceProfile["deviceType"] ?: ""),
            "imeiMD5"         to derivedImeiMD5,
            "isAuto"          to "1",
            "mobileBrand"     to (deviceProfile["mobileBrand"] ?: ""),
            "mobileModel"     to (deviceProfile["mobileModel"] ?: ""),
            "networkType"     to (deviceProfile["networkType"] ?: ""),
            "osType"          to (deviceProfile["osType"] ?: ""),
            "osVersion"       to (deviceProfile["osVersion"] ?: ""),
            "random"          to randomHex,
            "softType"        to "0",
            "timeStamp"       to reqTimestamp,
            "timeZone"        to config.timeZone
        )

        val innerJson = toSortedJson(innerMap)
        val loginKey  = CryptoUtils.pwdLoginKey(password)
        val encryData = CryptoUtils.aesEncryptHex(innerJson, loginKey)

        val signFields = mutableMapOf<String, String>()
        for ((k, v) in innerMap) signFields[k] = "${v ?: "null"}"
        signFields["appName"]        = "pyBYD+0.1.dev2+ge0a1f5e27"
        signFields["countryCode"]    = config.countryCode
        signFields["functionType"]   = "pwdLogin"
        signFields["identifier"]     = username
        signFields["identifierType"] = "0"
        signFields["language"]       = config.language
        signFields["reqTimestamp"]   = reqTimestamp
        val sign = CryptoUtils.sha1Mixed(
            CryptoUtils.buildSignString(signFields, CryptoUtils.md5Hex(password))
        )

        val outerMap = mutableListOf(
            "appName"       to ("pyBYD+0.1.dev2+ge0a1f5e27" as Any?),
            "countryCode"   to config.countryCode,
            "encryData"     to encryData,
            "functionType"  to "pwdLogin",
            "identifier"    to username,
            "identifierType" to "0",
            "imeiMD5"       to derivedImeiMD5,
            "isAuto"        to "1",
            "language"      to config.language,
            "reqTimestamp"  to reqTimestamp,
            "sign"          to sign,
            "signKey"       to password,
            "ostype"        to deviceProfile["ostype"],
            "imei"          to deviceProfile["imei"],
            "mac"           to deviceProfile["mac"],
            "model"         to deviceProfile["model"],
            "sdk"           to deviceProfile["sdk"],
            "mod"           to deviceProfile["mod"],
            "serviceTime"   to reqTimestamp
        )
        val outerJsonNoCheck = toSortedJson(outerMap)
        val checkcode = CryptoUtils.computeCheckcode(outerJsonNoCheck)
        outerMap.add("checkcode" to checkcode)
        val finalOuterJson = toSortedJson(outerMap)

        val encodedRequest = codec.encodeEnvelope(finalOuterJson)
        val bodyJson = JSONObject().put("request", encodedRequest).toString()

        val responseText = postJson("/app/account/login", bodyJson)
        val bodyObj = JSONObject(responseText)
        val encodedResponse = bodyObj.getString("response")

        var decoded = codec.decodeEnvelope(encodedResponse).trim()
        if (decoded.startsWith("F{")) decoded = decoded.substring(1)

        val outerResp = JSONObject(decoded)
        val resCode = outerResp.optString("code", "0")
        if (resCode != "0") {
            throw BydError.ServerError(outerResp.optString("message", "Login failed"), resCode)
        }

        val respondData = outerResp.getString("respondData")
        val innerText   = CryptoUtils.aesDecryptUTF8(respondData, loginKey)
        val innerResp   = JSONObject(innerText)
        val token       = innerResp.getJSONObject("token")

        val uid   = token.getString("userId")
        val stok  = token.getString("signToken")
        val etok  = token.getString("encryToken")

        userId     = uid
        signToken  = stok
        encryToken = etok
        storedUsername = username
        storedPassword = password

        onSessionUpdated?.invoke(uid, stok, etok)
        return@withLock uid
    }

    // MARK: - Vehicle List

    suspend fun fetchVehicleList(): List<String> {
        val result = postTokenSecure("/app/account/getAllListByUserId", buildInnerBase(), null)
        @Suppress("UNCHECKED_CAST")
        val list = result["list"] as? List<Map<String, Any?>> ?: emptyList()
        return list.mapNotNull { it["vin"] as? String }
    }

    // MARK: - Vehicle Status

    data class VehicleStatus(
        val soc: Int = 0,
        val elecPercent: Int = 0,
        val drivingRange: Double = 0.0,
        val isLocked: Boolean = false,
        val interiorTemperature: Double = 0.0,
        val isClimateOn: Boolean = false
    )

    suspend fun fetchVehicleStatus(vin: String): VehicleStatus {
        val inner = buildInnerBase(vin = vin).also {
            it.add("energyType" to "0")
            it.add("tboxVersion" to "3")
        }

        val triggerResult = postTokenSecure("/vehicleInfo/vehicle/vehicleRealTimeRequest", inner, vin)
        val serial = triggerResult["requestSerial"] as? String

        delay(1_500)

        var result: Map<String, Any?>? = null
        for (attempt in 1..5) {
            if (attempt > 1) delay(2_000)
            val pollInner = buildInnerBase(vin = vin, requestSerial = serial).also {
                it.add("energyType" to "0")
                it.add("tboxVersion" to "3")
            }
            try {
                result = postTokenSecure("/vehicleInfo/vehicle/vehicleRealTimeResult", pollInner, vin)
                break
            } catch (e: BydError.ServerError) {
                if (e.code == "3002") {
                    Log.d("BydApiClient", "차량 상태 조회 처리 중 (시도 $attempt/5)")
                    if (attempt == 5) throw BydError.ControlTimeout
                } else throw e
            }
        }
        val r = result ?: throw BydError.InvalidResponse

        val soc = (r["soc"] as? Int) ?: (r["elecPercent"] as? Int) ?: 0
        val range = (r["mileageEV"] as? Double) ?: (r["enduranceMileage"] as? Double) ?: 0.0

        val lf = r["leftFrontDoorLock"]  as? Int ?: 0
        val rf = r["rightFrontDoorLock"] as? Int ?: 0
        val lr = r["leftRearDoorLock"]   as? Int ?: 0
        val rr = r["rightRearDoorLock"]  as? Int ?: 0
        val isLocked = (lf != 0 || rf != 0 || lr != 0 || rr != 0) && (lf == 2 && rf == 2 && lr == 2 && rr == 2)

        val rawTemp = (r["interiorTemp"] as? Double) ?: (r["tempInCar"] as? Double) ?: 0.0
        val interiorTemp = if (rawTemp > -40 && rawTemp < 100) rawTemp else 0.0

        return VehicleStatus(soc = soc, drivingRange = range, isLocked = isLocked, interiorTemperature = interiorTemp)
    }

    // MARK: - Climate

    suspend fun startClimate(vin: String, temp: Double, durationMinutes: Int = 30, pin: String): Boolean {
        val params = mapOf(
            "mainSettingTemp"       to celsiusToScale(temp),
            "mainSettingTempNew"    to temp * 2.0,
            "copilotSettingTemp"    to celsiusToScale(temp),
            "copilotSettingTempNew" to temp * 2.0,
            "cycleMode"             to 2,
            "timeSpan"              to minutesToTimeSpan(durationMinutes),
            "remoteMode"            to 4,
            "airAccuracy"           to 1,
            "airConditioningMode"   to 1
        )
        return sendRemoteControl(vin, "OPENAIR", params, pin)
    }

    suspend fun stopClimate(vin: String, pin: String): Boolean =
        sendRemoteControl(vin, "CLOSEAIR", null, pin)

    // MARK: - Remote Control

    private suspend fun pollControlResult(
        vin: String,
        commandType: String,
        serial: String,
        pin: String,
        attempt: Int
    ): Boolean {
        delay(2_000)
        val inner = buildInnerBase(vin = vin, requestSerial = serial).also {
            it.add("commandType" to commandType)
            it.add("commandPwd" to CryptoUtils.md5Hex(pin))
        }
        val result = postTokenSecure("/control/remoteControlResult", inner, vin)
        val controlState = result["controlState"] as? Int ?: 0
        val res = result["res"] as? Int ?: 0

        if (controlState == 1 || res == 2) return true
        if (controlState == 2 || res > 2) {
            throw BydError.ControlFailed(result["message"] as? String ?: result["msg"] as? String ?: "실패")
        }
        if (attempt >= 10) throw BydError.ControlTimeout
        return pollControlResult(vin, commandType, serial, pin, attempt + 1)
    }

    private suspend fun sendRemoteControl(
        vin: String,
        commandType: String,
        params: Map<String, Any?>?,
        pin: String,
        fireAndForget: Boolean = false
    ): Boolean {
        val inner = buildInnerBase(vin = vin).also {
            it.add("commandType" to commandType)
            it.add("commandPwd" to CryptoUtils.md5Hex(pin))
            if (params != null) {
                val paramsJson = JSONObject(params).toString()
                it.add("controlParamsMap" to paramsJson)
            }
        }
        val result = postTokenSecure("/control/remoteControl", inner, vin)
        val controlState = result["controlState"] as? Int ?: 0
        val res = result["res"] as? Int ?: 0
        val serial = result["requestSerial"] as? String ?: ""

        if (controlState == 1 || res == 2) return true
        if (controlState == 2 || res > 2) {
            throw BydError.ControlFailed(result["message"] as? String ?: "실패")
        }
        if (fireAndForget) return true
        if (serial.isEmpty()) return false
        return pollControlResult(vin, commandType, serial, pin, 1)
    }

    // MARK: - Helpers

    private fun celsiusToScale(temp: Double): Int = maxOf(1, minOf(17, (temp - 14.0).toInt()))

    private fun minutesToTimeSpan(minutes: Int): Int = when (minutes) {
        10 -> 1; 15 -> 2; 20 -> 3; 25 -> 4; 30 -> 5; else -> 2
    }
}

// MARK: - Error Types

sealed class BydError(message: String) : Exception(message) {
    object NotLoggedIn   : BydError("로그인이 필요합니다")
    object SessionExpired : BydError("세션이 만료되었습니다")
    object InvalidResponse : BydError("잘못된 응답 형식")
    object ControlTimeout : BydError("제어 시간 초과")
    class ServerError(val msg: String, val code: String) : BydError("서버 오류: $msg ($code)")
    class ControlFailed(val detail: String) : BydError("제어 실패: $detail")
    class NetworkError(cause: Exception) : BydError("네트워크 오류: ${cause.localizedMessage}")
}

// JSONObject to Map helper
fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    for (key in keys()) {
        map[key] = when (val v = get(key)) {
            is JSONObject -> v.toMap()
            is JSONArray  -> (0 until v.length()).map { v.get(it) }
            JSONObject.NULL -> null
            else -> v
        }
    }
    return map
}
