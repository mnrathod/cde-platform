plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}


// Points at the real SDK sources rather than copying them. A copy would drift,
// and a harness that verifies a stale copy is worse than no harness.
val sdkSources = "../../android/cde-sdk/src"

sourceSets {
    main { kotlin.setSrcDirs(listOf("$sdkSources/main/kotlin", "stubs")) }
    test { kotlin.setSrcDirs(listOf("$sdkSources/test/kotlin")) }
}

dependencies {
    // Robolectric publishes the real AOSP framework classes to Maven Central,
    // so android.* calls are type-checked against the actual API rather than
    // assumed. compileOnly: the framework is provided by the device.
    compileOnly("org.robolectric:android-all:15-robolectric-13954326")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// The library targets 17. Toolchain resolution is left to whatever JDK is
// present so the harness runs on a plain CI image with no Android SDK.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test> {
    testLogging { events("passed", "failed", "skipped") }
}
