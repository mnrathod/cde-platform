plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.cde.sdk"
    compileSdk = 37

    defaultConfig {
        // 23 is where the keystore can hold an AES key, which is what
        // TokenStore needs to encrypt the session token at rest. PdfRenderer
        // arrives earlier, at 21, but shipping an SDK that keeps a bearer
        // token in cleartext to reach two releases from 2014 is not a trade
        // worth making. Raise this freely — nothing here assumes 23.
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // No kotlinOptions block: AGP 9 removed it, and with built-in Kotlin the
    // JVM target follows compileOptions.targetCompatibility above.

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
