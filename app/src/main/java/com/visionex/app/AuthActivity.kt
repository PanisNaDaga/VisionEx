package com.visionex.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.visionex.app.databinding.ActivityAuthBinding
import java.util.Locale

/**
 * Cloud-backed VisionEx accounts.
 * Usernames map to a private Firebase email identity and are not real mailboxes.
 */
class AuthActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAuthBinding
    private val authPrefs by lazy { getSharedPreferences("visionex_auth", MODE_PRIVATE) }
    private var registerMode = false
    private var requestInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!FirebaseRuntime.initialize(this)) {
            showMessage(getString(R.string.cloud_setup_missing))
            binding.authSubmitButton.isEnabled = false
            binding.authSwitchButton.isEnabled = false
            return
        }

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val username = authPrefs.getString("current_user", null)
                ?: currentUser.displayName
                ?: currentUser.email?.substringBefore("@")
                ?: "visionex_user"
            enterApp(username)
            return
        }

        binding.authSubmitButton.setOnClickListener { submitCredentials() }
        binding.authSwitchButton.setOnClickListener {
            if (requestInProgress) return@setOnClickListener
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
        if (requestInProgress) return

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
        if (registerMode && password != confirmation) {
            showMessage(getString(R.string.auth_error_confirm))
            return
        }

        setRequestInProgress(true)
        clearMessage()
        val identity = usernameToFirebaseEmail(username)
        val auth = FirebaseAuth.getInstance()
        val operation = if (registerMode) {
            auth.createUserWithEmailAndPassword(identity, password)
        } else {
            auth.signInWithEmailAndPassword(identity, password)
        }

        operation.addOnSuccessListener { result ->
            val user = result.user
            if (user == null) {
                setRequestInProgress(false)
                showMessage(getString(R.string.auth_error_invalid))
                return@addOnSuccessListener
            }

            val profile = hashMapOf<String, Any>(
                "username" to username,
                "updatedAt" to System.currentTimeMillis()
            )
            if (registerMode) profile["createdAt"] = System.currentTimeMillis()

            FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.uid)
                .set(profile, SetOptions.merge())
                .addOnSuccessListener {
                    setRequestInProgress(false)
                    enterApp(username)
                }
                .addOnFailureListener { error ->
                    auth.signOut()
                    setRequestInProgress(false)
                    showMessage(
                        "Authentication worked, but saving the cloud profile failed. " +
                            (error.localizedMessage ?: "Check Firestore setup and rules.")
                    )
                }
        }.addOnFailureListener { error ->
            setRequestInProgress(false)
            showMessage(friendlyAuthError(error))
        }
    }

    private fun usernameToFirebaseEmail(username: String): String =
        "${username.lowercase(Locale.ROOT)}@accounts.visionex.app"

    private fun friendlyAuthError(error: Exception): String {
        val message = error.localizedMessage.orEmpty()
        return when {
            message.contains("already in use", ignoreCase = true) ||
                message.contains("email-already-in-use", ignoreCase = true) ->
                getString(R.string.auth_error_exists)
            message.contains("no user record", ignoreCase = true) ||
                message.contains("wrong password", ignoreCase = true) ||
                message.contains("invalid credential", ignoreCase = true) ||
                message.contains("invalid login credentials", ignoreCase = true) ->
                getString(R.string.auth_error_invalid)
            message.contains("network", ignoreCase = true) ->
                getString(R.string.cloud_network_error)
            else -> message.ifBlank { getString(R.string.cloud_generic_error) }
        }
    }

    private fun setRequestInProgress(inProgress: Boolean) {
        requestInProgress = inProgress
        binding.authSubmitButton.isEnabled = !inProgress
        binding.authSwitchButton.isEnabled = !inProgress
        binding.authSubmitButton.text = if (inProgress) {
            getString(R.string.cloud_auth_working)
        } else {
            getString(if (registerMode) R.string.auth_register_button else R.string.auth_login_button)
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
