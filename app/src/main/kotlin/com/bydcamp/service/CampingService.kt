package com.bydcamp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bydcamp.MainActivity
import com.bydcamp.R
import com.bydcamp.api.BydApiClient
import com.bydcamp.api.BydConfig
import com.bydcamp.storage.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CampingService : LifecycleService() {

    companion object {
        const val ACTION_STOP         = "com.bydcamp.STOP_CAMPING"
        const val BROADCAST_STATUS    = "com.bydcamp.STATUS_UPDATE"
        const val EXTRA_BATTERY_PCT   = "batteryPct"
        const val EXTRA_ELAPSED_MS    = "elapsedMs"
        const val EXTRA_IS_RUNNING    = "isRunning"
        const val EXTRA_STATUS_MSG    = "statusMsg"

        private const val CHANNEL_ID        = "camping_channel"
        private const val NOTIFICATION_ID   = 1001
        private const val CLIMATE_INTERVAL  = 28 * 60 * 1000L  // 28분
        private const val STATUS_INTERVAL   = 5  * 60 * 1000L  // 5분
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PrefsManager
    private lateinit var client: BydApiClient

    private var startTimeMs = 0L
    private var lastBatteryPct = 100

    private val climateRunnable = object : Runnable {
        override fun run() {
            scope.launch { triggerClimate() }
            handler.postDelayed(this, CLIMATE_INTERVAL)
        }
    }

    private val statusRunnable = object : Runnable {
        override fun run() {
            scope.launch { checkStatus() }
            handler.postDelayed(this, STATUS_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        val config = BydConfig.fromRegion(prefs.region)
        client = BydApiClient(this, config)

        if (prefs.userId.isNotEmpty()) {
            client.restoreSession(prefs.userId, prefs.signToken, prefs.encryToken)
        }
        client.setCredentials(prefs.username, prefs.password)
        client.onSessionUpdated = { uid, sign, encry ->
            prefs.userId     = uid
            prefs.signToken  = sign
            prefs.encryToken = encry
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopCamping()
            return START_NOT_STICKY
        }

        startTimeMs = System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, buildNotification("캠핑 모드 시작 중..."))

        scope.launch {
            try {
                if (!client.isLoggedIn) {
                    client.login(prefs.username, prefs.password)
                }
                triggerClimate()
                broadcastStatus("에어컨 시작됨")
            } catch (e: Exception) {
                Log.e("CampingService", "초기 에어컨 시작 실패", e)
                broadcastStatus("시작 실패: ${e.message}")
                stopSelf()
                return@launch
            }
        }

        handler.postDelayed(climateRunnable, CLIMATE_INTERVAL)
        handler.postDelayed(statusRunnable, STATUS_INTERVAL)

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(climateRunnable)
        handler.removeCallbacks(statusRunnable)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun triggerClimate() {
        try {
            client.startClimate(
                vin = prefs.vin,
                temp = prefs.targetTemp.toDouble(),
                durationMinutes = 30,
                pin = prefs.pin
            )
            updateNotification("에어컨 실행 중 — 배터리 ${lastBatteryPct}%")
            Log.i("CampingService", "에어컨 재시작 성공")
        } catch (e: Exception) {
            Log.e("CampingService", "에어컨 재시작 실패", e)
        }
    }

    private suspend fun checkStatus() {
        try {
            val status = client.fetchVehicleStatus(prefs.vin)
            lastBatteryPct = status.soc
            val elapsedMs = System.currentTimeMillis() - startTimeMs
            val elapsedHours = elapsedMs / 3_600_000.0

            updateNotification("배터리 ${status.soc}% — ${formatElapsed(elapsedMs)}")
            broadcastStatus("배터리 ${status.soc}%", status.soc, elapsedMs)

            // 종료 조건 확인
            when {
                status.soc < prefs.stopBatteryPct -> {
                    Log.i("CampingService", "배터리 부족 (${status.soc}% < ${prefs.stopBatteryPct}%) — 종료")
                    stopCamping()
                }
                elapsedHours >= prefs.maxHours -> {
                    Log.i("CampingService", "최대 시간 초과 — 종료")
                    stopCamping()
                }
            }
        } catch (e: Exception) {
            Log.e("CampingService", "상태 조회 실패", e)
        }
    }

    private fun stopCamping() {
        scope.launch {
            try {
                client.stopClimate(prefs.vin, prefs.pin)
            } catch (e: Exception) {
                Log.e("CampingService", "에어컨 종료 실패", e)
            } finally {
                broadcastStatus("캠핑 모드 종료", isRunning = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun broadcastStatus(
        msg: String,
        batteryPct: Int = lastBatteryPct,
        elapsedMs: Long = System.currentTimeMillis() - startTimeMs,
        isRunning: Boolean = true
    ) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_BATTERY_PCT, batteryPct)
            putExtra(EXTRA_ELAPSED_MS, elapsedMs)
            putExtra(EXTRA_IS_RUNNING, isRunning)
            putExtra(EXTRA_STATUS_MSG, msg)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "캠핑 모드", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "BYD 캠핑 모드 실행 중"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, CampingService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPending = PendingIntent.getActivity(this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BYD 캠핑 모드")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPending)
            .addAction(android.R.drawable.ic_media_pause, "종료", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun formatElapsed(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }
}
