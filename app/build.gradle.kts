import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.anon.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.anon.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
        // phones by default; the emulator build passes -PanonAbis=x86_64
        ndk { abiFilters += (providers.gradleProperty("anonAbis").orNull ?: "arm64-v8a,armeabi-v7a").split(",") }
        // each worktree agent installs as its own app: ./gradlew installDebug -PdevSuffix=.player
        providers.gradleProperty("devSuffix").orNull?.let { applicationIdSuffix = it }
        // a test copy installed next to the real app must not look like it on the home screen
        manifestPlaceholders["appLabel"] = if (providers.gradleProperty("devSuffix").isPresent) "Shoebill QA" else "Shoebill"
    }

    // release key lives outside the repo (~/.secrets/shoebill-release.properties); without it release is debug-signed
    val releaseKey = File(System.getProperty("user.home"), ".secrets/shoebill-release.properties")
        .takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // full = GitHub / F-Droid (silent move with "All files access"); play = Google Play, which does not allow that
    // permission for a vault, so moving a file asks the system delete dialog instead.
    flavorDimensions += "store"
    productFlavors {
        create("full") { dimension = "store"; buildConfigField("boolean", "ALL_FILES", "true") }
        create("play") { dimension = "store"; buildConfigField("boolean", "ALL_FILES", "false") }
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("com.google.crypto.tink:tink-android:1.24.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.compose.animation:animation-graphics:1.9.4")
}

// The licence texts ship inside the app, for Settings > Open source licenses.
val copyLegal = tasks.register<Copy>("copyLegal") {
    from(rootProject.file("NOTICE")) { rename { "NOTICE.txt" } }
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    into(layout.buildDirectory.dir("generated/legal"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legal").get().asFile)
tasks.named("preBuild") { dependsOn(copyLegal) }
