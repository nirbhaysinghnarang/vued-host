package com.nsn8.vued.crypto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device exercise of the asymmetric crypto path — proves seal-to-public-key,
 * open-with-private-key, passphrase escrow, and persistence all work end to end
 * without any backend. Argon2id runs off the main thread.
 */
suspend fun runCryptoSelfTest(context: Context): String = withContext(Dispatchers.Default) {
    buildString {
        appendLine("=== Crypto Self-Test (asymmetric / HPKE) ===")
        try {
            val passphrase = "correct horse battery staple"

            // 1. Provision: generate keypair, persist private, get wrapped private + public.
            val provision = VuedCrypto.provision(context, passphrase)
            appendLine("provision: ok (keypair generated; public ${provision.publicKeysetB64.length}B b64)")

            // 2. Hybrid roundtrip: seal to public key, open with private key, AAD-bound.
            val box = VuedCrypto.box(context)
            val message = "let's ship Friday — secret transcript"
            val aad = "record-123".toByteArray()
            val ciphertext = box.sealString(message, aad)
            check(box.openString(ciphertext, aad) == message) { "roundtrip mismatch" }
            appendLine("HPKE seal(public) → open(private): ok (${ciphertext.size}B ciphertext)")

            // 3. Tampered associated data must be rejected.
            val aadRejected = runCatching { box.openString(ciphertext, "other".toByteArray()) }.isFailure
            appendLine("AAD tamper rejected: ${ok(aadRejected)}")

            // 4. Server-style encrypt: anyone with the PUBLIC key can seal; only we open.
            val serverEncrypt = IdentityKey.fromPublicBytes(
                android.util.Base64.decode(provision.publicKeysetB64, android.util.Base64.NO_WRAP)
            )
            val fromServer = serverEncrypt.encrypt("notes from the server".toByteArray(), aad)
            check(box.openString(fromServer, aad) == "notes from the server") { "server-seal mismatch" }
            appendLine("encrypt with public-only keyset → open with private: ok")

            // 5. Escrow: unwrap private with the right passphrase, rebuild, decrypt.
            val recovered = PassphraseVault.unwrap(provision.wrappedPrivateKey, passphrase)
            val recoveredBox = IdentityKey.fromPrivateBytes(recovered).box()
            check(recoveredBox.openString(ciphertext, aad) == message) { "escrow mismatch" }
            appendLine("passphrase escrow (Argon2id) unwrap + decrypt: ok")

            // 6. Wrong passphrase must fail.
            val wrongRejected = runCatching { PassphraseVault.unwrap(provision.wrappedPrivateKey, "nope") }.isFailure
            appendLine("wrong passphrase rejected: ${ok(wrongRejected)}")

            // 7. Private key persists (reloadable).
            check(IdentityKey.load(context) != null) { "identity not persisted" }
            appendLine("private key persisted (survives restart): ok")

            val allOk = aadRejected && wrongRejected
            appendLine(if (allOk) "ALL CHECKS PASSED" else "SOME CHECKS FAILED")
        } catch (error: Throwable) {
            appendLine("FAILED: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}

private fun ok(pass: Boolean) = if (pass) "ok" else "FAIL"
