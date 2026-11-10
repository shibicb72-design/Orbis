package com.shibi.orbis.cast

import java.io.ByteArrayOutputStream

/** Largest CASTV2 body this client will encode or accept. Larger frames are treated as a broken stream. */
internal const val CAST_MAX_MESSAGE_BYTES = 256 * 1024

/**
 * One CASTV2 message. [protocolVersion] is the enum value from the Cast channel protobuf (0 = CASTV2_1_0).
 * Binary payloads are not used; a binary frame decodes with an empty [payloadUtf8].
 */
internal data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String,
    val protocolVersion: Int = 0,
    val payloadType: Int = PAYLOAD_STRING,
) {
    companion object {
        const val PAYLOAD_STRING = 0
        const val PAYLOAD_BINARY = 1
    }
}

/** Protobuf body of [message], without the 4-byte length prefix. */
internal fun encodeCastMessage(message: CastMessage): ByteArray {
    val out = ByteArrayOutputStream()
    writeVarintField(1, message.protocolVersion, out)
    writeStringField(2, message.sourceId, out)
    writeStringField(3, message.destinationId, out)
    writeStringField(4, message.namespace, out)
    writeVarintField(5, message.payloadType, out)
    if (message.payloadType == CastMessage.PAYLOAD_STRING) writeStringField(6, message.payloadUtf8, out)
    return out.toByteArray()
}

/** Length-prefixed frame: 4-byte big-endian size, then the protobuf body. */
internal fun frameCastMessage(message: CastMessage): ByteArray {
    val body = encodeCastMessage(message)
    require(body.size <= CAST_MAX_MESSAGE_BYTES)
    val framed = ByteArray(4 + body.size)
    val length = body.size
    framed[0] = (length ushr 24).toByte()
    framed[1] = (length ushr 16).toByte()
    framed[2] = (length ushr 8).toByte()
    framed[3] = length.toByte()
    body.copyInto(framed, destinationOffset = 4)
    return framed
}

/** The protobuf body, or null when the bytes are not a CASTV2 string or binary message. */
internal fun decodeCastMessage(body: ByteArray): CastMessage? {
    if (body.size > CAST_MAX_MESSAGE_BYTES) return null
    var index = 0
    var source: String? = null
    var destination: String? = null
    var namespace: String? = null
    var payload = ""
    var protocol = -1
    var payloadType = -1
    while (index < body.size) {
        val key = readVarint(body, index) ?: return null
        index = key.next
        val field = key.value ushr 3
        val wire = key.value and 7
        when (wire) {
            0 -> {
                val value = readVarint(body, index) ?: return null
                index = value.next
                when (field) {
                    1 -> protocol = value.value
                    5 -> payloadType = value.value
                }
            }
            1 -> {
                if (index + 8 > body.size) return null
                index += 8
            }
            5 -> {
                if (index + 4 > body.size) return null
                index += 4
            }
            2 -> {
                val length = readVarint(body, index) ?: return null
                index = length.next
                if (length.value < 0 || index + length.value > body.size) return null
                val slice = body.copyOfRange(index, index + length.value)
                index += length.value
                val text = String(slice, Charsets.UTF_8)
                when (field) {
                    2 -> source = text
                    3 -> destination = text
                    4 -> namespace = text
                    6 -> payload = text
                }
            }
            else -> return null
        }
    }
    if (source == null || destination == null || namespace == null) return null
    if (protocol < 0 || (payloadType != CastMessage.PAYLOAD_STRING && payloadType != CastMessage.PAYLOAD_BINARY)) return null
    return CastMessage(
        sourceId = source,
        destinationId = destination,
        namespace = namespace,
        payloadUtf8 = if (payloadType == CastMessage.PAYLOAD_STRING) payload else "",
        protocolVersion = protocol,
        payloadType = payloadType,
    )
}

/** How many bytes of [framed] form the next complete message, or null when more bytes are required. Negative when the length is illegal. */
internal fun castFrameLength(framed: ByteArray, offset: Int = 0): Int? {
    if (framed.size - offset < 4) return null
    val length =
        ((framed[offset].toInt() and 0xFF) shl 24) or
            ((framed[offset + 1].toInt() and 0xFF) shl 16) or
            ((framed[offset + 2].toInt() and 0xFF) shl 8) or
            (framed[offset + 3].toInt() and 0xFF)
    if (length <= 0 || length > CAST_MAX_MESSAGE_BYTES) return -1
    if (framed.size - offset < 4 + length) return null
    return length
}

private class Varint(val value: Int, val next: Int)

private fun writeVarintField(field: Int, value: Int, out: ByteArrayOutputStream) {
    writeVarint((field shl 3) or 0, out)
    writeVarint(value, out)
}

private fun writeStringField(field: Int, value: String, out: ByteArrayOutputStream) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeVarint((field shl 3) or 2, out)
    writeVarint(bytes.size, out)
    out.write(bytes)
}

private fun writeVarint(value: Int, out: ByteArrayOutputStream) {
    var rest = value
    while (rest and 0x7F.inv() != 0) {
        out.write((rest and 0x7F) or 0x80)
        rest = rest ushr 7
    }
    out.write(rest and 0x7F)
}

private fun readVarint(bytes: ByteArray, start: Int): Varint? {
    var result = 0
    var shift = 0
    var index = start
    while (index < bytes.size && shift <= 28) {
        val piece = bytes[index].toInt() and 0xFF
        result = result or ((piece and 0x7F) shl shift)
        index += 1
        if (piece and 0x80 == 0) return Varint(result, index)
        shift += 7
    }
    return null
}
