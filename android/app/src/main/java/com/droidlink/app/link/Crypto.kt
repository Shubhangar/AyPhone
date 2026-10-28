package com.droidlink.app.link

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM, laid out as nonce(12) | ciphertext | tag(16) to match CryptoKit's `combined`. */
class LinkCrypto(key: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun seal(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(plain)
    }

    fun open(box: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, box, 0, NONCE))
        return cipher.doFinal(box, NONCE, box.size - NONCE)
    }

    private companion object {
        const val NONCE = 12
        const val TAG_BITS = 128
    }
}
