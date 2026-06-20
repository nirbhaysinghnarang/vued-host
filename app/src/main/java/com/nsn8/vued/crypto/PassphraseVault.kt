package com.nsn8.vued.crypto

import android.util.Base64
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Escrows the user's **private keyset** under their **encryption passphrase** — the
 * second secret, separate from the Supabase login password and never sent to any
 * server. Argon2id (memory-hard) derives a KEK from the passphrase; the KEK wraps the
 * private keyset bytes with AES-256-GCM. Only the resulting [WrappedKey] is uploaded
 * to the `user_vault` row, so the operator only ever sees ciphertext + a salt.
 */
object PassphraseVault {

    private const val T_COST = 3
    private const val M_COST_KIB = 64 * 1024
    private const val PARALLELISM = 1
    private const val KEK_LEN = 32
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    private val argon2 = Argon2Kt()

    fun wrap(privateKeyset: ByteArray, passphrase: String): WrappedKey {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val kek = deriveKek(passphrase, salt, T_COST, M_COST_KIB, PARALLELISM)
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(privateKeyset)
        return WrappedKey(
            saltB64 = b64(salt),
            nonceB64 = b64(nonce),
            ciphertextB64 = b64(ciphertext),
            tCost = T_COST,
            mCostKib = M_COST_KIB,
            parallelism = PARALLELISM,
        )
    }

    /** Throws (AEADBadTagException) if the passphrase is wrong. */
    fun unwrap(wrapped: WrappedKey, passphrase: String): ByteArray {
        val kek = deriveKek(
            passphrase,
            unb64(wrapped.saltB64),
            wrapped.tCost,
            wrapped.mCostKib,
            wrapped.parallelism,
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(kek, "AES"),
            GCMParameterSpec(TAG_BITS, unb64(wrapped.nonceB64)),
        )
        return cipher.doFinal(unb64(wrapped.ciphertextB64))
    }

    private fun deriveKek(
        passphrase: String,
        salt: ByteArray,
        tCost: Int,
        mCostKib: Int,
        parallelism: Int,
    ): ByteArray = argon2.hash(
        mode = Argon2Mode.ARGON2_ID,
        password = passphrase.toByteArray(Charsets.UTF_8),
        salt = salt,
        tCostInIterations = tCost,
        mCostInKibibyte = mCostKib,
        parallelism = parallelism,
        hashLengthInBytes = KEK_LEN,
    ).rawHashAsByteArray()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}

/**
 * The passphrase-wrapped private keyset destined for the Supabase `user_vault` row.
 * All fields are Base64; Argon2id cost params travel with the wrap so they can evolve.
 */
data class WrappedKey(
    val saltB64: String,
    val nonceB64: String,
    val ciphertextB64: String,
    val tCost: Int,
    val mCostKib: Int,
    val parallelism: Int,
)
