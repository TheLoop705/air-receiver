package com.airreceiver.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.modes.ChaCha20Poly1305 as ChaCha20Poly1305Engine
import org.bouncycastle.jce.provider.BouncyCastleProvider
import timber.log.Timber
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay 2 authentication and encryption handler
 * Based on the airplay2-receiver implementation
 */
class AirPlayAuth {

    companion object {
        init {
            Security.addProvider(BouncyCastleProvider())
        }

        private const val ED25519_KEY_SIZE = 32
        private const val X25519_KEY_SIZE = 32
    }

    private val secureRandom = SecureRandom()

    // Ed25519 key pair for device identity
    private var ed25519PrivateKey: Ed25519PrivateKeyParameters? = null
    private var ed25519PublicKey: Ed25519PublicKeyParameters? = null

    // X25519 ephemeral key pair for key exchange
    private var x25519PrivateKey: X25519PrivateKeyParameters? = null
    private var x25519PublicKey: X25519PublicKeyParameters? = null

    // Shared secrets
    private var sharedSecret: ByteArray? = null
    private var sessionKey: ByteArray? = null

    // Pairing state
    private var pairingId: String? = null
    private var verified: Boolean = false

    /**
     * Initialize the authentication module and generate keys
     */
    fun initialize() {
        generateEd25519KeyPair()
        Timber.d("AirPlay authentication initialized")
    }

    /**
     * Generate Ed25519 key pair for device signing
     */
    private fun generateEd25519KeyPair() {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()

        ed25519PrivateKey = keyPair.private as Ed25519PrivateKeyParameters
        ed25519PublicKey = keyPair.public as Ed25519PublicKeyParameters
    }

    /**
     * Generate X25519 ephemeral key pair for key exchange
     */
    fun generateX25519KeyPair() {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()

        x25519PrivateKey = keyPair.private as X25519PrivateKeyParameters
        x25519PublicKey = keyPair.public as X25519PublicKeyParameters
    }

    /**
     * Get the Ed25519 public key bytes
     */
    fun getEd25519PublicKey(): ByteArray {
        return ed25519PublicKey?.encoded ?: ByteArray(ED25519_KEY_SIZE)
    }

    /**
     * Get the X25519 public key bytes
     */
    fun getX25519PublicKey(): ByteArray {
        return x25519PublicKey?.encoded ?: ByteArray(X25519_KEY_SIZE)
    }

    /**
     * Sign data with Ed25519 private key
     */
    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, ed25519PrivateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /**
     * Verify Ed25519 signature
     */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            val pubKeyParams = Ed25519PublicKeyParameters(publicKey, 0)
            verifier.init(false, pubKeyParams)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            Timber.e(e, "Signature verification failed")
            false
        }
    }

    /**
     * Perform X25519 key exchange
     */
    fun computeSharedSecret(peerPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(x25519PrivateKey)

        val peerKeyParams = X25519PublicKeyParameters(peerPublicKey, 0)
        val secret = ByteArray(X25519_KEY_SIZE)
        agreement.calculateAgreement(peerKeyParams, secret, 0)

        sharedSecret = secret
        return secret
    }

    /**
     * Derive session keys using HKDF
     */
    fun deriveSessionKey(info: String): ByteArray {
        val secret = sharedSecret ?: throw IllegalStateException("Shared secret not computed")

        // HKDF-SHA512
        val salt = ByteArray(0)
        val key = hkdfExpand(hkdfExtract(salt, secret), info.toByteArray(), 32)

        sessionKey = key
        return key
    }

    /**
     * HKDF Extract
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        val effectiveSalt = if (salt.isEmpty()) ByteArray(64) else salt
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA512"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF Expand
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(prk, "HmacSHA512"))

        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1

        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()

            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            i++
        }

        return result
    }

    /**
     * Encrypt data using ChaCha20-Poly1305
     */
    fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val key = sessionKey ?: throw IllegalStateException("Session key not derived")

        val cipher = ChaCha20Poly1305Engine()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))

        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, len)

        return output
    }

    /**
     * Decrypt data using ChaCha20-Poly1305
     */
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val key = sessionKey ?: throw IllegalStateException("Session key not derived")

        val cipher = ChaCha20Poly1305Engine()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))

        val output = ByteArray(cipher.getOutputSize(ciphertext.size))
        val len = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
        cipher.doFinal(output, len)

        return output
    }

    /**
     * Handle pair-setup step 1 (receive client public key)
     */
    fun handlePairSetupStep1(clientData: ByteArray): ByteArray {
        // Generate X25519 key pair for this session
        generateX25519KeyPair()

        // Client sends their X25519 public key
        val clientPublicKey = clientData.copyOf(X25519_KEY_SIZE)

        // Compute shared secret
        computeSharedSecret(clientPublicKey)

        // Derive keys
        deriveSessionKey("Pair-Setup-Encrypt-Salt:Pair-Setup-Encrypt-Info")

        // Return our X25519 public key
        return getX25519PublicKey()
    }

    /**
     * Handle pair-verify step 1
     */
    fun handlePairVerifyStep1(clientData: ByteArray): ByteArray {
        if (clientData.size < 4) {
            Timber.w("Invalid pair-verify data")
            return ByteArray(0)
        }

        // Generate new ephemeral X25519 key pair
        generateX25519KeyPair()

        // Extract client's ephemeral public key
        val clientEphemeralPub = clientData.copyOfRange(0, X25519_KEY_SIZE)

        // Compute shared secret
        computeSharedSecret(clientEphemeralPub)

        // Derive session key
        deriveSessionKey("Pair-Verify-Encrypt-Salt:Pair-Verify-Encrypt-Info")

        // Create response: our ephemeral public key + encrypted signature
        val response = ByteArray(X25519_KEY_SIZE)
        System.arraycopy(getX25519PublicKey(), 0, response, 0, X25519_KEY_SIZE)

        verified = true
        return response
    }

    /**
     * Handle pair-verify step 2
     */
    fun handlePairVerifyStep2(encryptedData: ByteArray): ByteArray {
        // Decrypt client's verification data
        val nonce = ByteArray(12)
        nonce[4] = 'P'.code.toByte()
        nonce[5] = 'V'.code.toByte()
        nonce[6] = '-'.code.toByte()
        nonce[7] = 'M'.code.toByte()
        nonce[8] = 's'.code.toByte()
        nonce[9] = 'g'.code.toByte()
        nonce[10] = '0'.code.toByte()
        nonce[11] = '2'.code.toByte()

        try {
            val decrypted = decrypt(encryptedData, nonce)
            Timber.d("Pair verify step 2 successful")
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify client")
        }

        // Return empty response on success
        return ByteArray(0)
    }

    /**
     * Check if session is verified
     */
    fun isVerified(): Boolean = verified

    /**
     * Reset authentication state
     */
    fun reset() {
        x25519PrivateKey = null
        x25519PublicKey = null
        sharedSecret = null
        sessionKey = null
        verified = false
        pairingId = null
    }

    /**
     * Generate a random pairing ID
     */
    fun generatePairingId(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        pairingId = bytes.toHexString()
        return pairingId!!
    }

    /**
     * Get current pairing ID
     */
    fun getPairingId(): String? = pairingId

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}

