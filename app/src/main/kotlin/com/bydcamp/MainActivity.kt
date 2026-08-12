package com.bydcamp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bydcamp.databinding.ActivityMainBinding
import com.bydcamp.service.CampingService
import com.bydcamp.storage.PrefsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private var isRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val batteryPct = intent.getIntExtra(CampingService.EXTRA_BATTERY_PCT, 0)
            val elapsedMs  = intent.getLongExtra(CampingService.EXTRA_ELAPSED_MS, 0)
            val running    = intent.getBooleanExtra(CampingService.EXTRA_IS_RUNNING, false)
            val msg        = intent.getStringExtra(CampingService.EXTRA_STATUS_MSG) ?: ""

            isRunning = running
            updateUI(batteryPct, elapsedMs, msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)

        binding.btnStart.setOnClickListener {
            if (!prefs.isConfigured()) {
                Toast.makeText(this, "설정에서 계정/차량 정보를 먼저 입력하세요", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            startCampingService()
        }

        binding.btnStop.setOnClickListener {
            stopCampingService()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshConfigDisplay()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver, IntentFilter(CampingService.BROADCAST_STATUS)
        )
        refreshConfigDisplay()
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
        super.onPause()
    }

    private fun startCampingService() {
        val intent = Intent(this, CampingService::class.java)
        startForegroundService(intent)
        isRunning = true
        updateButtonState()
        binding.tvStatus.text = "시작 중..."
    }

    private fun stopCampingService() {
        val intent = Intent(this, CampingService::class.java).apply {
            action = CampingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUI(batteryPct: Int, elapsedMs: Long, msg: String) {
        binding.tvBatteryPct.text = "${batteryPct}%"
        binding.tvElapsedTime.text = formatElapsed(elapsedMs)
        binding.tvStatus.text = msg
        updateButtonState()
    }

    private fun updateButtonState() {
        binding.btnStart.isEnabled = !isRunning
        binding.btnStop.isEnabled  = isRunning
        binding.btnStart.alpha = if (isRunning) 0.4f else 1.0f
        binding.btnStop.alpha  = if (isRunning) 1.0f else 0.4f
    }

    private fun refreshConfigDisplay() {
        binding.tvTargetTemp.text = "${prefs.targetTemp}°C"
        binding.tvStopBattery.text = "종료 배터리: ${prefs.stopBatteryPct}%"
        binding.tvMaxHours.text = "최대: ${prefs.maxHours}시간"
        binding.tvVin.text = if (prefs.vin.isNotEmpty()) "VIN: ${prefs.vin}" else "VIN 미설정"
    }

    private fun formatElapsed(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
