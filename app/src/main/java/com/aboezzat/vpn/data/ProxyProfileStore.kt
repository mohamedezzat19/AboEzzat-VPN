package com.aboezzat.vpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProxyProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("proxy_profiles_v1", Context.MODE_PRIVATE)
    @Volatile private var credentialReadFailure = false

    fun hasCredentialReadFailure(): Boolean = credentialReadFailure

    fun loadProfiles(): List<ProxyProfile> {
        return try {
            val array = JSONArray(prefs.getString("profiles", "[]"))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val protocol = runCatching {
                        ProxyProtocol.valueOf(item.optString("protocol", "HTTP_CONNECT"))
                    }.getOrDefault(ProxyProtocol.HTTP_CONNECT)
                    add(
                        ProxyProfile(
                            name = item.optString("name"),
                            host = item.optString("host"),
                            port = item.optInt("port"),
                            protocol = protocol,
                            username = decrypt(item.optString("username", "")),
                            password = decrypt(item.optString("password", "")),
                            remoteDns = item.optBoolean("remoteDns", true)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            credentialReadFailure = true
            emptyList()
        }
    }

    /**
     * Refuses to overwrite stored profiles if a credential could not be
     * decrypted, preserving the original encrypted values on disk.
     */
    fun saveProfiles(profiles: List<ProxyProfile>): Boolean {
        if (credentialReadFailure) return false
        return try {
            val array = JSONArray()
            profiles.forEach { profile ->
                array.put(
                    JSONObject().apply {
                        put("name", profile.name)
                        put("host", profile.host)
                        put("port", profile.port)
                        put("protocol", profile.protocol.name)
                        put("username", encrypt(profile.username.orEmpty()))
                        put("password", encrypt(profile.password.orEmpty()))
                        put("remoteDns", profile.remoteDns)
                    }
                )
            }
            prefs.edit().putString("profiles", array.toString()).commit()
        } catch (_: Exception) {
            false
        }
    }

    fun activeProfileName(): String = prefs.getString("active_profile", "") ?: ""

    fun setActiveProfile(name: String) {
        prefs.edit().putString("active_profile", name).apply()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? {
        if (value.isEmpty()) return null
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val encrypted = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            credentialReadFailure = true
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "aboezzat_vpn_proxy_credentials"
    }
}
