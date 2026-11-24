package dev.lilt.player.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the AI API key as AES/GCM ciphertext. The AES key never leaves the
 * Android Keystore, and the preference file is excluded from Lilt backups.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("lilt_secrets", Context.MODE_PRIVATE)

    fun hasApiKey(): Boolean = loadApiKey() != null

    fun saveApiKey(value: String): Boolean {
        val key = value.trim()
        if (key.isEmpty()) return false
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + encrypted
            prefs.edit()
                .putString(KEY_API, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
            true
        }.getOrDefault(false)
    }

    fun loadApiKey(): String? {
        val payload = prefs.getString(KEY_API, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return null
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val encrypted = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, iv),
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API).commit()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lilt-ai-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_API = "ai_api_key"
        const val IV_BYTES = 12
    }
}
