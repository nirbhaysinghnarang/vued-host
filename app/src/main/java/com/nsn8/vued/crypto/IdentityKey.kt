package com.nsn8.vued.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridConfig
import java.io.ByteArrayOutputStream

/**
 * The per-user **hybrid keypair** (HPKE: X25519 + HKDF-SHA256 + AES-256-GCM).
 *
 *  - The **private** keyset never leaves the device: persisted wrapped by the
 *    Keystore key ([KeystoreMasterKey]) for runtime, and escrowed under the user's
 *    passphrase ([PassphraseVault]) for portability/recovery.
 *  - The **public** keyset is uploaded to the server (`user_keys`) so the backend can
 *    encrypt results TO the user without being able to decrypt them.
 *
 * Anyone (server or this app) can [HybridEncrypt] to the public key; only the holder
 * of the private key can [HybridDecrypt].
 */
class IdentityKey private constructor(private val handle: KeysetHandle) {

    fun box(): HybridCryptoBox = HybridCryptoBox(
        handle.publicKeysetHandle.getPrimitive(HybridEncrypt::class.java),
        handle.getPrimitive(HybridDecrypt::class.java),
    )

    fun privateKeysetBytes(): ByteArray = ByteArrayOutputStream().use {
        CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(it))
        it.toByteArray()
    }

    fun publicKeysetBytes(): ByteArray = ByteArrayOutputStream().use {
        handle.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(it))
        it.toByteArray()
    }

    companion object {
        private const val PREF_FILE = "vued_identity"
        private const val KEY = "wrapped_private"
        // HPKE: X25519-HKDF-SHA256 KEM, HKDF-SHA256 KDF, AES-256-GCM AEAD.
        private const val TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

        init {
            HybridConfig.register()
        }

        fun exists(context: Context): Boolean = prefs(context).contains(KEY)

        fun generate(context: Context): IdentityKey {
            val handle = KeysetHandle.generateNew(KeyTemplates.get(TEMPLATE))
            val identity = IdentityKey(handle)
            persist(context, identity.privateKeysetBytes())
            return identity
        }

        fun load(context: Context): IdentityKey? {
            val encoded = prefs(context).getString(KEY, null) ?: return null
            val privateBytes = KeystoreMasterKey.unwrap(Base64.decode(encoded, Base64.NO_WRAP))
            return fromPrivateBytes(privateBytes)
        }

        /** Installs a private keyset recovered from passphrase escrow (new device). */
        fun importRaw(context: Context, privateKeysetBytes: ByteArray): IdentityKey {
            persist(context, privateKeysetBytes)
            return fromPrivateBytes(privateKeysetBytes)
        }

        /** Builds an in-memory identity from raw private keyset bytes (no persistence). */
        fun fromPrivateBytes(privateKeysetBytes: ByteArray): IdentityKey =
            IdentityKey(CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(privateKeysetBytes)))

        /**
         * An encrypt-only primitive from a public keyset (what the *server* holds).
         * Can seal to the user but cannot open — mirrors the backend's capability.
         */
        fun fromPublicBytes(publicKeysetBytes: ByteArray): HybridEncrypt =
            KeysetHandle.readNoSecret(BinaryKeysetReader.withBytes(publicKeysetBytes))
                .getPrimitive(HybridEncrypt::class.java)

        fun clear(context: Context) {
            prefs(context).edit().remove(KEY).apply()
        }

        private fun persist(context: Context, privateBytes: ByteArray) {
            val wrapped = KeystoreMasterKey.wrap(privateBytes)
            prefs(context).edit()
                .putString(KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .apply()
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }
}
