# VisionEx v0.0.6: Firebase cloud setup

Version 0.0.6 uses Firebase Authentication, Cloud Firestore, and Firebase Storage. The Android app is configured with the Google services Gradle plugin and `app/google-services.json`.

## 1. Confirm the Firebase project

The included `app/google-services.json` is configured for project `visionex-c6d5a` and Android package `com.visionex.app`. If you use a different Firebase project, download its Android configuration file from Firebase Console → Project settings → General and replace `app/google-services.json`. The registered Android package must be exactly `com.visionex.app`.

The Firebase Android configuration file contains client configuration values used by the app. It is not a substitute for security rules. Keep Firestore and Storage locked down with the included rules, and restrict the API key to the Android app where practical.

## 2. Enable Firebase services

1. In Firebase Console → Authentication → Sign-in method, enable **Email/Password**.
2. Create a **Cloud Firestore** database.
3. Create a **Cloud Storage** bucket.
4. Publish `firestore.rules` in Firestore Database → Rules.
5. Publish `storage.rules` in Storage → Rules.

Firebase Console: https://console.firebase.google.com/

VisionEx allows a username and password in the app. Internally, the username is mapped to an email-shaped Firebase identity ending in `@accounts.visionex.app`. This is an internal sign-in identity, not a real email inbox, and does not support email-based password recovery. Usernames are normalized to lowercase.

## 3. Build the APK

The root Gradle file applies the Google services plugin version `4.5.0`, and the app module reads `app/google-services.json`. Firebase Auth, Firestore, and Storage are already included as dependencies.

In Android Studio, select **Sync Project with Gradle Files**, then use **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

Because the configuration file is part of the branch, local builds and GitHub Actions builds use the same Firebase project configuration. You do not need to add Firebase values to your personal Gradle properties file.

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
