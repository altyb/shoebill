import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

// versionCode = release number x 10 + ABI slot, so each per-ABI APK has its own code and every one of them
// is above the last single-APK release (versionCode 5). Universal gets slot 0 so a device offered several
// APKs prefers its own ABI. Bump releaseNumber once per release.
val releaseNumber = 6
val abiSlot = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)
// the emulator build passes -PdevAbis=x86_64: one APK with only those ABIs, no splits
val devAbis = providers.gradleProperty("devAbis").orNull

android {
    namespace = "dev.altayeb.shoebill"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.altayeb.shoebill"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseNumber * 10
        versionName = "0.5.0"
        // without the filter the universal APK would also carry x86, which no phone needs
        ndk { abiFilters += devAbis?.split(",") ?: abiSlot.keys }
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

    // one APK per CPU plus a universal one; the universal APK is what the old single-APK installs update to
    splits.abi {
        isEnable = devAbis == null
        reset()
        include(*abiSlot.keys.toTypedArray())
        isUniversalApk = true
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
    implementation("com.hierynomus:sshj:0.40.0") // SFTP, Apache-2.0
    implementation("commons-net:commons-net:3.13.0") // FTP, Apache-2.0
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // WebDAV, Apache-2.0
    implementation("org.bouncycastle:bcprov-jdk18on:1.83") // Already pulled in by smbj and sshj; named because SFTP installs it as a provider
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
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    // Downloads exist only in the full build: Play does not allow them, so none of this reaches the Play bundle.
    "fullImplementation"("io.github.junkfood02.youtubedl-android:library:0.18.1")
    "fullImplementation"("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}

androidComponents {
    onVariants { v ->
        // yt-dlp runs from native libraries on disk, so the full build must extract them; the Play build need not.
        v.packaging.jniLibs.useLegacyPackaging.set(v.flavorName == "full")
        v.outputs.forEach { o ->
            val abi = o.filters.firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            o.versionCode.set(releaseNumber * 10 + (abiSlot[abi] ?: 0))
        }
    }
}

// Startup profile. Generating needs a phone: ./gradlew :app:generateFullReleaseBaselineProfile
dependencies { "baselineProfile"(project(":baselineprofile")) }

// The licence texts ship inside the app, for Settings > Open source licenses.
val copyLegal = tasks.register<Copy>("copyLegal") {
    from(rootProject.file("NOTICE")) { rename { "NOTICE.txt" } }
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    into(layout.buildDirectory.dir("generated/legal"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legal").get().asFile)
tasks.named("preBuild") { dependsOn(copyLegal) }
