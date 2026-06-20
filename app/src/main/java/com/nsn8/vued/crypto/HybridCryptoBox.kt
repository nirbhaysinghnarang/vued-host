package com.nsn8.vued.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt

/**
 * Hybrid (public-key) content encryption via Tink HPKE.
 *
 *  - [seal] encrypts to the user's public key — usable by the server *or* this app.
 *  - [open] decrypts with the private key — only this device (or a passphrase-recovered
 *    one) can do it.
 *
 * `context` is HPKE associated data (bind e.g. the record id) — it must match on
 * seal/open or decryption fails.
 */
class HybridCryptoBox(
    private val encrypt: HybridEncrypt,
    private val decrypt: HybridDecrypt,
) {
    fun seal(plaintext: ByteArray, context: ByteArray = EMPTY): ByteArray =
        encrypt.encrypt(plaintext, context)

    fun open(ciphertext: ByteArray, context: ByteArray = EMPTY): ByteArray =
        decrypt.decrypt(ciphertext, context)

    fun sealString(text: String, context: ByteArray = EMPTY): ByteArray =
        seal(text.toByteArray(Charsets.UTF_8), context)

    fun openString(ciphertext: ByteArray, context: ByteArray = EMPTY): String =
        String(open(ciphertext, context), Charsets.UTF_8)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
