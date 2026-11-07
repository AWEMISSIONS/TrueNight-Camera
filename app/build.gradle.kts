plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.awemissions.truenight"
    compileSdk = 35
    defaultConfig { applicationId = "org.awemissions.truenight"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}