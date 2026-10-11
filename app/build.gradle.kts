plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.visionex.app"; compileSdk = 35
 defaultConfig { applicationId = "com.visionex.app"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.0.2" }
 buildFeatures { viewBinding = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.appcompat:appcompat:1.7.0")
 implementation("com.google.android.material:material:1.12.0")
 implementation("com.google.android.gms:play-services-location:21.3.0")
}
