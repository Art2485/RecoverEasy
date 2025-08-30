plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}
android {
  compileSdk = 34
  defaultConfig {
    applicationId = "com.recovereasy"
    minSdk = 24
    targetSdk = 34
  }
}
dependencies {
  implementation("androidx.core:core-ktx:1.12.0")
}
