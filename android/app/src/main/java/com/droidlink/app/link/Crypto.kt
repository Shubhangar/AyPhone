package com.droidlink.app.link

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM, laid out as nonce(12) | ciphertext | tag(16) to match CryptoKit's `combined`. */
open class LinkCrypto(key: ByteArray) {
    protected val spec = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    open fun seal(plain: ByteArray): ByteArray = seal(plain, ByteArray(NONCE).also(random::nextBytes))

    open fun open(box: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, box, 0, NONCE))
        return cipher.doFinal(box, NONCE, box.size - NONCE)
    }

    protected fun seal(plain: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(plain)
    }

    companion object {
        const val NONCE = 12
        const val TAG_BITS = 128
        /** Each side's random contribution to a session key. */
        const val SESSION_NONCE = 16

        fun randomNonce() = ByteArray(SESSION_NONCE).also(SecureRandom()::nextBytes)
    }
}

/**
 * One connection's encryption (the Mac's LinkSession). Its key comes from the pairing key and
 * both sides' random nonces (HKDF-SHA256), so it's new every time and frames recorded from
 * another connection don't open. Nonces are a direction byte and a counter, and each frame must
 * carry the next one: a frame repeated within the session is refused too.
 */
class SessionCrypto(pairingKey: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray) :
    LinkCrypto(hkdf(pairingKey, phoneNonce + macNonce, "AyPhone session".toByteArray())) {
    private var sent = 0L
    private var received = 0L

    override fun seal(plain: ByteArray): ByteArray = seal(plain, nonce(FROM_PHONE, sent++))

    override fun open(box: ByteArray): ByteArray {
        if (box.size <= NONCE || !box.copyOfRange(0, NONCE).contentEquals(nonce(FROM_MAC, received))) {
            throw GeneralSecurityException("out-of-order or repeated frame")
        }
        return super.open(box).also { received++ }
    }

    private companion object {
        const val FROM_MAC: Byte = 0x4D
        const val FROM_PHONE: Byte = 0x50

        fun nonce(direction: Byte, counter: Long): ByteArray =
            ByteBuffer.allocate(NONCE).put(direction).put(0).put(0).put(0).putLong(counter).array()

        /** RFC 5869 with one 32-byte block, as CryptoKit's HKDF<SHA256> derives it. */
        fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
            val prk = extract.doFinal(ikm)
            val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            return expand.doFinal(info + byteArrayOf(1))
        }
    }
}
