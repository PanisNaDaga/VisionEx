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
    private lateinit var cameraManager: CameraDrowsinessManager
    private val prefs by lazy { getSharedPreferences("visionex_prefs", MODE_PRIVATE) }
    private val location by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var alerts = 0
    private var emergencyThreshold = 3
    private var isCurrentlyDrowsy = false
    private var bleDrowsy = false
    private var cameraDrowsy = false
    private var simulatedDrowsy = false
    private var bleDrowsyAcknowledged = false
    private var cameraDrowsyAcknowledged = false
    private var simulatedDrowsyAcknowledged = false
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

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSelectedCamera()
        else Snackbar.make(binding.root, R.string.camera_status_permission, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = BleVisionExManager(
            this,
            { runOnUiThread { binding.connectionText.text = it } },
            { runOnUiThread { handleDeviceMessage(it.trim()) } }
        )

        cameraManager = CameraDrowsinessManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreview,
            onStatus = { binding.cameraModelStatusText.text = it },
            onMetrics = { metrics ->
                val stateLabel = when (metrics.eyeState) {
                    EyeState.OPEN -> getString(R.string.camera_eye_open)
                    EyeState.CLOSED -> getString(R.string.camera_eye_closed)
                    EyeState.UNKNOWN -> getString(R.string.camera_eye_unknown)
                }
                val perclosLabel = metrics.perclosPercent?.let {
                    String.format(Locale.US, "%.1f%%", it)
                } ?: getString(R.string.camera_perclos_not_ready)
                binding.cameraMetricsText.text = getString(
                    R.string.camera_metrics_format,
                    stateLabel,
                    (metrics.confidence * 100f).toInt(),
                    perclosLabel,
                    metrics.closedDurationSeconds
                )
                binding.cameraDrowsyText.text = getString(
                    if (metrics.drowsy) R.string.camera_drowsy else R.string.camera_normal
                )
                binding.cameraDrowsyText.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (metrics.drowsy) R.color.visionex_warning else R.color.visionex_ok
                    )
                )
            },
            onDrowsinessChanged = { detected ->
                if (cameraDrowsy != detected) {
                    cameraDrowsy = detected
                    cameraDrowsyAcknowledged = false
                    refreshCombinedDrowsinessStatus()
                }
            }
        )

        setupTabs()
        restoreSettings()
        setupSettingsControls()
        setupActions()
        setupCameraControls()
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
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_camera))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun showTab(position: Int) {
        val target = when (position) {
            1 -> binding.historySection
            2 -> binding.settingsSection
            3 -> binding.cameraSection
            else -> binding.homeSection
        }
        val sections = listOf(
            binding.homeSection,
            binding.historySection,
            binding.settingsSection,
            binding.cameraSection
        )
        if (target != binding.cameraSection && cameraManager.isCameraRunning()) {
            cameraManager.stopCamera()
            binding.cameraStartButton.text = getString(R.string.camera_start)
        }

        sections.filter { it != target }.forEach { section ->
            section.animate().cancel()
            section.visibility = View.GONE
            section.alpha = 1f
            section.translationY = 0f
        }

        if (target.visibility != View.VISIBLE) {
            target.alpha = 0f
            target.translationY = 8f
            target.visibility = View.VISIBLE
            target.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    private fun restoreSettings() {
        binding.contactInput.setText(prefs.getString("emergency_contact", ""))
        binding.autoSmsSwitch.isChecked = prefs.getBoolean("auto_sms", false)
        binding.vibrationSwitch.isChecked = prefs.getBoolean("vibration_enabled", true)
        binding.buzzerSwitch.isChecked = prefs.getBoolean("buzzer_enabled", true)

        val strength = prefs.getInt("vibration_strength", 70)
        binding.vibrationSeekBar.progress = strength
        binding.vibrationStrengthText.text = getString(R.string.vibration_strength, strength)

        emergencyThreshold = prefs.getInt("emergency_threshold", 3).coerceIn(1, 20)
        binding.emergencyThresholdSeekBar.progress = emergencyThreshold - 1
        binding.emergencyThresholdText.text = getString(R.string.emergency_threshold, emergencyThreshold)

        alerts = prefs.getInt("alerts", 0)
        updateAlerts()
        updateAutoSmsProgress()

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
            updateAutoSmsProgress()
        })

        binding.applyVibrationButton.setOnClickListener {
            animateTap(it)
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
            updateAutoSmsProgress()
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

    private fun setupCameraControls() {
        binding.cameraYoloSwitch.isChecked = prefs.getBoolean("camera_yolo_enabled", true)
        binding.cameraPerclosSwitch.isChecked = prefs.getBoolean("camera_perclos_enabled", true)
        binding.cameraMicrosleepSwitch.isChecked = prefs.getBoolean("camera_microsleep_enabled", true)
        binding.cameraConfidenceSeekBar.progress = prefs.getInt("camera_confidence", 40).coerceIn(0, 80)
        binding.cameraPerclosWindowSeekBar.progress = prefs.getInt("camera_perclos_window", 50).coerceIn(0, 50)
        binding.cameraPerclosThresholdSeekBar.progress = prefs.getInt("camera_perclos_threshold", 40).coerceIn(0, 60)
        binding.cameraMicrosleepSeekBar.progress = prefs.getInt("camera_microsleep", 1).coerceIn(0, 4)
        val frontCamera = prefs.getBoolean("camera_front", true)
        binding.cameraFacingGroup.check(if (frontCamera) R.id.cameraFrontRadio else R.id.cameraBackRadio)
        val closedClass = prefs.getInt("camera_closed_class", 0).coerceIn(0, 1)
        binding.cameraClosedClassGroup.check(
            if (closedClass == 0) R.id.cameraClassZeroClosedRadio else R.id.cameraClassOneClosedRadio
        )

        listOf(
            binding.cameraYoloSwitch,
            binding.cameraPerclosSwitch,
            binding.cameraMicrosleepSwitch
        ).forEach { control ->
            control.setOnCheckedChangeListener { _, _ -> applyCameraOptions() }
        }
        binding.cameraConfidenceSeekBar.setOnSeekBarChangeListener(simpleSeekListener { applyCameraOptions() })
        binding.cameraPerclosWindowSeekBar.setOnSeekBarChangeListener(simpleSeekListener { applyCameraOptions() })
        binding.cameraPerclosThresholdSeekBar.setOnSeekBarChangeListener(simpleSeekListener { applyCameraOptions() })
        binding.cameraMicrosleepSeekBar.setOnSeekBarChangeListener(simpleSeekListener { applyCameraOptions() })
        binding.cameraClosedClassGroup.setOnCheckedChangeListener { _, _ -> applyCameraOptions() }
        binding.cameraFacingGroup.setOnCheckedChangeListener { _, _ ->
            applyCameraOptions()
            if (cameraManager.isCameraRunning()) startSelectedCamera()
        }
        binding.cameraModelButton.setOnClickListener {
            animateTap(it)
            cameraManager.downloadOrLoadModel()
        }
        binding.cameraStartButton.setOnClickListener {
            animateTap(it)
            if (cameraManager.isCameraRunning()) {
                cameraManager.stopCamera()
                binding.cameraStartButton.text = getString(R.string.camera_start)
            } else startSelectedCamera()
        }
        applyCameraOptions()
    }

    private fun applyCameraOptions() {
        val confidence = (binding.cameraConfidenceSeekBar.progress + 10) / 100f
        val windowSeconds = binding.cameraPerclosWindowSeekBar.progress + 10
        val perclosThreshold = binding.cameraPerclosThresholdSeekBar.progress + 20
        val microsleepSeconds = binding.cameraMicrosleepSeekBar.progress + 1
        val closedClassIndex = if (binding.cameraClassZeroClosedRadio.isChecked) 0 else 1
        val frontCamera = binding.cameraFrontRadio.isChecked

        binding.cameraConfidenceText.text = getString(
            R.string.camera_confidence, (confidence * 100f).toInt()
        )
        binding.cameraPerclosWindowText.text = getString(R.string.camera_perclos_window, windowSeconds)
        binding.cameraPerclosThresholdText.text = getString(
            R.string.camera_perclos_threshold, perclosThreshold
        )
        binding.cameraMicrosleepThresholdText.text = getString(
            R.string.camera_microsleep_threshold, microsleepSeconds
        )

        prefs.edit {
            putBoolean("camera_yolo_enabled", binding.cameraYoloSwitch.isChecked)
            putBoolean("camera_perclos_enabled", binding.cameraPerclosSwitch.isChecked)
            putBoolean("camera_microsleep_enabled", binding.cameraMicrosleepSwitch.isChecked)
            putInt("camera_confidence", binding.cameraConfidenceSeekBar.progress)
            putInt("camera_perclos_window", binding.cameraPerclosWindowSeekBar.progress)
            putInt("camera_perclos_threshold", binding.cameraPerclosThresholdSeekBar.progress)
            putInt("camera_microsleep", binding.cameraMicrosleepSeekBar.progress)
            putInt("camera_closed_class", closedClassIndex)
            putBoolean("camera_front", frontCamera)
        }
        cameraManager.setOptions(
            yolo = binding.cameraYoloSwitch.isChecked,
            perclos = binding.cameraPerclosSwitch.isChecked,
            microsleep = binding.cameraMicrosleepSwitch.isChecked,
            confidence = confidence,
            closedClassIndex = closedClassIndex,
            windowSeconds = windowSeconds,
            perclosThreshold = perclosThreshold,
            microsleepSeconds = microsleepSeconds
        )
    }

    private fun startSelectedCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        applyCameraOptions()
        cameraManager.startCamera(binding.cameraFrontRadio.isChecked)
        binding.cameraStartButton.text = getString(R.string.camera_stop)
    }

    private fun simpleSeekListener(onChanged: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChanged(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun animateTap(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(80)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
            }
            .start()
    }

    private fun setupActions() {
        binding.connectButton.setOnClickListener {
            animateTap(it)
            if (hasBle()) ble.scanAndConnect() else requestPermissionsNow()
        }
        binding.ackButton.setOnClickListener {
            animateTap(it)
            alerts = 0
            prefs.edit {
                putInt("alerts", 0)
                remove("auto_sms_detection_count")
            }
            if (bleDrowsy) bleDrowsyAcknowledged = true
            if (cameraDrowsy) cameraDrowsyAcknowledged = true
            if (simulatedDrowsy) simulatedDrowsyAcknowledged = true
            setNormal()
            updateAlerts()
            updateAutoSmsProgress()
            ble.sendCommand("ACK")
            addEvent("Alert acknowledged")
        }
        binding.simulateButton.setOnClickListener {
            animateTap(it)
            // Each tap is an explicit test event, independent of repeated BLE packets.
            simulatedDrowsy = true
            simulatedDrowsyAcknowledged = false
            isCurrentlyDrowsy = false
            refreshCombinedDrowsinessStatus()
        }
        binding.clearHistoryButton.setOnClickListener {
            animateTap(it)
            prefs.edit { remove("history") }
            renderHistory()
        }
        binding.testSmsButton.setOnClickListener {
            animateTap(it)
            saveContact()
            sendEmergency(true)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(smsSentReceiver) } catch (_: Exception) {}
        try { cameraManager.close() } catch (_: Exception) {}
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

    private fun handleDeviceMessage(raw: String) {
        val message = raw.uppercase(Locale.US)
        when {
            message == "DROWSY" || message == "STATUS:DROWSY" || message == "ALERT:1" -> {
                if (!bleDrowsy) {
                    bleDrowsy = true
                    bleDrowsyAcknowledged = false
                    refreshCombinedDrowsinessStatus()
                }
            }
            message == "NORMAL" || message == "STATUS:NORMAL" -> {
                if (bleDrowsy) {
                    bleDrowsy = false
                    bleDrowsyAcknowledged = false
                    refreshCombinedDrowsinessStatus()
                }
            }
            message.startsWith("BATTERY:") -> binding.batteryText.text =
                getString(R.string.battery_percent, message.substringAfter(":").filter { it.isDigit() })
            else -> addEvent("Device: " + raw)
        }
    }

    private fun refreshCombinedDrowsinessStatus() {
        val effectiveDrowsiness =
            (bleDrowsy && !bleDrowsyAcknowledged) ||
            (cameraDrowsy && !cameraDrowsyAcknowledged) ||
            (simulatedDrowsy && !simulatedDrowsyAcknowledged)
        if (effectiveDrowsiness) drowsy() else if (isCurrentlyDrowsy) setNormal()
    }

    private fun drowsy() {
        // Count a change into the drowsy state, not every repeated BLE status update.
        if (isCurrentlyDrowsy) return
        isCurrentlyDrowsy = true

        binding.drowsinessText.text = getString(R.string.drowsiness_detected)
        binding.drowsinessText.setTextColor(ContextCompat.getColor(this, R.color.visionex_warning))
        binding.drowsinessText.setBackgroundResource(R.drawable.status_pill_warning)
        binding.statusHelperText.text = getString(R.string.status_drowsy_hint)
        binding.statusCard.setStrokeColor(ContextCompat.getColor(this, R.color.visionex_warning))
        binding.drowsinessText.animate().cancel()
        binding.drowsinessText.alpha = 0.75f
        binding.drowsinessText.scaleX = 0.96f
        binding.drowsinessText.scaleY = 0.96f
        binding.drowsinessText.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(260).start()
        alerts++
        prefs.edit { putInt("alerts", alerts) }
        updateAlerts()
        addEvent("Drowsiness detected (alert " + alerts + ")")

        updateAutoSmsProgress()
        // Use one source of truth: the current unacknowledged-alert count.
        if (binding.autoSmsSwitch.isChecked && alerts >= emergencyThreshold) {
            val thresholdAtTrigger = emergencyThreshold
            if (!binding.contactInput.text.isNullOrBlank()) {
                alerts = 0
                prefs.edit {
                    putInt("alerts", 0)
                    remove("auto_sms_detection_count")
                }
                updateAlerts()
                updateAutoSmsProgress()
                sendEmergency(false, thresholdAtTrigger)
            } else {
                Toast.makeText(this, R.string.enter_emergency_contact, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setNormal() {
        isCurrentlyDrowsy = false
        binding.drowsinessText.text = getString(R.string.normal)
        binding.drowsinessText.setTextColor(ContextCompat.getColor(this, R.color.visionex_ok))
        binding.drowsinessText.setBackgroundResource(R.drawable.status_pill_normal)
        binding.statusHelperText.text = getString(R.string.status_normal_hint)
        binding.statusCard.setStrokeColor(ContextCompat.getColor(this, R.color.card_stroke))
        binding.drowsinessText.animate().cancel()
        binding.drowsinessText.alpha = 1f
        binding.drowsinessText.scaleX = 1f
        binding.drowsinessText.scaleY = 1f
    }

    private fun updateAlerts() {
        binding.alertCountText.text = getString(R.string.unacknowledged_alerts, alerts)
    }

    private fun updateAutoSmsProgress() {
        if (!::binding.isInitialized) return
        binding.autoSmsCountText.text = getString(
            R.string.auto_sms_progress,
            alerts,
            emergencyThreshold
        )
    }

    private fun saveContact() {
        prefs.edit { putString("emergency_contact", binding.contactInput.text.toString().trim()) }
    }

    private fun sendEmergency(test: Boolean, triggeredAtCount: Int = emergencyThreshold) {
        saveContact()
        val number = normalizePhilippineNumber(binding.contactInput.text.toString())
        if (number.isBlank()) {
            Toast.makeText(this, R.string.enter_emergency_contact, Toast.LENGTH_SHORT).show()
            return
        }

        val messageStart = if (test) {
            "VISIONEX TEST: Emergency notification test."
        } else {
            "VISIONEX ALERT: Drowsiness was detected $triggeredAtCount times. Please check on the driver."
        }

        val dispatchMessage: (android.location.Location?) -> Unit = { loc ->
            val locationText = if (loc != null) {
                " Last known location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            } else {
                " Driver location is unavailable."
            }
            sendSms(number, messageStart + locationText)
            addEvent(
                if (test) "Test emergency SMS requested"
                else "Automatic SMS requested after $triggeredAtCount drowsiness detections"
            )
        }

        // Do not block the emergency message if the driver denied location permission.
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            location.lastLocation
                .addOnSuccessListener { loc -> dispatchMessage(loc) }
                .addOnFailureListener { dispatchMessage(null) }
        } else {
            dispatchMessage(null)
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
