package com.unais.offlineconnect.protocol

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Message types per spec section 9. Only TEXT, DEVICE_INFO, PING, PONG, ACK, DISCONNECT
 * are actually handled by the transport/chat layer in this MVP (Phases 1-3).
 * VOICE_START/VOICE_DATA/VOICE_END/VOICE_MESSAGE/FILE are reserved here so the wire
 * format doesn't need to change when those phases are implemented, but sending them
 * is intentionally not wired up yet - no fake buttons, no half-built features.
 */
@Serializable
enum class MessageType {
    TEXT,
    VOICE_START,
    VOICE_DATA,
    VOICE_END,
    VOICE_MESSAGE,
    PING,
    PONG,
    DEVICE_INFO,
    ACK,
    FILE,
    DISCONNECT
}

/**
 * Common packet envelope for every message sent over the RFCOMM socket.
 * hopCount/ttl/senderId/destinationId are present now so the relay engine (Phase 7)
 * can be added without breaking the wire format, but hopCount is always 0 and ttl
 * always 1 in this MVP since there is no relay logic yet - every packet is direct.
 */
@Serializable
data class Packet(
    val packetId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val destinationId: String? = null, // null = direct peer, unused until relay mode
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = 1,
    val hopCount: Int = 0,
    val payload: String = "",
    val sequenceNumber: Int = 0 // reserved for voice packet ordering (Phase 5)
)
