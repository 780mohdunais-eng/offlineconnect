package com.unais.offlineconnect.protocol

import com.unais.offlineconnect.crypto.CryptoSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Frames each Packet as [4-byte length][bytes] over the RFCOMM stream. If a
 * CryptoSession is supplied, the bytes are the AES-GCM ciphertext of the packet's
 * JSON (encrypt-then-frame); otherwise they're the raw JSON (only used for the
 * pre-handshake bootstrap, never for actual message content).
 */
object PacketCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(packet: Packet, session: CryptoSession?): ByteArray {
        val plain = json.encodeToString(packet).toByteArray(StandardCharsets.UTF_8)
        val body = session?.encrypt(plain) ?: plain
        val header = ByteBuffer.allocate(4).putInt(body.size).array()
        return header + body
    }

    fun write(out: OutputStream, packet: Packet, session: CryptoSession?) {
        out.write(encode(packet, session))
        out.flush()
    }

    /** Blocks until one full packet is read, or returns null if the stream ended/failed. */
    fun readOne(input: InputStream, session: CryptoSession?): Packet? {
        val header = input.readNBytes(4)
        if (header.size < 4) return null
        val length = ByteBuffer.wrap(header).int
        if (length <= 0 || length > 2_000_000) return null // guard against corrupt length
        val body = input.readNBytes(length)
        if (body.size < length) return null
        val plain = if (session != null) session.decrypt(body) else body
        if (plain == null) return null
        return try {
            json.decodeFromString(Packet.serializer(), String(plain, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            null // corrupted/invalid packet - dropped, never crashes the app (spec section 24)
        }
    }
}

/**
 * Small ring buffer of recently seen packet IDs, used to avoid re-processing or
 * re-forwarding the same packet twice (spec section 7/9).
 */
class DuplicateCache(private val capacity: Int = 500) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun isDuplicate(packetId: String): Boolean {
        if (seen.contains(packetId)) return true
        seen.add(packetId)
        if (seen.size > capacity) {
            seen.remove(seen.first())
        }
        return false
    }
}
