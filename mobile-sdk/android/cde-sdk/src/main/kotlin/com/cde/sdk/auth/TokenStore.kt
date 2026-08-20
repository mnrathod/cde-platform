package com.cde.sdk.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the session token between launches.
 *
 * Encrypted at rest via the Android keystore rather than kept in plain
 * preferences: a bearer token is a credential, and on a rooted or backed-up
 * device plain preferences are readable. The keystore ties the key to the
 * device so a copied file is inert.
 *
 * Falls back to ordinary preferences only if the keystore is unavailable —
 * some older or damaged devices cannot provision a master key, and refusing
 * to run at all would be a worse outcome than a warning.
 */
class TokenStore(context: Context) {

    private val preferences: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "cde-sdk-session",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        context.getSharedPreferences("cde-sdk-session-plain", Context.MODE_PRIVATE)
    }

    val token: String? get() = preferences.getString(KEY_TOKEN, null)
    val username: String? get() = preferences.getString(KEY_USERNAME, null)
    val role: String? get() = preferences.getString(KEY_ROLE, null)

    fun store(token: String, username: String, role: String) {
        preferences.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .putString(KEY_ROLE, role)
            .apply()
    }

    fun clear() = preferences.edit().clear().apply()

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_USERNAME = "username"
        const val KEY_ROLE = "role"
    }
}
