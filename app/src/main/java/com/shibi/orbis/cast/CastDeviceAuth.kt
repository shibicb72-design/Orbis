package com.shibi.orbis.cast

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

/*
 * CASTV2 device authentication (S-01). The TLS leaf on port 8009 is self-signed and regenerated every day or two, so
 * it cannot be pinned. The receiver's device certificate is stable: after the handshake the sender sends a nonce, and
 * the receiver signs nonce || TLS leaf DER with the device key and returns the device certificate. Orbis checks that
 * signature and pins the device certificate's public key per device id. It does not check the certificate chain
 * against Google's Cast root and sends nothing anywhere but the TV.
 */

internal const val NS_DEVICE_AUTH = "urn:x-cast:com.google.cast.tp.deviceauth"
internal const val DEVICE_AUTH_NONCE_BYTES = 16

/** The receiver must answer the challenge within this long, or the connection is dropped. */
internal const val DEVICE_AUTH_TIMEOUT_MS = 3_000L

/** SignatureAlgorithm values in the Cast channel protobuf. */
internal const val SIG_RSASSA_PKCS1V15 = 1
internal const val SIG_RSASSA_PSS = 2

/** HashAlgorithm values in the Cast channel protobuf. */
internal const val HASH_SHA1 = 0
internal const val HASH_SHA256 = 1

internal class AuthChallenge(
    val signatureAlgorithm: Int,
    val senderNonce: ByteArray,
    val hashAlgorithm: Int,
)

/** [signatureAlgorithm] and [hashAlgorithm] carry the protobuf defaults (PKCS#1 v1.5, SHA-1) when the field is absent. */
internal class AuthResponse(
    val signature: ByteArray,
    val clientAuthCertificate: ByteArray,
    val intermediateCertificates: List<ByteArray> = emptyList(),
    val signatureAlgorithm: Int = SIG_RSASSA_PKCS1V15,
    val senderNonce: ByteArray? = null,
    val hashAlgorithm: Int = HASH_SHA1,
)

/** DeviceAuthMessage: at most one of the three is set in practice. [errorType] is AuthError.error_type. */
internal class DeviceAuthMessage(
    val challenge: AuthChallenge? = null,
    val response: AuthResponse? = null,
    val errorType: Int? = null,
)

internal fun encodeDeviceAuthMessage(message: DeviceAuthMessage): ByteArray {
    val out = ByteArrayOutputStream()
    message.challenge?.let { challenge ->
        val body = ByteArrayOutputStream()
        writeVarintField(1, challenge.signatureAlgorithm, body)
        writeBytesField(2, challenge.senderNonce, body)
        writeVarintField(3, challenge.hashAlgorithm, body)
        writeBytesField(1, body.toByteArray(), out)
    }
    message.response?.let { response ->
        val body = ByteArrayOutputStream()
        writeBytesField(1, response.signature, body)
        writeBytesField(2, response.clientAuthCertificate, body)
        response.intermediateCertificates.forEach { writeBytesField(3, it, body) }
        writeVarintField(4, response.signatureAlgorithm, body)
        response.senderNonce?.let { writeBytesField(5, it, body) }
        writeVarintField(6, response.hashAlgorithm, body)
        writeBytesField(2, body.toByteArray(), out)
    }
    message.errorType?.let { error ->
        val body = ByteArrayOutputStream()
        writeVarintField(1, error, body)
        writeBytesField(3, body.toByteArray(), out)
    }
    return out.toByteArray()
}

/** Null when [bytes] is not a well-formed DeviceAuthMessage, or a response lacks its required fields. */
internal fun decodeDeviceAuthMessage(bytes: ByteArray): DeviceAuthMessage? {
    var challenge: AuthChallenge? = null
    var response: AuthResponse? = null
    var errorType: Int? = null
    val ok =
        forEachField(bytes) { field, _, slice ->
            when (field) {
                1 -> challenge = slice?.let(::decodeChallenge) ?: return@forEachField false
                2 -> response = slice?.let(::decodeResponse) ?: return@forEachField false
                3 -> errorType = slice?.let(::decodeErrorType) ?: return@forEachField false
            }
            true
        }
    return if (ok) DeviceAuthMessage(challenge, response, errorType) else null
}

/** The CASTV2 frame that asks the receiver to prove its device key, signed with PKCS#1 v1.5 over SHA-256. */
internal fun deviceAuthChallengeMessage(nonce: ByteArray): CastMessage =
    CastMessage(
        sourceId = CAST_SENDER_ID,
        destinationId = CAST_RECEIVER_ID,
        namespace = NS_DEVICE_AUTH,
        payloadUtf8 = "",
        payloadType = CastMessage.PAYLOAD_BINARY,
        payloadBinary =
            encodeDeviceAuthMessage(
                DeviceAuthMessage(challenge = AuthChallenge(SIG_RSASSA_PKCS1V15, nonce, HASH_SHA256)),
            ),
    )

/**
 * The SubjectPublicKeyInfo of the device certificate when [payload] is an AuthResponse that echoes [nonce] and whose
 * signature over [nonce] || [tlsLeafDer] verifies with that certificate's RSA key. Null for anything else, including
 * an AuthError.
 */
