package dev.shrimpscript.porthole.net

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hierynomus.sshj.signature.Ed25519PublicKey
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts a secret at rest. Production uses the Android Keystore; tests a stand-in. */
interface Vault {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM under a key that lives in the Android Keystore and never leaves it, so the
 * app's own storage, copied off the phone, does not give the secret away.
 */
object KeystoreVault : Vault {
    private const val ALIAS = "porthole-failsafe"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    fun delete() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/**
 * This phone's own SSH key, for the failsafe on a computer where Tailscale SSH does not
 * sign it in: a Mac running the Tailscale app, or Linux with its own sshd. When asked,
 * the daemon puts the public half in the computer's ~/.ssh/authorized_keys, usable only
 * from this phone's tailnet addresses, and takes it out again when the phone is revoked.
 *
 * The private half is an Ed25519 seed of 32 bytes, stored sealed by a [Vault]. One key
 * serves every paired computer: each ties it to this phone's addresses on its own.
 */
class FailsafeKey(private val prefs: SharedPreferences, private val vault: Vault = KeystoreVault) {
    private companion object {
        const val SEALED = "failsafe_key_sealed"
        const val PUBLIC = "failsafe_key_public"
    }

    /** Whether a key has been made on this phone. */
    val exists: Boolean get() = prefs.contains(SEALED)

    /** The public key as an authorized_keys entry, making the key pair on first use. */
    fun publicLine(): String {
        prefs.getString(PUBLIC, null)?.let { if (exists) return it }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val line = publicLineOf(seed)
        // commit, not apply: the public half is about to be sent to the computer, and a
        // key the phone then loses (the process ends before an apply lands) would sit in
        // authorized_keys with nothing on the phone to match it.
        val saved = prefs.edit()
            .putString(SEALED, Base64.encodeToString(vault.seal(seed), Base64.NO_WRAP))
            .putString(PUBLIC, line)
            .commit()
        check(saved) { "could not save the key" }
        seed.fill(0)
        return line
    }

    /**
     * The key for sshj, or null when there is none. A key that exists but cannot be opened
     * (the Keystore entry is gone, as after a restore onto another phone) is an error, not
     * "no key": the failsafe would otherwise quietly try another way in and fail for a
     * reason nobody could see.
     */
    fun provider(): KeyProvider? {
        val sealed = prefs.getString(SEALED, null) ?: return null
        val seed = try {
            vault.open(Base64.decode(sealed, Base64.NO_WRAP))
        } catch (e: Exception) {
            throw IllegalStateException("this phone's SSH key could not be opened - add it again from Settings", e)
        }
        return keyPairOf(seed).also { seed.fill(0) }
    }

    fun forget() {
        prefs.edit().remove(SEALED).remove(PUBLIC).commit()
    }
}

/** The OpenSSH public key line for an Ed25519 seed. */
internal fun publicLineOf(seed: ByteArray): String {
    val pub = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519))).abyte
    val blob = ByteArrayOutputStream()
    for (part in listOf("ssh-ed25519".toByteArray(), pub)) {
        blob.write(byteArrayOf((part.size ushr 24).toByte(), (part.size ushr 16).toByte(), (part.size ushr 8).toByte(), part.size.toByte()))
        blob.write(part)
    }
    return "ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(blob.toByteArray()) + " porthole"
}

/** The key pair sshj signs with, rebuilt from its seed. */
internal fun keyPairOf(seed: ByteArray): KeyProvider {
    val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
    val pub = Ed25519PublicKey(EdDSAPublicKeySpec(priv.a, spec))
    return KeyPairWrapper(pub, priv)
}