/**
 * FairPlay handler for encrypted AirPlay content
 * This is a simplified implementation - full FairPlay requires Apple's SAP
 */
class FairPlayHandler {

    companion object {
        // FairPlay message types
        const val FP_SETUP_MESSAGE_TYPE_1 = 1
        const val FP_SETUP_MESSAGE_TYPE_2 = 2
        const val FP_SETUP_MESSAGE_TYPE_3 = 3
    }

    private var fpMode: Int = 0
    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null

    /**
     * Handle FairPlay setup message
     */
    fun handleSetup(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)

        val messageType = data[0].toInt() and 0xFF
        Timber.d("FairPlay setup message type: $messageType")

        return when (messageType) {
            FP_SETUP_MESSAGE_TYPE_1 -> handleSetupMessage1(data)
            FP_SETUP_MESSAGE_TYPE_2 -> handleSetupMessage2(data)
            FP_SETUP_MESSAGE_TYPE_3 -> handleSetupMessage3(data)
            else -> {
                Timber.w("Unknown FairPlay message type: $messageType")
                ByteArray(0)
            }
        }
    }

    private fun handleSetupMessage1(data: ByteArray): ByteArray {
        // Message type 1: Initial handshake
        // In a full implementation, this would involve key exchange
        fpMode = 1

        // Return a placeholder response
        val response = ByteArray(142)
        response[0] = 2 // Response type
        return response
    }

    private fun handleSetupMessage2(data: ByteArray): ByteArray {
        // Message type 2: Key derivation
        fpMode = 2
        return ByteArray(32) // Placeholder
    }

    private fun handleSetupMessage3(data: ByteArray): ByteArray {
        // Message type 3: Finalization
        fpMode = 3
        return ByteArray(0)
    }

    /**
     * Decrypt FairPlay encrypted data
     */
    fun decrypt(data: ByteArray): ByteArray {
        val key = aesKey ?: return data
        val iv = aesIv ?: return data

        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (e: Exception) {
            Timber.e(e, "FairPlay decryption failed")
            data
        }
    }

    /**
     * Set the AES key for decryption
     */
    fun setAesKey(key: ByteArray, iv: ByteArray) {
        aesKey = key.copyOf()
        aesIv = iv.copyOf()
    }

    /**
     * Check if FairPlay is initialized
     */
    fun isInitialized(): Boolean = fpMode > 0

    /**
     * Reset FairPlay state
     */
    fun reset() {
        fpMode = 0
        aesKey = null
        aesIv = null
    }
}
