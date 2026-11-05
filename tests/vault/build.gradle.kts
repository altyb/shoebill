plugins {
    kotlin("jvm") version "2.2.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.crypto.tink:tink:1.15.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

// The code under test lives in the app; these JVM tests are the vault contract (tests-first, never edited by the implementer).
kotlin.sourceSets["main"].kotlin.srcDir("../../app/src/main/java/dev/altayeb/shoebill/vault/crypto")
kotlin.sourceSets["main"].kotlin.srcDir("../../app/src/main/java/dev/altayeb/shoebill/vault/logic")
