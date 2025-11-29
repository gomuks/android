package app.gomuks.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Encryption {
    companion object {
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val AES_SIZE = 256
        private const val GCM_TAG_SIZE = 128
        private const val GCM_IV_SIZE = 12

        fun generatePlainKey(): ByteArray {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES)
            keyGen.init(AES_SIZE)
            return keyGen.generateKey().encoded
        }

        fun fromPlainKey(key: ByteArray): Encryption {
            return Encryption(SecretKeySpec(key, KeyProperties.KEY_ALGORITHM_AES))
        }

        private fun buildParamSpec(keyName: String): KeyGenParameterSpec {
            return KeyGenParameterSpec
                .Builder(keyName, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_SIZE)
                .build()
        }
    }

    private var key: SecretKey

    constructor(keyName: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
        if (!keyStore.containsAlias(keyName)) {
            val keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE
            )
            keyGen.init(buildParamSpec(keyName))
            keyGen.generateKey()
        }
        key = keyStore.getKey(keyName, null) as SecretKey
    }

    constructor(key: SecretKey) {
        this.key = key
    }

    fun encrypt(input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(input)
        return cipher.iv + encrypted
    }

    fun encrypt(input: String): String {
        return Base64.encodeToString(encrypt(input.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_MODE)
        val iv = encrypted.sliceArray(0 until GCM_IV_SIZE)
        val actualEncrypted = encrypted.sliceArray(GCM_IV_SIZE until encrypted.size)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))
        return cipher.doFinal(actualEncrypted)
    }

    fun decrypt(encrypted: String): String {
        return decrypt(Base64.decode(encrypted, Base64.DEFAULT)).toString(Charsets.UTF_8)
    }
}