package com.visionex.app

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.app.PendingIntent
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.LocationServices
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.visionex.app.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleVisionExManager
    private val prefs by lazy { getSharedPreferences("visionex_prefs", MODE_PRIVATE) }
    private val location by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var alerts = 0
    private var emergencyThreshold = 3
    private var pendingSmsNumber: String? = null
    private var pendingSmsMessage: String? = null

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (resultCode) {
                Activity.RESULT_OK -> {
                    Toast.makeText(this@MainActivity, R.string.sms_sent, Toast.LENGTH_SHORT).show()
                    addEvent("SMS accepted by Android/carrier")
                }
                SmsManager.RESULT_ERROR_NO_SERVICE -> smsFailure("No cellular service")
                SmsManager.RESULT_ERROR_RADIO_OFF -> smsFailure("Cellular radio is off")
                SmsManager.RESULT_ERROR_NULL_PDU -> smsFailure("Invalid SMS data")
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> smsFailure("Carrier or SMS service rejected the message")
                else -> smsFailure("SMS failed with code " + resultCode)
            }
        }
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!hasBle()) {
            Snackbar.make(binding.root, getString(R.string.nearby_permission_needed), Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = BleVisionExManager(
            this,
            { runOnUiThread { binding.connectionText.text = it } },
            { runOnUiThread { handleMessage(it.trim()) } }
        )

        setupTabs()
        restoreSettings()
        setupSettingsControls()
        setupActions()
        updateAlerts()
        renderHistory()
        ContextCompat.registerReceiver(
            this,
            smsSentReceiver,
            android.content.IntentFilter(packageName + ".SMS_SENT"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        requestPermissionsNow()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_home))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_history))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_settings))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun showTab(position: Int) {
        binding.homeSection.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.historySection.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.settingsSection.visibility = if (position == 2) View.VISIBLE else View.GONE
    }

    private fun restoreSettings() {
        binding.contactInput.setText(prefs.getString("emergency_contact", ""))
        binding.autoSmsSwitch.isChecked = prefs.getBoolean("auto_sms", false)
        binding.vibrationSwitch.isChecked = prefs.getBoolean("vibration_enabled", true)
        binding.buzzerSwitch.isChecked = prefs.getBoolean("buzzer_enabled", true)

        val strength = prefs.getInt("vibration_strength", 70)
        binding.vibrationSeekBar.progress = strength
        binding.vibrationStrengthText.text = getString(R.string.vibration_strength, strength)

        emergencyThreshold = prefs.getInt("emergency_threshold", 3).coerceIn(1, 5)
        binding.emergencyThresholdSeekBar.progress = emergencyThreshold - 1
        binding.emergencyThresholdText.text = getString(R.string.emergency_threshold, emergencyThreshold)

        alerts = prefs.getInt("alerts", 0)

        when (prefs.getString("theme", "system")) {
            "light" -> binding.themeLight.isChecked = true
            "dark" -> binding.themeDark.isChecked = true
            else -> binding.themeSystem.isChecked = true
        }
    }

    private fun setupSettingsControls() {
        binding.vibrationSeekBar.setOnSeekBarChangeListener(simpleSeekListener { value ->
            binding.vibrationStrengthText.text = getString(R.string.vibration_strength, value)
        })

        binding.emergencyThresholdSeekBar.setOnSeekBarChangeListener(simpleSeekListener { value ->
            emergencyThreshold = value + 1
            prefs.edit { putInt("emergency_threshold", emergencyThreshold) }
            binding.emergencyThresholdText.text = getString(R.string.emergency_threshold, emergencyThreshold)
        })

        binding.applyVibrationButton.setOnClickListener {
            val strength = binding.vibrationSeekBar.progress
            prefs.edit { putInt("vibration_strength", strength) }
            ble.sendCommand("VIBRATION:" + strength)
            Toast.makeText(this, R.string.settings_sent, Toast.LENGTH_SHORT).show()
        }

        binding.vibrationSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit { putBoolean("vibration_enabled", enabled) }
            ble.sendCommand("VIBRATION_ENABLED:" + if (enabled) "1" else "0")
        }

        binding.buzzerSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit { putBoolean("buzzer_enabled", enabled) }
            ble.sendCommand("BUZZER_ENABLED:" + if (enabled) "1" else "0")
        }

        binding.autoSmsSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit { putBoolean("auto_sms", enabled) }
        }

        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.themeLight -> "light"
                R.id.themeDark -> "dark"
                else -> "system"
            }
            prefs.edit { putString("theme", theme) }
            applyTheme(theme)
        }
    }

    private fun simpleSeekListener(onChanged: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChanged(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun setupActions() {
        binding.connectButton.setOnClickListener {
            if (hasBle()) ble.scanAndConnect() else requestPermissionsNow()
        }
        binding.ackButton.setOnClickListener {
            alerts = 0
            prefs.edit { putInt("alerts", 0) }
            setNormal()
            updateAlerts()
            ble.sendCommand("ACK")
            addEvent("Alert acknowledged")
        }
        binding.simulateButton.setOnClickListener { handleMessage("DROWSY") }
        binding.clearHistoryButton.setOnClickListener {
            prefs.edit { remove("history") }
            renderHistory()
        }
        binding.testSmsButton.setOnClickListener {
            saveContact()
            sendEmergency(true)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(smsSentReceiver) } catch (_: Exception) {}
        ble.disconnect()
        super.onDestroy()
    }

    private fun applySavedTheme() {
        val saved = getSharedPreferences("visionex_prefs", MODE_PRIVATE).getString("theme", "system") ?: "system"
        applyTheme(saved)
    }

    private fun applyTheme(theme: String) {
        val mode = when (theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun requestPermissionsNow() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        launcher.launch(p.toTypedArray())
    }

    private fun hasBle(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
             ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    private fun handleMessage(raw: String) {
        val m = raw.uppercase(Locale.US)
        when {
            m == "DROWSY" || m == "STATUS:DROWSY" || m == "ALERT:1" -> drowsy()
            m == "NORMAL" || m == "STATUS:NORMAL" -> setNormal()
            m.startsWith("BATTERY:") -> binding.batteryText.text =
                getString(R.string.battery_percent, m.substringAfter(":").filter { it.isDigit() })
            else -> addEvent("Device: " + raw)
        }
    }

    private fun drowsy() {
        binding.drowsinessText.text = getString(R.string.drowsiness_detected)
        binding.drowsinessText.setTextColor(ContextCompat.getColor(this, R.color.visionex_warning))
        alerts++
        prefs.edit { putInt("alerts", alerts) }
        updateAlerts()
        addEvent("Drowsiness detected (alert " + alerts + ")")
        if (binding.autoSmsSwitch.isChecked && alerts >= emergencyThreshold) sendEmergency(false)
    }

    private fun setNormal() {
        binding.drowsinessText.text = getString(R.string.normal)
        binding.drowsinessText.setTextColor(ContextCompat.getColor(this, R.color.visionex_ok))
    }

    private fun updateAlerts() {
        binding.alertCountText.text = getString(R.string.unacknowledged_alerts, alerts)
    }

    private fun saveContact() {
        prefs.edit { putString("emergency_contact", binding.contactInput.text.toString().trim()) }
    }

    private fun sendEmergency(test: Boolean) {
        saveContact()
        val number = normalizePhilippineNumber(binding.contactInput.text.toString())
        if (number.isBlank()) {
            Toast.makeText(this, R.string.enter_emergency_contact, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsNow()
            Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_SHORT).show()
            return
        }

        location.lastLocation.addOnSuccessListener { loc ->
            val start = if (test) "VISIONEX TEST: Emergency notification test."
            else "VISIONEX ALERT: Repeated drowsiness alerts were not acknowledged."
            val where = if (loc != null)
                " Last known location: https://maps.google.com/?q=" + loc.latitude + "," + loc.longitude
            else " Last known location is unavailable."
            sendSms(number, start + where)
            addEvent(if (test) "Test emergency SMS sent" else "Emergency SMS sent")
            if (!test) {
                alerts = 0
                prefs.edit { putInt("alerts", 0) }
                updateAlerts()
            }
        }.addOnFailureListener {
            sendSms(
                number,
                if (test) "VISIONEX TEST: Emergency notification test. Location unavailable."
                else "VISIONEX ALERT: Repeated drowsiness alerts were not acknowledged. Location unavailable."
            )
        }
    }

    private fun normalizePhilippineNumber(input: String): String {
        val cleaned = input.trim().replace(" ", "").replace("-", "")
        return when {
            cleaned.startsWith("+63") -> cleaned
            cleaned.startsWith("09") && cleaned.length == 11 -> "+63" + cleaned.substring(1)
            cleaned.startsWith("9") && cleaned.length == 10 -> "+63" + cleaned
            else -> cleaned
        }
    }

    private fun smsFailure(reason: String) {
        addEvent("SMS failed: " + reason)
        Toast.makeText(this, "SMS failed: " + reason, Toast.LENGTH_LONG).show()
        val number = pendingSmsNumber
        val message = pendingSmsMessage
        if (!number.isNullOrBlank() && message != null) openSmsApp(number, message)
    }

    private fun openSmsApp(number: String, message: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:" + Uri.encode(number))
                putExtra("sms_body", message)
            })
            Toast.makeText(this, R.string.sms_opened, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.sms_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun sendSms(number: String, message: String) {
        pendingSmsNumber = number
        pendingSmsMessage = message

        val canSendDirectly = ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!canSendDirectly) {
            addEvent("Direct SMS permission unavailable; opening messaging app")
            openSmsApp(number, message)
            return
        }

        try {
            val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (defaultSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                smsFailure("No default SMS SIM is selected")
                return
            }

            val sm = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(SmsManager::class.java).createForSubscriptionId(defaultSubId)
            } else {
                SmsManager.getSmsManagerForSubscriptionId(defaultSubId)
            }

            val sentIntent = PendingIntent.getBroadcast(
                this,
                1001,
                Intent(packageName + ".SMS_SENT").setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            sm.sendTextMessage(number, null, message, sentIntent, null)
            Toast.makeText(this, "Sending SMS...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            smsFailure(e.message ?: "Unknown SMS error")
        }
    }

    private fun addEvent(desc: String) {
        val h = getHistory()
        h.put(
            JSONObject()
                .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                .put("description", desc)
        )
        prefs.edit { putString("history", h.toString()) }
        renderHistory()
    }

    private fun getHistory(): JSONArray {
        val r = prefs.getString("history", null)
        return try { if (r.isNullOrBlank()) JSONArray() else JSONArray(r) }
        catch (_: Exception) { JSONArray() }
    }

    private fun renderHistory() {
        val h = getHistory()
        if (h.length() == 0) {
            binding.historyText.text = getString(R.string.no_events_recorded)
            return
        }
        val lines = mutableListOf<String>()
        for (i in h.length() - 1 downTo 0) {
            val e = h.optJSONObject(i) ?: continue
            lines += e.optString("time") + "  " + e.optString("description")
        }
        binding.historyText.text = lines.joinToString("\n")
    }
}
