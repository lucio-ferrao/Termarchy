package com.mrndtvndv.term.data.ssh

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone's SSH key: one ECDSA P-256 key pair (ecdsa-sha2-nistp256) for the app, made inside the Android Keystore
 * (in StrongBox when the phone has it) and never exportable. Every signature needs a strong biometric (a fingerprint)
 * at that moment, so the key cannot be used, nor copied, any other way. The server's authorized_keys gets
 * [publicKeyLine]. Changing the enrolled fingerprints invalidates the key; a new one must then be made and its public
 * key put on the servers again.
 */
@RequiresApi(Build.VERSION_CODES.P)
class DeviceSshKey(private val context: Context) {
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    val exists: Boolean get() = keyStore.containsAlias(ALIAS)

    /** Makes the key pair, replacing any previous one. Fails without an enrolled fingerprint. */
    fun generate() {
        try {
            generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    /** The public key as an SSH blob: string "ecdsa-sha2-nistp256", string "nistp256", string Q (uncompressed point). */
    fun publicKeyBlob(): ByteArray {
        val key = keyStore.getCertificate(ALIAS)?.publicKey as? ECPublicKey
            ?: throw IOException("This phone has no SSH key yet: make one in the server settings")
        val point = byteArrayOf(4) + unsigned32(key.w.affineX) + unsigned32(key.w.affineY)
        return sshStrings(KEY_TYPE.toByteArray(), CURVE.toByteArray(), point)
    }

    /** The line for the server's ~/.ssh/authorized_keys. */
    fun publicKeyLine(): String =
        "$KEY_TYPE ${Base64.encodeToString(publicKeyBlob(), Base64.NO_WRAP)} termarchy@${Build.MODEL.replace(' ', '-')}"

    /** Why the last signature was not made, for the connection error; null when the fingerprint was just refused. */
    var signFailure: String? = null
        private set

    /**
     * Signs [data] for SSH authentication after a fingerprint: asks for it on the main thread and waits here, so call
     * it off the main thread (the native layer calls it from the connecting thread). Returns the SSH signature blob
     * (mpint r, mpint s), or null when the fingerprint is refused or cancelled, or the key no longer works.
     */
    fun sign(data: ByteArray): ByteArray? {
        signFailure = null
        val signature = Signature.getInstance("SHA256withECDSA")
        try {
            signature.initSign(keyStore.getKey(ALIAS, null) as PrivateKey)
        } catch (_: KeyPermanentlyInvalidatedException) {
            signFailure = "The fingerprints changed, so this phone's SSH key no longer works: make a new one " +
                "in the server settings and put its public key on the servers again"
            return null
        }
        val latch = CountDownLatch(1)
        var signed: ByteArray? = null
        val cancel = CancellationSignal()
        Handler(Looper.getMainLooper()).post {
            prompt().authenticate(
                BiometricPrompt.CryptoObject(signature),
                cancel,
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        signed = runCatching {
                            val sig = checkNotNull(result.cryptoObject.signature)
                            sig.update(data)
                            derToSsh(sig.sign())
                        }.getOrNull()
                        latch.countDown()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        latch.countDown()
                    }
                },
            )
        }
        if (!latch.await(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) cancel.cancel()
        return signed
    }

    private fun prompt(): BiometricPrompt =
        BiometricPrompt.Builder(context)
            .setTitle("SSH login")
            .setSubtitle("Sign in to the server with this phone's key")
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                }
            }
            .setNegativeButton("Cancel", context.mainExecutor) { _, _ -> }
            .build()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ssh_key"
        const val KEY_TYPE = "ecdsa-sha2-nistp256"
        const val CURVE = "nistp256"
        const val PROMPT_TIMEOUT_SECONDS = 120L

        /** [value] as 32 big-endian bytes, without the sign byte BigInteger may add. */
        fun unsigned32(value: BigInteger): ByteArray {
            val bytes = value.toByteArray().takeLast(32).toByteArray()
            return ByteArray(32 - bytes.size) + bytes
        }

        fun sshStrings(vararg parts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { data ->
                for (part in parts) {
                    data.writeInt(part.size)
                    data.write(part)
                }
            }
            return out.toByteArray()
        }

        /**
         * A DER ECDSA signature, SEQUENCE { INTEGER r, INTEGER s }, as the SSH blob: mpint r, mpint s. A DER INTEGER
         * is already a minimal two's complement number, which is what an mpint holds.
         */
        fun derToSsh(der: ByteArray): ByteArray {
            var i = 2
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
            fun integer(): ByteArray {
                check(der[i].toInt() == 0x02) { "Not a DER ECDSA signature" }
                val length = der[i + 1].toInt() and 0xff
                return der.copyOfRange(i + 2, i + 2 + length).also { i += 2 + length }
            }
            val r = integer()
            val s = integer()
            return sshStrings(r, s)
        }
    }
}
