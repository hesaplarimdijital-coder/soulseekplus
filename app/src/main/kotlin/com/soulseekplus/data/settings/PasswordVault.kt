package com.soulseekplus.data.settings

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Parolayı Android Keystore'daki AES-GCM anahtarıyla şifreleyerek saklanabilir dizeye çevirir.
 * Keystore kullanılamazsa (bozuk cihazlar, yedek geri yükleme) düz metne düşer; parola yalnızca uygulamaya özel
 * depolamada tutulur (`allowBackup=false`). Biçim: `v1:<iv>:<şifreli>` ya da `p0:<düz>` (Base64).
 */
internal object PasswordVault {
    private const val ALIAS = "soulseekplus_account_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val encrypted = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            "v1:" + b64(cipher.iv) + ":" + b64(data)
        } catch (e: Exception) {
            null
        }
        return encrypted ?: ("p0:" + b64(plain.toByteArray(StandardCharsets.UTF_8)))
    }

    fun open(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        return try {
            when {
                stored.startsWith("p0:") -> String(unb64(stored.substring(3)), StandardCharsets.UTF_8)
                stored.startsWith("v1:") -> {
                    val parts = stored.split(':')
                    if (parts.size != 3) return ""
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[1])))
                    String(cipher.doFinal(unb64(parts[2])), StandardCharsets.UTF_8)
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE)
        store.load(null)
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setUnlockedDeviceRequired(false)
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
    private fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
