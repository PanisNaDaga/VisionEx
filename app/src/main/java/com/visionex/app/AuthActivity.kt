package com.visionex.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.visionex.app.databinding.ActivityAuthBinding
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Device-local account registration and login for the VisionEx prototype.
 * Passwords are not stored directly; each account has a random salt and PBKDF2 hash.
 * This is not a cloud identity system and accounts do not sync between devices.
 */
class AuthActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAuthBinding
    private val authPrefs by lazy { getSharedPreferences("visionex_auth", MODE_PRIVATE) }
    private var registerMode = false

    companion object {
        private const val USERS_KEY = "users_json"
        private const val HASH_ITERATIONS = 120_000
        private const val HASH_BITS = 256
        private const val SALT_BYTES = 16
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!authPrefs.getString("current_user", null).isNullOrBlank()) {
            enterApp(authPrefs.getString("current_user", "") ?: "")
            return
        }

        binding.authSubmitButton.setOnClickListener { submitCredentials() }
        binding.authSwitchButton.setOnClickListener {
            registerMode = !registerMode
            clearMessage()
            updateMode()
        }
        updateMode()
    }

    private fun updateMode() {
        binding.authTitle.text = getString(
            if (registerMode) R.string.auth_title_register else R.string.auth_title_login
        )
        binding.authSubtitle.text = getString(R.string.auth_subtitle)
        binding.authConfirmPassword.visibility = if (registerMode) View.VISIBLE else View.GONE
        binding.authSubmitButton.text = getString(
            if (registerMode) R.string.auth_register_button else R.string.auth_login_button
        )
        binding.authSwitchButton.text = getString(
            if (registerMode) R.string.auth_switch_to_login else R.string.auth_switch_to_register
        )
    }

    private fun submitCredentials() {
        val username = binding.authUsername.text.toString().trim().lowercase(Locale.ROOT)
        val password = binding.authPassword.text.toString()
        val confirmation = binding.authConfirmPassword.text.toString()

        if (!username.matches(Regex("^[a-zA-Z0-9_.-]{3,32}$"))) {
            showMessage(getString(R.string.auth_error_username))
            return
        }
        if (password.length < 8) {
            showMessage(getString(R.string.auth_error_password))
            return
        }

        val users = readUsers()
        if (registerMode) {
            if (password != confirmation) {
                showMessage(getString(R.string.auth_error_confirm))
                return
            }
            if (users.has(username)) {
                showMessage(getString(R.string.auth_error_exists))
                return
            }

            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val hash = derivePasswordHash(password.toCharArray(), salt, HASH_ITERATIONS)
            val account = JSONObject()
                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .put("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
                .put("iterations", HASH_ITERATIONS)

            users.put(username, account)
            authPrefs.edit {
                putString(USERS_KEY, users.toString())
                putString("current_user", username)
            }
            enterApp(username)
        } else {
            val account = users.optJSONObject(username)
            if (account == null) {
                showMessage(getString(R.string.auth_error_invalid))
                return
            }
            try {
                val salt = Base64.decode(account.getString("salt"), Base64.NO_WRAP)
                val expectedHash = Base64.decode(account.getString("hash"), Base64.NO_WRAP)
                val iterations = account.optInt("iterations", HASH_ITERATIONS).coerceIn(100_000, 500_000)
                val actualHash = derivePasswordHash(password.toCharArray(), salt, iterations)
                if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                    showMessage(getString(R.string.auth_error_invalid))
                    return
                }
                authPrefs.edit { putString("current_user", username) }
                enterApp(username)
            } catch (_: Exception) {
                showMessage(getString(R.string.auth_error_invalid))
            }
        }
    }

    private fun readUsers(): JSONObject {
        return try {
            JSONObject(authPrefs.getString(USERS_KEY, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun derivePasswordHash(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
    }

    private fun showMessage(message: String) {
        binding.authMessage.text = message
    }

    private fun clearMessage() {
        binding.authMessage.text = ""
    }

    private fun enterApp(username: String) {
        authPrefs.edit { putString("current_user", username.lowercase(Locale.ROOT)) }
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(binding.root.windowToken, 0)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
