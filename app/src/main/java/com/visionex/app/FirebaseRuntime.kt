package com.visionex.app

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp

/**
 * Initializes the default Firebase app using configuration resources generated
 * by the Google services Gradle plugin from app/google-services.json.
 */
object FirebaseRuntime {
    fun initialize(context: Context): Boolean {
        return try {
            FirebaseApp.initializeApp(context) != null
        } catch (error: Exception) {
            Log.e("VisionEx", "Firebase initialization failed. Check app/google-services.json.", error)
            false
        }
    }
}
