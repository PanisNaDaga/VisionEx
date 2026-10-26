import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val visionExEyeModelUrl =
    "https://huggingface.co/MichalMlodawski/open-closed-eye-detection/resolve/main/model.onnx"
val visionExEyeModelSha256 =
    "2f207282e996a6341f33e1471de77abc1d847d47c33cabe4e3a31490e7484d76"
val visionExEyeModelAsset = file("src/main/assets/visionex_open_closed_eye.onnx")

fun visionExSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val prepareVisionExEyeModel by tasks.registering {
    inputs.property("expectedSha256", visionExEyeModelSha256)
    outputs.file(visionExEyeModelAsset)
    outputs.upToDateWhen { false }
    doLast {
        if (visionExEyeModelAsset.exists() &&
            visionExSha256(visionExEyeModelAsset).equals(visionExEyeModelSha256, ignoreCase = true)
        ) {
            logger.lifecycle("VisionEx trained eye model already exists and passed SHA-256 verification.")
            return@doLast
        }

        visionExEyeModelAsset.parentFile.mkdirs()
        val temporaryFile = File(visionExEyeModelAsset.parentFile, visionExEyeModelAsset.name + ".download")
        temporaryFile.delete()
        val connection = URL(visionExEyeModelUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "VisionEx-Android/0.0.5")
            val status = connection.responseCode
            if (status !in 200..299) {
                throw GradleException("Could not download the VisionEx eye model (HTTP $status). Check your internet connection.")
            }
            connection.inputStream.buffered().use { input ->
                temporaryFile.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }

        val actualHash = visionExSha256(temporaryFile)
        if (!actualHash.equals(visionExEyeModelSha256, ignoreCase = true)) {
            temporaryFile.delete()
            throw GradleException("The downloaded VisionEx eye model failed SHA-256 verification. The APK build was stopped.")
        }
        if (visionExEyeModelAsset.exists()) visionExEyeModelAsset.delete()
        if (!temporaryFile.renameTo(visionExEyeModelAsset)) {
            temporaryFile.copyTo(visionExEyeModelAsset, overwrite = true)
            temporaryFile.delete()
        }
        logger.lifecycle("Downloaded and verified the trained VisionEx eye model for APK packaging.")
    }
}

android {
    namespace = "com.visionex.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.visionex.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.0.5"
    }

    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.named("preBuild").configure {
    dependsOn(prepareVisionExEyeModel)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
