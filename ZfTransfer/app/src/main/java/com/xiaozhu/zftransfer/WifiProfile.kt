package com.xiaozhu.zftransfer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class WifiProfile(val ssid: String, val password: String) {
    override fun toString() = "WifiProfile(ssid=$ssid, password=<redacted>)"
    fun valid(): Boolean = ssid.isNotBlank() && ssid.toByteArray(Charsets.UTF_8).size <= 32 &&
        password.length in 8..63 && password.all { it.code in 32..126 }
}

/** Credentials never enter logs; only AES-GCM ciphertext is persisted. Backup is disabled. */
class WifiProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("camera_wifi", Context.MODE_PRIVATE)
    private val alias = "zftransfer_wifi_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): WifiProfile? = runCatching {
        val ssid = prefs.getString("ssid", null) ?: return null
        val iv = Base64.decode(prefs.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString("password", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            updateAAD(ssid.toByteArray(Charsets.UTF_8))
        }
        WifiProfile(ssid, String(cipher.doFinal(encrypted), Charsets.UTF_8)).takeIf { it.valid() }
    }.getOrNull()
    fun save(profile: WifiProfile) {
        require(profile.valid())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(profile.ssid.toByteArray(Charsets.UTF_8))
        }
        val encrypted = cipher.doFinal(profile.password.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("ssid", profile.ssid)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("password", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun clear() { prefs.edit().clear().apply() }
}
