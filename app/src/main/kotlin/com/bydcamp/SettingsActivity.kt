package com.bydcamp

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bydcamp.api.BydApiClient
import com.bydcamp.api.BydConfig
import com.bydcamp.api.BydError
import com.bydcamp.databinding.ActivitySettingsBinding
import com.bydcamp.storage.PrefsManager
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsManager
    private var vinList: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        loadPrefsToUI()

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnFetchVin.setOnClickListener { doFetchVin() }
        binding.btnSave.setOnClickListener { doSave() }
    }

    private fun loadPrefsToUI() {
        binding.etEmail.setText(prefs.username)
        binding.etPassword.setText(prefs.password)
        binding.etPin.setText(prefs.pin)
        binding.etTargetTemp.setText(prefs.targetTemp.toString())
        binding.etStopBattery.setText(prefs.stopBatteryPct.toString())
        binding.etMaxHours.setText(prefs.maxHours.toString())

        val regions = listOf("KR", "EU", "JP", "SG", "AU", "BR", "MX", "NO", "IN", "ID", "VN")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, regions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerRegion.adapter = adapter
        val idx = regions.indexOf(prefs.region)
        if (idx >= 0) binding.spinnerRegion.setSelection(idx)
    }

    private fun doLogin() {
        val email = binding.etEmail.text.toString().trim()
        val pw    = binding.etPassword.text.toString()
        if (email.isEmpty() || pw.isEmpty()) {
            Toast.makeText(this, "이메일과 비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false

        val region = binding.spinnerRegion.selectedItem.toString()
        val config = BydConfig.fromRegion(region)
        val client = BydApiClient(this, config)

        lifecycleScope.launch {
            try {
                client.login(email, pw)
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "로그인 성공!", Toast.LENGTH_SHORT).show()
                    binding.tvLoginStatus.text = "로그인됨 ✓"
                    prefs.username   = email
                    prefs.password   = pw
                    prefs.region     = region
                    prefs.userId     = client.userId ?: ""
                    prefs.signToken  = client.signToken ?: ""
                    prefs.encryToken = client.encryToken ?: ""
                }
                doFetchVinWithClient(client)
            } catch (e: BydError.ServerError) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "로그인 실패: ${e.msg} (${e.code})", Toast.LENGTH_LONG).show()
                    binding.tvLoginStatus.text = "로그인 실패"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "오류: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.tvLoginStatus.text = "오류"
                }
            } finally {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                }
            }
        }
    }

    private fun doFetchVin() {
        val region = binding.spinnerRegion.selectedItem.toString()
        val config = BydConfig.fromRegion(region)
        val client = BydApiClient(this, config)

        if (prefs.userId.isNotEmpty()) {
            client.restoreSession(prefs.userId, prefs.signToken, prefs.encryToken)
            client.setCredentials(prefs.username, prefs.password)
        } else {
            Toast.makeText(this, "먼저 로그인하세요", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch { doFetchVinWithClient(client) }
    }

    private suspend fun doFetchVinWithClient(client: BydApiClient) {
        runOnUiThread { binding.progressBar.visibility = View.VISIBLE }
        try {
            val vins = client.fetchVehicleList()
            vinList = vins
            runOnUiThread {
                if (vins.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "차량을 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val adapter = ArrayAdapter(this@SettingsActivity,
                    android.R.layout.simple_spinner_item, vins)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerVin.adapter = adapter

                val savedIdx = vins.indexOf(prefs.vin)
                if (savedIdx >= 0) binding.spinnerVin.setSelection(savedIdx)
                Toast.makeText(this@SettingsActivity, "차량 ${vins.size}대 조회됨", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this@SettingsActivity, "VIN 조회 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            runOnUiThread { binding.progressBar.visibility = View.GONE }
        }
    }

    private fun doSave() {
        val pin     = binding.etPin.text.toString()
        val tempStr = binding.etTargetTemp.text.toString()
        val batStr  = binding.etStopBattery.text.toString()
        val hrStr   = binding.etMaxHours.text.toString()

        if (pin.isEmpty()) {
            Toast.makeText(this, "PIN을 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.pin           = pin
        prefs.targetTemp    = tempStr.toFloatOrNull() ?: 24.0f
        prefs.stopBatteryPct = batStr.toIntOrNull() ?: 30
        prefs.maxHours      = hrStr.toIntOrNull() ?: 8
        prefs.region        = binding.spinnerRegion.selectedItem.toString()

        if (vinList.isNotEmpty() && binding.spinnerVin.selectedItemPosition >= 0) {
            prefs.vin = vinList[binding.spinnerVin.selectedItemPosition]
        }

        Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        finish()
    }
}
