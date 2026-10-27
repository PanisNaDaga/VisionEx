# VisionEx v0.0.6: Firebase cloud setup

Version 0.0.6 uses Firebase Authentication, Cloud Firestore, and Firebase Storage. Cloud services are **not active until you configure your own Firebase project**. This repository intentionally does not include another person's Firebase project credentials.

## 1. Create and configure the Firebase project

1. Create a project in the Firebase Console: https://console.firebase.google.com/
2. Register an Android app with package name `com.visionex.app`.
3. In **Authentication → Sign-in method**, enable **Email/Password**.
4. Create a Cloud Firestore database.
5. Create a Cloud Storage bucket.
6. In **Project settings → General**, copy the Web API key, Android App ID, Project ID, and Storage bucket name.

VisionEx allows a username and password in the app. Internally, the username is mapped to an email-shaped Firebase identity ending in `@accounts.visionex.app`. This is an internal sign-in identity, not a real email inbox, and does not support email-based password recovery. Usernames are normalized to lowercase.

## 2. Configure Gradle

Set these properties in your **user-level** Gradle properties file so you do not need to commit project details into the repository.

Windows:
`%USERPROFILE%\.gradle\gradle.properties`

macOS/Linux:
`~/.gradle/gradle.properties`

Add:

```properties
visionexFirebaseApiKey=YOUR_FIREBASE_WEB_API_KEY
visionexFirebaseAppId=YOUR_ANDROID_APP_ID
visionexFirebaseProjectId=YOUR_FIREBASE_PROJECT_ID
visionexFirebaseStorageBucket=YOUR_FIREBASE_STORAGE_BUCKET
```

Rebuild the app after adding the values. The app will show a setup message and disable login/registration while the required authentication values are missing.

For CI, the Gradle script also accepts the environment variables `VISIONEX_FIREBASE_API_KEY`, `VISIONEX_FIREBASE_APP_ID`, `VISIONEX_FIREBASE_PROJECT_ID`, and `VISIONEX_FIREBASE_STORAGE_BUCKET`.

## 3. Publish security rules

Publish the supplied `firestore.rules` in Firebase Console → Firestore Database → Rules.

Publish the supplied `storage.rules` in Firebase Console → Storage → Rules.

The Firestore rules restrict each user's data to the authenticated Firebase UID. The Storage rules restrict custom alert audio to the authenticated user's own storage path and limit uploads to audio files smaller than 10 MB.

## 4. Where account data is stored

- `users/{uid}`: account profile.
- `users/{uid}/private/settings`: settings, alert thresholds, contact number, interface theme, camera test settings, and the custom-sound download URL/name.
- `users/{uid}/history/{eventId}`: timestamped app and device events.
- `users/{uid}/sessions/{sessionId}`: session start/end times and recovery status.
- `users/{uid}/alert_audio/custom_alert`: uploaded custom alert sound.

The chosen local content URI is not synchronized because it is valid only on the phone where the file was selected. The audio itself is uploaded to Firebase Storage; its download URL and display name are saved in the account so another signed-in device can play it.

History and session records use separate Firestore documents, rather than one growing array in a settings document. This avoids the Firestore document-size limit for long-running history.

## 5. Session and audio behavior

A monitoring session starts when the dashboard enters the foreground and ends when it leaves the foreground. Each session is displayed as a numbered group, for example:

```text
Session 1
Session Started at May 14, 2026 11:32 PM
Session Ended at May 15, 2026 3:43 AM
```

Clearing event history removes history records from the cloud account. Clearing session history removes completed sessions; an active session is preserved.

When drowsiness remains detected, the phone audio repeats until the app receives a normal state from all active detection sources. The app also sends `ALERT:1` to the glasses when alert outputs are active and `ALERT:0` when they should stop. The embedded firmware must hold the physical buzzer on for `ALERT:1` and stop it on `ALERT:0`; this behavior cannot be verified from the Android app repository alone.
