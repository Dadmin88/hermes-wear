plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "io.github.hermeswear"
  compileSdk = 35
  testOptions.unitTests.isIncludeAndroidResources = true
  sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
  sourceSets["main"].res.srcDir("../shared/src/main/res")
  defaultConfig {
    applicationId = "io.github.hermeswear"
    minSdk = 26
    targetSdk = 35
    versionCode = 10
    versionName = "1.0.0-rc5"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("org.robolectric:robolectric:4.14.1"); implementation("androidx.work:work-runtime:2.10.1"); testImplementation("junit:junit:4.13.2"); implementation("com.google.android.gms:play-services-wearable:20.0.1") }
