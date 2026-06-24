package com.nsn8.vued.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.net.VuedApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AmbientDecryptor {
    private const val PREFS = "vued_ambient_decryptor"
    private const val KEY_PRIVATE = "private_key_der_b64"
    private const val KEY_NONCE = "private_key_nonce_b64"
    private const val KEYSTORE_ALIAS = "vued-ambient-decryptor-v1"
    private const val VAULT_AAD_PREFIX = "vued-user-vault-v1:"
    private const val WRAP_INFO = "vued-content-key-wrap-v1"

    fun isUnlocked(context: Context): Boolean = loadPrivateKeyDer(context) != null

    suspend fun unlock(context: Context, passphrase: String) = withContext(Dispatchers.IO) {
        val token = VuedAuth.currentAccessToken() ?: throw VuedApi.ApiException("not signed in")
        val userId = jwtSub(token) ?: throw VuedApi.ApiException("could not read user id")
        val vault = VuedApi.fetchVault()
        val salt = b64(vault.getString("salt"))
        val nonce = b64(vault.getString("nonce"))
        val wrapped = b64(vault.getString("wrapped_key"))
        val key = SCrypt.generate(
            passphrase.toByteArray(StandardCharsets.UTF_8),
            salt,
            1 shl vault.getInt("kdf_t_cost"),
            8,
            vault.getInt("kdf_parallelism"),
            32,
        )
        val payload = decryptAesGcm(key, nonce, wrapped, (VAULT_AAD_PREFIX + userId).toByteArray())
        val privateKeyDerB64 = JSONObject(String(payload, StandardCharsets.UTF_8))
            .getString("privateKeyDerB64")
        storePrivateKeyDer(context, b64(privateKeyDerB64))
    }

    fun decryptEventText(context: Context, event: JSONObject): String? {
        val sealed = event.optString("textEnc", "").ifBlank { return null }
        return decryptSealedText(context, event.getString("id"), sealed)
    }

    fun decryptSealedText(context: Context, aadId: String, sealed: String): String? {
        val privateDer = loadPrivateKeyDer(context) ?: return null
        val packet = JSONObject(String(b64(sealed), StandardCharsets.UTF_8))
        val shared = x25519(privateDer, b64(packet.getString("epk")))
        val aad = aadId.toByteArray(StandardCharsets.UTF_8)
        val key = hkdfSha256(shared, aad, WRAP_INFO.toByteArray(StandardCharsets.UTF_8), 32)
        return String(decryptAesGcm(key, b64(packet.getString("nonce")), b64(packet.getString("ct")), aad), StandardCharsets.UTF_8)
    }

    private fun storePrivateKeyDer(context: Context, privateDer: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(privateDer)
        prefs(context).edit()
            .putString(KEY_PRIVATE, b64(encrypted))
            .putString(KEY_NONCE, b64(cipher.iv))
            .apply()
    }

    private fun loadPrivateKeyDer(context: Context): ByteArray? {
        val encrypted = prefs(context).getString(KEY_PRIVATE, null) ?: return null
        val nonce = prefs(context).getString(KEY_NONCE, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, b64(nonce)))
            cipher.doFinal(b64(encrypted))
        }.getOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keystoreKey(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEYSTORE_ALIAS, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun x25519(privateDer: ByteArray, publicDer: ByteArray): ByteArray {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        val keyFactory = runCatching { KeyFactory.getInstance("X25519") }
            .getOrElse { KeyFactory.getInstance("X25519", "BC") }
        val agreement = runCatching { KeyAgreement.getInstance("X25519") }
            .getOrElse { KeyAgreement.getInstance("X25519", "BC") }
        agreement.init(keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateDer)))
        agreement.doPhase(keyFactory.generatePublic(X509EncodedKeySpec(publicDer)), true)
        return agreement.generateSecret()
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            counter += 1
        }
        return out
    }

    private fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun jwtSub(token: String): String? = runCatching {
        val part = token.split(".")[1]
        val padded = part + "=".repeat((4 - part.length % 4) % 4)
        val json = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
        JSONObject(json).getString("sub")
    }.getOrNull()

    private fun b64(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)
    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
}
