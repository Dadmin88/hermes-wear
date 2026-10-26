plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "io.github.hermeswear"
  compileSdk = 35
  testOptions.unitTests.isIncludeAndroidResources = true
  sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
  sourceSets["main"].res.srcDir("../shared/src/main/res")
  defaultConfig {
    applicationId = "io.github.hermeswear"
    minSdk = 30
    targetSdk = 35
    ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    versionCode = 17
    versionName = "1.0.0-rc12"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.alphacephei:vosk-android:0.3.75@aar"); implementation("net.java.dev.jna:jna:5.18.1@aar"); testImplementation("org.robolectric:robolectric:4.14.1"); testImplementation("junit:junit:4.13.2"); implementation("androidx.work:work-runtime-ktx:2.10.0"); implementation("com.google.android.gms:play-services-wearable:20.0.1") }
