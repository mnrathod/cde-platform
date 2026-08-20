package androidx.security.crypto

import android.content.Context
import android.content.SharedPreferences

/**
 * Compile-only stand-ins for `androidx.security:security-crypto`.
 *
 * AndroidX is published only to Google's Maven repository. Where that host is
 * unreachable these let the rest of the SDK still be type-checked; they are
 * never on the library's own classpath, so nothing here can reach a device.
 *
 * They exist to satisfy the compiler, and deliberately throw if called: a
 * silent fake would let a test pass while proving nothing about encryption.
 */
class MasterKey private constructor() {

    enum class KeyScheme { AES256_GCM }

    class Builder(context: Context) {
        fun setKeyScheme(scheme: KeyScheme): Builder = this
        fun build(): MasterKey = MasterKey()
    }
}

object EncryptedSharedPreferences {

    enum class PrefKeyEncryptionScheme { AES256_SIV }

    enum class PrefValueEncryptionScheme { AES256_GCM }

    @JvmStatic
    fun create(
        context: Context,
        fileName: String,
        masterKey: MasterKey,
        keyScheme: PrefKeyEncryptionScheme,
        valueScheme: PrefValueEncryptionScheme,
    ): SharedPreferences =
        throw UnsupportedOperationException(
            "Compile-only stand-in. Build with the Android Gradle Plugin to get the real implementation.")
}
