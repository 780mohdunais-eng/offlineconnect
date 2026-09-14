package com.unais.offlineconnect.protocol

import com.unais.offlineconnect.crypto.CryptoSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object PacketCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(packet: Packet, session: CryptoSession? = null): ByteArray {
        val plain = json.encodeToString(packet).toByteArray(StandardCharsets.UTF_8)
        val body = session?.encrypt(plain) ?: plain
        val header = ByteBuffer.allocate(4).putInt(body.size).array()
        return header + body
    }

    fun write(out: OutputStream, packet: Packet, session: CryptoSession? = null) {
        out.write(encode(packet, session))
        out.flush()
    }

    fun readOne(input: InputStream, session: CryptoSession? = null): Packet? {
        val header = input.readNBytes(4)
        if (header.size < 4) return null
        val length = ByteBuffer.wrap(header).int
        if (length <= 0 || length > 2_000_000) return null
        val body = input.readNBytes(length)
        if (body.size < length) return null
        val plain = if (session != null) session.decrypt(body) else body
        if (plain == null) return null
        return try {
            json.decodeFromString(Packet.serializer(), String(plain, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }
}

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
