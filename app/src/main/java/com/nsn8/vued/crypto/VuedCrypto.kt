package com.nsn8.vued.crypto

import android.content.Context
import android.util.Base64

/**
 * Facade over the asymmetric crypto core.
 *
 * Lifecycle:
 *  - [provision] once per user → generates the keypair, persists the private key
 *    device-bound, and returns BOTH the passphrase-[WrappedKey] (upload to
 *    `user_vault`) and the public keyset Base64 (upload to `user_keys` so the server
 *    can encrypt to the user).
 *  - [recover] on a new device → unwrap the private key with the passphrase.
 *  - [box] anywhere → the [HybridCryptoBox]: seal to the public key (server or app),
 *    open with the private key (this device only).
 */
object VuedCrypto {

    data class Provision(val wrappedPrivateKey: WrappedKey, val publicKeysetB64: String)

    fun isProvisioned(context: Context): Boolean = IdentityKey.exists(context)

    fun provision(context: Context, passphrase: String): Provision {
        val identity = IdentityKey.generate(context)
        return Provision(
            wrappedPrivateKey = PassphraseVault.wrap(identity.privateKeysetBytes(), passphrase),
            publicKeysetB64 = Base64.encodeToString(identity.publicKeysetBytes(), Base64.NO_WRAP),
        )
    }

    fun recover(context: Context, passphrase: String, wrapped: WrappedKey) {
        val privateBytes = PassphraseVault.unwrap(wrapped, passphrase)
        IdentityKey.importRaw(context, privateBytes)
    }

    fun box(context: Context): HybridCryptoBox =
        (IdentityKey.load(context) ?: error("device not provisioned")).box()

    /** The public keyset (Base64) to send to the server, or null if not provisioned. */
    fun publicKeysetB64(context: Context): String? =
        IdentityKey.load(context)?.let { Base64.encodeToString(it.publicKeysetBytes(), Base64.NO_WRAP) }

    fun clear(context: Context) = IdentityKey.clear(context)
}
