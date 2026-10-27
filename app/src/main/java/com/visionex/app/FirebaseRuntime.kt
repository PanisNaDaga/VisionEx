package com.visionex.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Initializes Firebase from Gradle properties or environment variables.
 * The project configuration is supplied by the owner of the Firebase project.
 */
object FirebaseRuntime {
    fun isConfigured(): Boolean =
        BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    fun initialize(context: Context): Boolean {
        if (!isConfigured()) return false

        if (FirebaseApp.getApps(context).isEmpty()) {
            val builder = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)

            if (BuildConfig.FIREBASE_STORAGE_BUCKET.isNotBlank()) {
                builder.setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
            }
            FirebaseApp.initializeApp(context, builder.build())
        }
        return true
    }
}
