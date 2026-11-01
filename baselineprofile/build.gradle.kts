// Records which classes and methods run at startup, so release builds ship a baseline profile and start faster.
// Needs a phone (API 33+ unrooted) over USB: ./gradlew :app:generateFullReleaseBaselineProfile
// The result lands in app/src/fullRelease/generated/baselineProfiles/ and is committed.
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "dev.altayeb.shoebill.baselineprofile"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    flavorDimensions += "store"
    productFlavors {
        create("full") { dimension = "store" }
        create("play") { dimension = "store" }
    }
}

baselineProfile { useConnectedDevices = true }

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
