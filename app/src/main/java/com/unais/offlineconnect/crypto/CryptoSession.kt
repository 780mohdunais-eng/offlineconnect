package com.unais.offlineconnect.crypto

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Transport-level encryption between two directly-connected devices (spec section 15).
 * Uses standard Android/JCE primitives only - no custom crypto:
 *   - ECDH (secp256r1) key agreement, one fresh keypair per connection (forward secrecy
 *     between sessions - a new socket = a new handshake = a new key)
 *   - SHA-256 of the raw shared secret as the AES key (simple KDF; good enough for a
 *     per-session symmetric key, not claiming this is a full HKDF)
 *   - AES/GCM/NoPadding for authenticated encryption of every packet's bytes
 *
 * HONEST LIMITATION: this is hop-to-hop encryption, not end-to-end across relay mode.
 * A relay device necessarily decrypts each packet (to read destinationId/TTL and decide
 * where to forward it) and re-encrypts it for the next hop. That means a relay device
 * can technically read message content passing through it. True end-to-end encryption
 * across multiple hops would need per-conversation keys distributed out-of-band between
 * the two end devices, which is out of scope here - documented in the README too.
 */
class CryptoSession private constructor(private val aesKey: SecretKeySpec) {

    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun decrypt(data: ByteArray): ByteArray? {
        if (data.size < 13) return null
        val iv = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null // auth failure / corrupted packet - caller drops it, never crashes
        }
    }

    companion object {
        /**
         * Blocking handshake: writes our ephemeral EC public key, reads the peer's,
         * derives a shared AES key. Both sides call this the same way right after the
         * RFCOMM socket connects - there's no explicit client/server asymmetry needed
         * for ECDH. Returns null if the handshake fails for any reason (peer too old,
         * corrupted data, IO error) so the caller can decide whether to abort the
         * connection or fall back.
         */
        fun handshake(input: InputStream, output: OutputStream): CryptoSession? {
            return try {
                val kpg = KeyPairGenerator.getInstance("EC")
                kpg.initialize(256) // secp256r1
                val keyPair = kpg.generateKeyPair()
                val myPub = keyPair.public.encoded

                val header = ByteBuffer.allocate(4).putInt(myPub.size).array()
                output.write(header)
                output.write(myPub)
                output.flush()

                val peerHeader = input.readNBytes(4)
                if (peerHeader.size < 4) return null
                val peerLen = ByteBuffer.wrap(peerHeader).int
                if (peerLen <= 0 || peerLen > 4096) return null
                val peerPubBytes = input.readNBytes(peerLen)
                if (peerPubBytes.size < peerLen) return null

                val keyFactory = KeyFactory.getInstance("EC")
                val peerPub: PublicKey = keyFactory.generatePublic(X509EncodedKeySpec(peerPubBytes))

                val agreement = KeyAgreement.getInstance("ECDH")
                agreement.init(keyPair.private)
                agreement.doPhase(peerPub, true)
                val sharedSecret = agreement.generateSecret()

                val aesKeyBytes = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
                CryptoSession(SecretKeySpec(aesKeyBytes, "AES"))
            } catch (e: Exception) {
                null
            }
        }
    }
}
