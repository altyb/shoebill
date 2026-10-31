plugins {
    kotlin("jvm") version "2.2.10"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

// Pure logic lives in the app; these JVM tests are its contract (no android imports allowed there).
kotlin.sourceSets["main"].kotlin.srcDir("../app/src/main/java/app/anon/player/library/logic")
kotlin.sourceSets["main"].kotlin.srcDir("../app/src/main/java/app/anon/player/player/cast/logic")