internal fun verifyDeviceAuth(payload: ByteArray, nonce: ByteArray, tlsLeafDer: ByteArray): ByteArray? =
    try {
        val response = decodeDeviceAuthMessage(payload)?.response
        val echoed = response?.senderNonce
        if (response == null || echoed == null || !MessageDigest.isEqual(echoed, nonce)) {
            null
        } else {
            val certificate =
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(response.clientAuthCertificate.inputStream()) as X509Certificate
            val key = certificate.publicKey as? RSAPublicKey
            val verifier = key?.let { verifierFor(response.signatureAlgorithm, response.hashAlgorithm) }
            if (key == null || verifier == null) {
                null
            } else {
                verifier.initVerify(key)
                verifier.update(nonce)
                verifier.update(tlsLeafDer)
                if (verifier.verify(response.signature)) key.encoded else null
            }
        }
    } catch (_: Exception) {
        null
    }

private fun verifierFor(signatureAlgorithm: Int, hashAlgorithm: Int): Signature? {
    val (name, digest) =
        when (hashAlgorithm) {
            HASH_SHA256 -> "SHA256" to "SHA-256"
            HASH_SHA1 -> "SHA1" to "SHA-1"
            else -> return null
        }
    return when (signatureAlgorithm) {
        SIG_RSASSA_PKCS1V15 -> Signature.getInstance("${name}withRSA")
        SIG_RSASSA_PSS -> pssVerifier(name, digest)
        else -> null
    }
}

/** RSASSA-PSS with MGF1 over the same digest and a salt as long as the digest. Android names it "SHA256withRSA/PSS". */
private fun pssVerifier(name: String, digest: String): Signature {
    runCatching { return Signature.getInstance("${name}withRSA/PSS") }
    val saltLength = MessageDigest.getInstance(digest).digestLength
    return Signature.getInstance("RSASSA-PSS").apply {
        setParameter(PSSParameterSpec(digest, "MGF1", MGF1ParameterSpec(digest), saltLength, 1))
    }
}

/**
 * Reads frames until the receiver's deviceauth reply arrives and returns its binary payload. Frames on other
 * namespaces are skipped. Null when the socket closes or nothing arrives within [DEVICE_AUTH_TIMEOUT_MS].
 */
internal fun awaitDeviceAuthReply(input: InputStream, nowMs: () -> Long = { System.currentTimeMillis() }): ByteArray? {
    val started = nowMs()
    while (nowMs() - started < DEVICE_AUTH_TIMEOUT_MS) {
        when (val read = readCastFrame(input, nowMs)) {
            Read.Tick -> Unit
            Read.Closed -> return null
            is Read.Frame ->
                if (read.message.namespace == NS_DEVICE_AUTH && read.message.payloadType == CastMessage.PAYLOAD_BINARY) {
                    return read.message.payloadBinary
                }
        }
    }
    return null
}

private fun decodeChallenge(bytes: ByteArray): AuthChallenge? {
    var signatureAlgorithm = SIG_RSASSA_PKCS1V15
    var nonce: ByteArray? = null
    var hashAlgorithm = HASH_SHA1
    val ok =
        forEachField(bytes) { field, varint, slice ->
            when (field) {
                1 -> signatureAlgorithm = varint ?: return@forEachField false
                2 -> nonce = slice ?: return@forEachField false
                3 -> hashAlgorithm = varint ?: return@forEachField false
            }
            true
        }
    return if (ok) AuthChallenge(signatureAlgorithm, nonce ?: CastMessage.NO_BYTES, hashAlgorithm) else null
}

private fun decodeResponse(bytes: ByteArray): AuthResponse? {
    var signature: ByteArray? = null
    var certificate: ByteArray? = null
    val intermediates = ArrayList<ByteArray>()
    var signatureAlgorithm = SIG_RSASSA_PKCS1V15
    var nonce: ByteArray? = null
    var hashAlgorithm = HASH_SHA1
    val ok =
        forEachField(bytes) { field, varint, slice ->
            when (field) {
                1 -> signature = slice ?: return@forEachField false
                2 -> certificate = slice ?: return@forEachField false
                3 -> intermediates += slice ?: return@forEachField false
                4 -> signatureAlgorithm = varint ?: return@forEachField false
                5 -> nonce = slice ?: return@forEachField false
                6 -> hashAlgorithm = varint ?: return@forEachField false
            }
            true
        }
    val sig = signature
    val cert = certificate
    if (!ok || sig == null || cert == null) return null
    return AuthResponse(sig, cert, intermediates, signatureAlgorithm, nonce, hashAlgorithm)
}

private fun decodeErrorType(bytes: ByteArray): Int? {
    var errorType: Int? = null
    val ok =
        forEachField(bytes) { field, varint, _ ->
            if (field == 1) errorType = varint ?: return@forEachField false
            true
        }
    return if (ok) errorType ?: 0 else null
}

/**
 * Walks the top-level fields of a protobuf message. [visit] gets a varint value or a length-delimited slice (the other
 * is null); fixed-width fields are skipped. Returns false when the bytes are malformed or [visit] returns false.
 */
private inline fun forEachField(bytes: ByteArray, visit: (field: Int, varint: Int?, slice: ByteArray?) -> Boolean): Boolean {
    var index = 0
    while (index < bytes.size) {
        val key = readVarint(bytes, index) ?: return false
        index = key.next
        val field = key.value ushr 3
        when (key.value and 7) {
            0 -> {
                val value = readVarint(bytes, index) ?: return false
                index = value.next
                if (!visit(field, value.value, null)) return false
            }
            1 -> {
                if (index + 8 > bytes.size) return false
                index += 8
            }
            5 -> {
                if (index + 4 > bytes.size) return false
                index += 4
            }
            2 -> {
                val length = readVarint(bytes, index) ?: return false
                index = length.next
                if (length.value < 0 || index + length.value > bytes.size) return false
                val slice = bytes.copyOfRange(index, index + length.value)
                index += length.value
                if (!visit(field, null, slice)) return false
            }
            else -> return false
        }
    }
    return true
}
