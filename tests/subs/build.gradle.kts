plugins {
    kotlin("jvm") version "2.2.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // JVM stand-in for Android's built-in org.json. Not needed in the app build.
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}

// The code under test lives in the app.
kotlin.sourceSets["main"].kotlin.srcDir("../../app/src/main/java/dev/altayeb/shoebill/subs")
