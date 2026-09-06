package com.phoneoperator.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the AI API key using Android Keystore-backed encryption, never in
 * plain SharedPreferences and never logged. The app ships with no key
 * baked in — the user (or an MDM config for enterprise deployment) supplies
 * it once via a settings screen, and this is the only place it's read from.
 */
class ApiKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "phone_operator_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getApiKey(): String? = prefs.getString(KEY, null)

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY, key).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "ai_api_key"
    }
}
