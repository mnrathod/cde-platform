plugins {
    // AGP 9 compiles Kotlin itself, so org.jetbrains.kotlin.android is no
    // longer applied — applying it alongside built-in Kotlin registers a
    // second `kotlin` extension and fails the build. Compiler plugins such as
    // serialization are unaffected and still declared here.
    id("com.android.library") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
