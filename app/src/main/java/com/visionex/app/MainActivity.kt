package com.visionex.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.Activity
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.visionex.app.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleVisionExManager
    private lateinit var cameraManager: CameraDrowsinessManager
    private val authPrefs by lazy { getSharedPreferences("visionex_auth", MODE_PRIVATE) }
    private val prefs by lazy {
        val username = authPrefs.getString("current_user", "guest") ?: "guest"
        val safeUsername = username.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        getSharedPreferences("visionex_prefs_$safeUsername", MODE_PRIVATE)
    }
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
    private var activeAlertPlayer: MediaPlayer? = null
    private var alertAudioGeneration = 0
    private val cloudSyncHandler = Handler(Looper.getMainLooper())
    private var cloudSettingsSyncRunnable: Runnable? = null
    private var initializingControls = false
    private var accountDataReady = false
    private var smsPreparedForCurrentAlertCycle = false
    private var activeSessionStartMillis: Long? = null
    private var activeSessionId: String? = null

    private var pendingSmsPayload: Pair<String, String>? = null
    private var activeSmsBatchId = 0L
    private var smsBatchInProgress = false
    private var smsPartsExpected = 0
    private var smsPartsCompleted = 0
    private var smsBatchFailed = false
    private var smsBatchFailureReason: String? = null

    companion object {
        private const val DROWSINESS_CHANNEL_ID = "visionex_drowsiness_alerts"
        private const val DROWSINESS_NOTIFICATION_ID = 6201
        private const val SMS_SENT_ACTION = "com.visionex.app.SMS_SENT"
        private const val EXTRA_SMS_BATCH_ID = "sms_batch_id"
        private const val EXTRA_SMS_PART_INDEX = "sms_part_index"
        private const val EXTRA_SMS_PART_COUNT = "sms_part_count"
        private const val SESSION_HISTORY_KEY = "sessions"
        private const val ACTIVE_SESSION_START_KEY = "active_session_start"
        private const val ACTIVE_SESSION_ID_KEY = "active_session_id"
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

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingSmsPayload
        pendingSmsPayload = null
        if (granted) {
            if (pending != null) sendSmsDirect(pending.first, pending.second)
        } else {
            addEvent("SMS not sent: SEND_SMS permission denied")
            binding.autoSmsSwitch.isChecked = false
            prefs.edit { putBoolean("auto_sms", false) }
            Toast.makeText(this, R.string.sms_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onCustomAudioSelected(uri)
    }

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val receivedIntent = intent ?: return
            if (receivedIntent.action != SMS_SENT_ACTION) return
            val batchId = receivedIntent.getLongExtra(EXTRA_SMS_BATCH_ID, -1L)
            if (!smsBatchInProgress || batchId != activeSmsBatchId) return

            if (resultCode != Activity.RESULT_OK) {
                smsBatchFailed = true
                if (smsBatchFailureReason == null) {
                    smsBatchFailureReason = when (resultCode) {
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "Cellular radio is off"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "Invalid SMS data"
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Carrier or SMS service rejected the message"
                        else -> "Android SMS service returned error code ${resultCode}"
                    }
                }
            }

            smsPartsCompleted++
            if (smsPartsCompleted >= smsPartsExpected) {
                smsBatchInProgress = false
                val failure = smsBatchFailureReason
                activeSmsBatchId = 0L
                if (smsBatchFailed) {
                    val reason = failure ?: "SMS transmission failed"
                    addEvent("SMS transmission failed: " + reason)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.sms_failed, reason),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    addEvent("All SMS parts accepted by Android/carrier; delivery confirmation is unavailable")
                    Toast.makeText(this@MainActivity, R.string.sms_sent, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FirebaseRuntime.initialize(this)) {
            authPrefs.edit { remove("current_user") }
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser == null) {
            authPrefs.edit { remove("current_user") }
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        val accountName = authPrefs.getString("current_user", null)
            ?: firebaseUser.displayName
            ?: firebaseUser.email?.substringBefore("@")
            ?: "visionex_user"
        authPrefs.edit { putString("current_user", accountName.lowercase(Locale.ROOT)) }
        applySavedTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        createDrowsinessNotificationChannel()

        ble = BleVisionExManager(
            this,
            { status ->
                runOnUiThread {
                    binding.connectionText.text = status
                    if (status.equals("VISIONEX connected", ignoreCase = true)) {
                        syncHardwareAlertSettings()
                    }
                }
            },
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
        setupActions()
        ContextCompat.registerReceiver(
            this,
            smsSentReceiver,
            IntentFilter(SMS_SENT_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        requestPermissionsNow()
        loadCloudAccountData()
    }

    private fun accountDocument() =
        FirebaseAuth.getInstance().currentUser?.uid?.let {
            FirebaseFirestore.getInstance().collection("users").document(it)
        }

    private fun settingsDocument() =
        accountDocument()?.collection("private")?.document("settings")

    private fun historyCollection() = accountDocument()?.collection("history")

    private fun sessionsCollection() = accountDocument()?.collection("sessions")

    private fun loadCloudAccountData() {
        val account = accountDocument()
        if (account == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        val settingsTask = account.collection("private").document("settings").get()
        val historyTask = account.collection("history")
            .orderBy("timestampMillis", Query.Direction.ASCENDING).get()
        val sessionsTask = account.collection("sessions")
            .orderBy("startedAt", Query.Direction.ASCENDING).get()

        Tasks.whenAllComplete(settingsTask, historyTask, sessionsTask)
            .addOnSuccessListener {
                val loaded = settingsTask.isSuccessful && historyTask.isSuccessful && sessionsTask.isSuccessful
                if (loaded) {
                    val settingsSnapshot = settingsTask.result
                    val historySnapshot = historyTask.result
                    val sessionsSnapshot = sessionsTask.result
                    val migrationNotDone = !prefs.getBoolean("cloud_migration_completed", false)
                    val shouldMigrateLocalData = migrationNotDone &&
                        !settingsSnapshot.exists() &&
                        historySnapshot.isEmpty &&
                        sessionsSnapshot.isEmpty

                    if (shouldMigrateLocalData) {
                        // Preserve this username's existing v0.0.5 local data on its first cloud login.
                        saveSettingsToCloud()
                        migrateLocalHistoryToCloud()
                        migrateLocalSessionsToCloud()
                        prefs.edit { putBoolean("cloud_migration_completed", true) }
                    } else {
                        applyCloudSettings(settingsSnapshot)
                        replaceLocalHistoryFromCloud(historySnapshot)
                        replaceLocalSessionsFromCloud(sessionsSnapshot)
                        prefs.edit { putBoolean("cloud_migration_completed", true) }
                    }
                } else {
                    Snackbar.make(binding.root, R.string.cloud_sync_failed, Snackbar.LENGTH_LONG).show()
                }
                initializeAccountUi(syncInitialSettings = loaded)
            }
            .addOnFailureListener {
                Snackbar.make(binding.root, R.string.cloud_sync_failed, Snackbar.LENGTH_LONG).show()
                initializeAccountUi(syncInitialSettings = false)
            }
    }

    private fun migrateLocalHistoryToCloud() {
        val collection = historyCollection() ?: return
        val localHistory = getHistory()
        for (i in 0 until localHistory.length()) {
            val event = localHistory.optJSONObject(i) ?: continue
            val textTime = event.optString("time", "")
            val description = event.optString("description", "")
            val storedTimestamp = event.optLong("timestampMillis", 0L)
            val timestamp = if (storedTimestamp > 0L) storedTimestamp else parseHistoryTimestamp(textTime)
            collection.document().set(
                mapOf(
                    "time" to if (textTime.isBlank()) formatHistoryTimestamp(timestamp) else textTime,
                    "description" to description,
                    "timestampMillis" to timestamp
                )
            ).addOnFailureListener {
                android.util.Log.w("VisionEx", "Local history migration failed", it)
            }
        }
    }

    private fun parseHistoryTimestamp(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(value)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun formatHistoryTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private fun migrateLocalSessionsToCloud() {
        val sessions = getSessions()
        for (i in 0 until sessions.length()) {
            val session = sessions.optJSONObject(i) ?: continue
            val startedAt = session.optLong("startedAt", 0L)
            if (startedAt <= 0L) continue
            val id = session.optString("sessionId").takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            saveCloudSession(
                id,
                startedAt,
                session.optLong("endedAt", 0L),
                session.optBoolean("recovered", false)
            )
        }
    }

    private fun initializeAccountUi(syncInitialSettings: Boolean) {
        if (accountDataReady) return
        initializingControls = true
        restoreSettings()
        setupSettingsControls()
        setupCameraControls()
        updateAlerts()
        renderHistory()
        renderSessions()
        updateAlertAudioLabel()
        initializingControls = false
        accountDataReady = true
        if (syncInitialSettings) scheduleCloudSettingsSync()
    }

    private fun applyCloudSettings(snapshot: DocumentSnapshot) {
        if (!snapshot.exists()) return
        val editor = prefs.edit()
        val integerKeys = setOf(
            "vibration_strength", "emergency_threshold", "alerts",
            "camera_confidence", "camera_perclos_window",
            "camera_perclos_threshold", "camera_microsleep", "camera_closed_class"
        )
        snapshot.data.orEmpty().forEach { (key, value) ->
            if (key == "updatedAt" || key == "custom_audio_uri") return@forEach
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Float -> editor.putFloat(key, value)
                is Number -> {
                    if (key in integerKeys) editor.putInt(key, value.toInt())
                    else editor.putLong(key, value.toLong())
                }
            }
        }
        editor.apply()
    }

    private fun replaceLocalHistoryFromCloud(snapshot: QuerySnapshot) {
        val history = JSONArray()
        snapshot.documents.forEach { document ->
            val timestamp = document.getLong("timestampMillis") ?: 0L
            val time = document.getString("time")
                ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            history.put(
                JSONObject()
                    .put("time", time)
                    .put("description", document.getString("description").orEmpty())
                    .put("timestampMillis", timestamp)
                    .put("eventId", document.id)
            )
        }
        prefs.edit { putString("history", history.toString()) }
    }

    private fun replaceLocalSessionsFromCloud(snapshot: QuerySnapshot) {
        val items = mutableListOf<JSONObject>()
        snapshot.documents.forEach { document ->
            items.add(
                JSONObject()
                    .put("sessionId", document.id)
                    .put("startedAt", document.getLong("startedAt") ?: 0L)
                    .put("endedAt", document.getLong("endedAt") ?: 0L)
                    .put("recovered", document.getBoolean("recovered") ?: false)
            )
        }

        // Keep a just-started local session if it began while the cloud read was in flight.
        val currentId = activeSessionId
        val currentStart = activeSessionStartMillis
        if (currentId != null && currentStart != null && items.none { it.optString("sessionId") == currentId }) {
            items.add(
                JSONObject()
                    .put("sessionId", currentId)
                    .put("startedAt", currentStart)
                    .put("endedAt", 0L)
                    .put("recovered", false)
            )
        }
        items.sortBy { it.optLong("startedAt", 0L) }
        val array = JSONArray()
        items.forEach { array.put(it) }
        prefs.edit { putString(SESSION_HISTORY_KEY, array.toString()) }
    }

    private fun scheduleCloudSettingsSync() {
        if (!accountDataReady || initializingControls) return
        cloudSettingsSyncRunnable?.let { cloudSyncHandler.removeCallbacks(it) }
        val runnable = Runnable { saveSettingsToCloud() }
        cloudSettingsSyncRunnable = runnable
        cloudSyncHandler.postDelayed(runnable, 450L)
    }

    private fun saveSettingsToCloud() {
        val document = settingsDocument() ?: return
        val excludedKeys = setOf(
            "history", SESSION_HISTORY_KEY, ACTIVE_SESSION_START_KEY,
            ACTIVE_SESSION_ID_KEY, "custom_audio_uri", "cloud_migration_completed"
        )
        val settings = hashMapOf<String, Any>()
        prefs.all.forEach { (key, value) ->
            if (key in excludedKeys || value == null) return@forEach
            when (value) {
                is Boolean, is String, is Int, is Long, is Float, is Double -> settings[key] = value
            }
        }
        settings["updatedAt"] = System.currentTimeMillis()
        document.set(settings, SetOptions.merge())
            .addOnFailureListener {
                android.util.Log.w("VisionEx", "Cloud settings sync failed", it)
            }
    }

    private fun saveCloudHistoryEvent(time: String, description: String, timestampMillis: Long) {
        historyCollection()?.document()?.set(
            mapOf(
                "time" to time,
                "description" to description,
                "timestampMillis" to timestampMillis
            )
        )?.addOnFailureListener {
            android.util.Log.w("VisionEx", "Cloud history write failed", it)
        }
    }

    private fun saveCloudSession(
        sessionId: String,
        startedAt: Long,
        endedAt: Long,
        recovered: Boolean
    ) {
        sessionsCollection()?.document(sessionId)?.set(
            mapOf(
                "sessionId" to sessionId,
                "startedAt" to startedAt,
                "endedAt" to endedAt,
                "recovered" to recovered,
                "updatedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        )?.addOnFailureListener {
            android.util.Log.w("VisionEx", "Cloud session write failed", it)
        }
    }

    private fun deleteCloudDocuments(
        documents: List<DocumentSnapshot>,
        offset: Int = 0,
        onComplete: () -> Unit
    ) {
        if (offset >= documents.size) {
            onComplete()
            return
        }
        val chunk = documents.drop(offset).take(400)
        val batch = FirebaseFirestore.getInstance().batch()
        chunk.forEach { batch.delete(it.reference) }
        batch.commit()
            .addOnSuccessListener { deleteCloudDocuments(documents, offset + chunk.size, onComplete) }
            .addOnFailureListener {
                Snackbar.make(binding.root, R.string.cloud_sync_failed, Snackbar.LENGTH_LONG).show()
            }
    }

    private fun clearHistoryForAccount() {
        prefs.edit { remove("history") }
        renderHistory()
        val collection = historyCollection() ?: return
        collection.get()
            .addOnSuccessListener { snapshot ->
                deleteCloudDocuments(snapshot.documents) {
                    Snackbar.make(binding.root, R.string.no_events_recorded, Snackbar.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Snackbar.make(binding.root, R.string.cloud_sync_failed, Snackbar.LENGTH_LONG).show()
            }
    }

    private fun clearSessionHistoryForAccount() {
        val previous = getSessions()
        val activeOnly = JSONArray()
        for (i in 0 until previous.length()) {
            val session = previous.optJSONObject(i) ?: continue
            if (session.optLong("endedAt", 0L) <= 0L) activeOnly.put(session)
        }
        prefs.edit { putString(SESSION_HISTORY_KEY, activeOnly.toString()) }
        renderSessions()

        val collection = sessionsCollection() ?: return
        collection.whereGreaterThan("endedAt", 0L).get()
            .addOnSuccessListener { snapshot ->
                deleteCloudDocuments(snapshot.documents) { renderSessions() }
            }
            .addOnFailureListener {
                Snackbar.make(binding.root, R.string.cloud_sync_failed, Snackbar.LENGTH_LONG).show()
            }
    }

    private fun updateAlertAudioLabel() {
        if (!::binding.isInitialized) return
        val label = prefs.getString("alert_audio_label", "").orEmpty()
        binding.alertAudioName.text = if (label.isBlank()) {
            getString(R.string.alert_audio_default)
        } else if (label == getString(R.string.alert_audio_default)) {
            label
        } else {
            getString(R.string.alert_audio_custom, label)
        }
    }

    private fun onCustomAudioSelected(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some document providers do not offer persistable URI permissions.
        }
        val label = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "Custom alert sound"

        prefs.edit {
            putString("custom_audio_uri", uri.toString())
            putString("alert_audio_label", label)
            putString("alert_audio_url", "")
        }
        updateAlertAudioLabel()
        scheduleCloudSettingsSync()
        uploadCustomAlertAudio(uri)
        if (isCurrentlyDrowsy) startAlertAudio()
    }

    private fun uploadCustomAlertAudio(uri: Uri) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        if (BuildConfig.FIREBASE_STORAGE_BUCKET.isBlank()) {
            Snackbar.make(binding.root, R.string.alert_audio_storage_missing, Snackbar.LENGTH_LONG).show()
            return
        }
        val reference = try {
            FirebaseStorage.getInstance().reference
                .child("users/${user.uid}/alert_audio/custom_alert")
        } catch (_: Exception) {
            Snackbar.make(binding.root, R.string.alert_audio_storage_missing, Snackbar.LENGTH_LONG).show()
            return
        }

        val rawContentType = contentResolver.getType(uri) ?: "audio/mpeg"
        val contentType = if (rawContentType.startsWith("audio/")) rawContentType else "audio/mpeg"
        val metadata = StorageMetadata.Builder().setContentType(contentType).build()
        Snackbar.make(binding.root, R.string.alert_audio_uploading, Snackbar.LENGTH_SHORT).show()
        reference.putFile(uri, metadata)
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    throw task.exception ?: IllegalStateException("Custom audio upload failed.")
                }
                reference.downloadUrl
            }
            .addOnSuccessListener { downloadUri ->
                prefs.edit { putString("alert_audio_url", downloadUri.toString()) }
                scheduleCloudSettingsSync()
                if (isCurrentlyDrowsy) startAlertAudio()
                Snackbar.make(binding.root, R.string.alert_audio_uploaded, Snackbar.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                Snackbar.make(
                    binding.root,
                    getString(R.string.alert_audio_upload_failed, error.localizedMessage ?: "unknown error"),
                    Snackbar.LENGTH_LONG
                ).show()
            }
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
        updateAlertAudioLabel()

        val strength = prefs.getInt("vibration_strength", 70)
        binding.vibrationSeekBar.progress = strength
        binding.vibrationStrengthText.text = getString(R.string.vibration_strength, strength)

        emergencyThreshold = prefs.getInt("emergency_threshold", 3).coerceIn(1, 20)
        binding.emergencyThresholdSeekBar.progress = emergencyThreshold - 1
        binding.emergencyThresholdText.text = getString(R.string.emergency_threshold, emergencyThreshold)

        alerts = prefs.getInt("alerts", 0)
        updateAlerts()
        updateAutoSmsProgress()

        val savedTheme = prefs.getString("theme", "system") ?: "system"
        when (savedTheme) {
            "light" -> binding.themeLight.isChecked = true
            "dark" -> binding.themeDark.isChecked = true
            else -> binding.themeSystem.isChecked = true
        }
        applyTheme(savedTheme)
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
            scheduleCloudSettingsSync()
        })

        binding.applyVibrationButton.setOnClickListener {
            animateTap(it)
            val strength = binding.vibrationSeekBar.progress
            prefs.edit { putInt("vibration_strength", strength) }
            scheduleCloudSettingsSync()
            ble.sendCommand("VIBRATION:" + strength)
            Toast.makeText(this, R.string.settings_sent, Toast.LENGTH_SHORT).show()
        }

        binding.vibrationSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit { putBoolean("vibration_enabled", enabled) }
            scheduleCloudSettingsSync()
            if (isCurrentlyDrowsy) updateActiveAlertOutputs()
            ble.sendCommand("VIBRATION_ENABLED:" + if (enabled) "1" else "0")
        }

        binding.buzzerSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit { putBoolean("buzzer_enabled", enabled) }
            scheduleCloudSettingsSync()
            if (isCurrentlyDrowsy) updateActiveAlertOutputs()
            ble.sendCommand("BUZZER_ENABLED:" + if (enabled) "1" else "0")
        }

        binding.autoSmsSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit { putBoolean("auto_sms", enabled) }
            scheduleCloudSettingsSync()
            updateAutoSmsProgress()
            if (enabled &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            }
        }

        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.themeLight -> "light"
                R.id.themeDark -> "dark"
                else -> "system"
            }
            prefs.edit { putString("theme", theme) }
            applyTheme(theme)
            scheduleCloudSettingsSync()
        }

        binding.chooseAlertAudioButton.setOnClickListener {
            animateTap(it)
            audioPickerLauncher.launch(arrayOf("audio/*"))
        }
        binding.defaultAlertAudioButton.setOnClickListener {
            animateTap(it)
            prefs.edit {
                putString("custom_audio_uri", "")
                putString("alert_audio_url", "")
                putString("alert_audio_label", getString(R.string.alert_audio_default))
            }
            updateAlertAudioLabel()
            scheduleCloudSettingsSync()
            if (isCurrentlyDrowsy) startAlertAudio()
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
        scheduleCloudSettingsSync()
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
            if (simulatedDrowsy) {
                simulatedDrowsy = false
                simulatedDrowsyAcknowledged = true
            }
            smsPreparedForCurrentAlertCycle = false
            updateAlerts()
            updateAutoSmsProgress()
            scheduleCloudSettingsSync()
            refreshCombinedDrowsinessStatus()
            ble.sendCommand("ACK")
            ble.sendCommand("ALERT:0")
            addEvent("Alert acknowledged")
        }
        binding.logoutButton.setOnClickListener {
            cloudSettingsSyncRunnable?.let { cloudSyncHandler.removeCallbacks(it) }
            saveSettingsToCloud()
            finishMonitoringSession()
            stopLocalAlerts()
            cameraManager.stopCamera()
            ble.disconnect()
            FirebaseAuth.getInstance().signOut()
            authPrefs.edit { remove("current_user") }
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
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
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.confirm_clear_history_title)
                .setMessage(R.string.confirm_clear_history_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ -> clearHistoryForAccount() }
                .show()
        }
        binding.clearSessionsButton.setOnClickListener {
            animateTap(it)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.confirm_clear_sessions_title)
                .setMessage(R.string.confirm_clear_sessions_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ -> clearSessionHistoryForAccount() }
                .show()
        }
        binding.testSmsButton.setOnClickListener {
            animateTap(it)
            saveContact()
            sendEmergency(true)
        }
    }

    override fun onStart() {
        super.onStart()
        startMonitoringSession()
    }

    override fun onStop() {
        finishMonitoringSession()
        super.onStop()
    }

    override fun onDestroy() {
        stopLocalAlerts()
        try { unregisterReceiver(smsSentReceiver) } catch (_: Exception) {}
        if (::cameraManager.isInitialized) {
            try { cameraManager.close() } catch (_: Exception) {}
        }
        if (::ble.isInitialized) {
            try { ble.disconnect() } catch (_: Exception) {}
        }
        super.onDestroy()
    }


    private fun createDrowsinessNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DROWSINESS_CHANNEL_ID,
                "VisionEx Drowsiness Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when VisionEx detects a potential drowsiness condition."
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun showDrowsinessNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            6201,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, DROWSINESS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("VISIONEX: Drowsiness detected")
            .setContentText("A potential drowsiness condition was detected. Check the alert and respond safely.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(DROWSINESS_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Local vibration and tone still work if notifications are not permitted.
        }
    }

    private fun getSystemVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        }
    }

    private fun startLocalAlerts() {
        updateActiveAlertOutputs()
    }

    private fun updateActiveAlertOutputs() {
        val vibrationEnabled = prefs.getBoolean("vibration_enabled", true)
        val buzzerEnabled = prefs.getBoolean("buzzer_enabled", true)

        if (vibrationEnabled) {
            val vibrator = getSystemVibrator()
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 300L, 150L, 300L, 150L, 350L),
                        -1
                    )
                )
            }
        } else {
            try { getSystemVibrator().cancel() } catch (_: Exception) {}
        }

        if (buzzerEnabled) {
            if (activeAlertPlayer == null) startAlertAudio()
        } else {
            stopAlertAudio()
        }

        // The hardware firmware should latch ALERT:1 until it receives ALERT:0.
        ble.sendCommand(if (vibrationEnabled || buzzerEnabled) "ALERT:1" else "ALERT:0")
    }

    private fun startAlertAudio() {
        stopAlertAudio()
        if (!isCurrentlyDrowsy || !prefs.getBoolean("buzzer_enabled", true)) return
        val generation = alertAudioGeneration
        val candidates = listOfNotNull(
            prefs.getString("custom_audio_uri", null)?.takeIf { it.isNotBlank() },
            prefs.getString("alert_audio_url", null)?.takeIf { it.isNotBlank() },
            Settings.System.DEFAULT_ALARM_ALERT_URI?.toString(),
            Settings.System.DEFAULT_NOTIFICATION_URI?.toString()
        ).distinct()

        fun attempt(index: Int) {
            if (generation != alertAudioGeneration || !isCurrentlyDrowsy) return
            if (index >= candidates.size) {
                addEvent("Alert audio could not be played")
                return
            }

            val player = MediaPlayer()
            activeAlertPlayer = player
            player.isLooping = true
            player.setOnPreparedListener { readyPlayer ->
                if (generation == alertAudioGeneration && isCurrentlyDrowsy &&
                    prefs.getBoolean("buzzer_enabled", true)
                ) {
                    try {
                        readyPlayer.isLooping = true
                        readyPlayer.start()
                    } catch (_: Exception) {
                        readyPlayer.release()
                        if (activeAlertPlayer === readyPlayer) activeAlertPlayer = null
                        attempt(index + 1)
                    }
                } else {
                    try { readyPlayer.release() } catch (_: Exception) {}
                    if (activeAlertPlayer === readyPlayer) activeAlertPlayer = null
                }
            }
            player.setOnErrorListener { failedPlayer, _, _ ->
                try { failedPlayer.release() } catch (_: Exception) {}
                if (activeAlertPlayer === failedPlayer) activeAlertPlayer = null
                attempt(index + 1)
                true
            }

            try {
                val source = candidates[index]
                if (source.startsWith("http://", true) || source.startsWith("https://", true)) {
                    player.setDataSource(source)
                } else {
                    player.setDataSource(this, Uri.parse(source))
                }
                player.prepareAsync()
            } catch (_: Exception) {
                try { player.release() } catch (_: Exception) {}
                if (activeAlertPlayer === player) activeAlertPlayer = null
                attempt(index + 1)
            }
        }

        attempt(0)
    }

    private fun stopAlertAudio() {
        alertAudioGeneration++
        activeAlertPlayer?.let { player ->
            try {
                player.setOnPreparedListener(null)
                player.setOnErrorListener(null)
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
        }
        activeAlertPlayer = null
    }

    private fun stopLocalAlerts() {
        try { getSystemVibrator().cancel() } catch (_: Exception) {}
        stopAlertAudio()
    }

    private fun syncHardwareAlertSettings() {
        ble.sendCommand("VIBRATION:" + prefs.getInt("vibration_strength", 70))
        ble.sendCommand("VIBRATION_ENABLED:" + if (prefs.getBoolean("vibration_enabled", true)) "1" else "0")
        ble.sendCommand("BUZZER_ENABLED:" + if (prefs.getBoolean("buzzer_enabled", true)) "1" else "0")
    }

    private fun applySavedTheme() {
        val saved = prefs.getString("theme", "system") ?: "system"
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
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.POST_NOTIFICATIONS
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
        val effectiveDrowsiness = bleDrowsy || cameraDrowsy || simulatedDrowsy
        if (effectiveDrowsiness) drowsy() else if (isCurrentlyDrowsy) setNormal()
    }

    private fun drowsy() {
        // Trigger alert outputs once when the combined state enters drowsiness.
        if (isCurrentlyDrowsy) return
        isCurrentlyDrowsy = true

        startLocalAlerts()
        showDrowsinessNotification()
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
        scheduleCloudSettingsSync()
        updateAlerts()
        addEvent("Drowsiness detected (alert " + alerts + ")")

        updateAutoSmsProgress()
        // Use one source of truth: the current unacknowledged-alert count.
        if (binding.autoSmsSwitch.isChecked &&
            alerts >= emergencyThreshold &&
            !smsPreparedForCurrentAlertCycle
        ) {
            val thresholdAtTrigger = emergencyThreshold
            if (!binding.contactInput.text.isNullOrBlank()) {
                // Android opens the SMS composer; the user must tap Send.
                // Keep the unacknowledged alert count until the user acknowledges it.
                smsPreparedForCurrentAlertCycle = true
                sendEmergency(false, thresholdAtTrigger)
            } else {
                Toast.makeText(this, R.string.enter_emergency_contact, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setNormal() {
        isCurrentlyDrowsy = false
        stopLocalAlerts()
        ble.sendCommand("ALERT:0")
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
        scheduleCloudSettingsSync()
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
            sendSmsDirect(number, messageStart + locationText)
            addEvent(
                if (test) "Test emergency SMS direct-send requested"
                else "Emergency SMS direct-send requested after " + triggeredAtCount + " unacknowledged alert(s)"
            )
        }

        // The user always reviews and sends the SMS from the messaging app.
        // Do not block the SMS draft if the driver denied location permission.
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

    @Suppress("DEPRECATION")
    private fun sendSmsDirect(number: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSmsPayload = number to message
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            return
        }

        if (smsBatchInProgress) {
            addEvent("SMS not sent: another SMS submission is still in progress")
            Toast.makeText(this, "An SMS is already being submitted. Try again shortly.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
                if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    val reason = "No default SMS SIM is selected"
                    addEvent("SMS submission failed: " + reason)
                    Toast.makeText(this, getString(R.string.sms_failed, reason), Toast.LENGTH_LONG).show()
                    return
                }
                val systemSmsManager = getSystemService(SmsManager::class.java)
                if (systemSmsManager == null) {
                    val reason = "SMS service is unavailable"
                    addEvent("SMS submission failed: " + reason)
                    Toast.makeText(this, getString(R.string.sms_failed, reason), Toast.LENGTH_LONG).show()
                    return
                }
                systemSmsManager.createForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.isNullOrEmpty()) {
                val reason = "SMS message is empty"
                addEvent("SMS submission failed: " + reason)
                Toast.makeText(this, getString(R.string.sms_failed, reason), Toast.LENGTH_LONG).show()
                return
            }

            activeSmsBatchId = System.currentTimeMillis()
            smsPartsExpected = parts.size
            smsPartsCompleted = 0
            smsBatchFailed = false
            smsBatchFailureReason = null
            smsBatchInProgress = true

            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.forEachIndexed { index, _ ->
                val sentIntent = Intent(SMS_SENT_ACTION)
                    .setPackage(packageName)
                    .putExtra(EXTRA_SMS_BATCH_ID, activeSmsBatchId)
                    .putExtra(EXTRA_SMS_PART_INDEX, index)
                    .putExtra(EXTRA_SMS_PART_COUNT, parts.size)
                val requestCode = (activeSmsBatchId % 1_000_000L).toInt() * 100 + index
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        this,
                        requestCode,
                        sentIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            addEvent("Direct SMS submitted to Android SMS service (" + parts.size + " part(s)); awaiting send result")
            Toast.makeText(this, R.string.sms_submitting, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            smsBatchInProgress = false
            activeSmsBatchId = 0L
            val reason = e.message ?: "Unknown SMS error"
            addEvent("SMS submission failed: " + reason)
            Toast.makeText(this, getString(R.string.sms_failed, reason), Toast.LENGTH_LONG).show()
        }
    }

    private fun startMonitoringSession() {
        if (activeSessionStartMillis != null) return

        val now = System.currentTimeMillis()
        val unfinishedStart = prefs.getLong(ACTIVE_SESSION_START_KEY, 0L)
        val unfinishedId = prefs.getString(ACTIVE_SESSION_ID_KEY, null)
        if (unfinishedStart > 0L) {
            storeCompletedSession(
                unfinishedStart,
                maxOf(unfinishedStart, now),
                recovered = true,
                sessionId = unfinishedId ?: UUID.randomUUID().toString()
            )
        }

        activeSessionStartMillis = now
        activeSessionId = UUID.randomUUID().toString()
        prefs.edit {
            putLong(ACTIVE_SESSION_START_KEY, now)
            putString(ACTIVE_SESSION_ID_KEY, activeSessionId)
        }
        upsertLocalSession(activeSessionId!!, now, 0L, recovered = false)
        saveCloudSession(activeSessionId!!, now, 0L, recovered = false)
        renderSessions()
    }

    private fun finishMonitoringSession() {
        val startedAt = activeSessionStartMillis ?: prefs.getLong(ACTIVE_SESSION_START_KEY, 0L)
        if (startedAt <= 0L) return
        val endedAt = maxOf(startedAt, System.currentTimeMillis())
        val sessionId = activeSessionId
            ?: prefs.getString(ACTIVE_SESSION_ID_KEY, null)
            ?: UUID.randomUUID().toString()
        storeCompletedSession(startedAt, endedAt, recovered = false, sessionId = sessionId)
        activeSessionStartMillis = null
        activeSessionId = null
        prefs.edit {
            remove(ACTIVE_SESSION_START_KEY)
            remove(ACTIVE_SESSION_ID_KEY)
        }
        renderSessions()
    }

    private fun storeCompletedSession(
        startedAt: Long,
        endedAt: Long,
        recovered: Boolean,
        sessionId: String
    ) {
        upsertLocalSession(sessionId, startedAt, endedAt, recovered)
        saveCloudSession(sessionId, startedAt, endedAt, recovered)
    }

    private fun upsertLocalSession(
        sessionId: String,
        startedAt: Long,
        endedAt: Long,
        recovered: Boolean
    ) {
        val items = mutableListOf<JSONObject>()
        val previous = getSessions()
        for (i in 0 until previous.length()) {
            val existing = previous.optJSONObject(i) ?: continue
            if (existing.optString("sessionId") != sessionId) items.add(existing)
        }
        items.add(
            JSONObject()
                .put("sessionId", sessionId)
                .put("startedAt", startedAt)
                .put("endedAt", endedAt)
                .put("recovered", recovered)
        )
        items.sortBy { it.optLong("startedAt", 0L) }
        val updated = JSONArray()
        items.forEach { updated.put(it) }
        prefs.edit { putString(SESSION_HISTORY_KEY, updated.toString()) }
    }

    private fun getSessions(): JSONArray {
        val saved = prefs.getString(SESSION_HISTORY_KEY, null)
        return try { if (saved.isNullOrBlank()) JSONArray() else JSONArray(saved) }
        catch (_: Exception) { JSONArray() }
    }

    private fun formatSessionTimestamp(timestamp: Long): String =
        SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date(timestamp))

    private fun renderSessions() {
        if (!::binding.isInitialized) return
        val sessions = getSessions()
        val rows = mutableListOf<String>()
        for (i in sessions.length() - 1 downTo 0) {
            val session = sessions.optJSONObject(i) ?: continue
            val startedAt = session.optLong("startedAt", 0L)
            if (startedAt <= 0L) continue
            val endedAt = session.optLong("endedAt", 0L)
            val startedLine = getString(R.string.session_started_at, formatSessionTimestamp(startedAt))
            val endedLine = if (endedAt > 0L) {
                getString(R.string.session_ended_at, formatSessionTimestamp(endedAt))
            } else getString(R.string.session_in_progress)
            val recoveryNote = if (session.optBoolean("recovered", false)) {
                "\n" + getString(R.string.session_end_estimated_note)
            } else ""
            rows += "Session ${i + 1}\n$startedLine\n$endedLine$recoveryNote"
        }
        binding.sessionHistoryText.text = if (rows.isEmpty()) {
            getString(R.string.no_sessions_recorded)
        } else rows.joinToString("\n\n")
    }

    private fun addEvent(desc: String) {
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
        val h = getHistory()
        h.put(
            JSONObject()
                .put("time", time)
                .put("description", desc)
                .put("timestampMillis", now)
        )
        prefs.edit { putString("history", h.toString()) }
        saveCloudHistoryEvent(time, desc, now)
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
