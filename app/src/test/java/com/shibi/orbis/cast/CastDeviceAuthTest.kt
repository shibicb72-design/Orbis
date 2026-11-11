package com.shibi.orbis.cast

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S-01: the deviceauth challenge, the AuthResponse check, and the identity-key pin built on it. */
class CastDeviceAuthTest {
    private val nonce = ByteArray(DEVICE_AUTH_NONCE_BYTES) { (it * 7 + 1).toByte() }
    private val tlsLeaf = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x05)

    @Test
    fun theChallengeIsABinaryDeviceAuthFrameAskingForPkcs1OverSha256() {
        val message = deviceAuthChallengeMessage(nonce)
        val framed = frameCastMessage(message)
        val decoded = decodeCastMessage(framed.copyOfRange(4, framed.size))!!
        assertEquals(message, decoded)
        assertEquals(CAST_SENDER_ID, decoded.sourceId)
        assertEquals(CAST_RECEIVER_ID, decoded.destinationId)
        assertEquals(NS_DEVICE_AUTH, decoded.namespace)
        assertEquals(CastMessage.PAYLOAD_BINARY, decoded.payloadType)
        // DeviceAuthMessage{challenge(1){signature_algorithm(1)=1, sender_nonce(2)=16 bytes, hash_algorithm(3)=1}}
        val expected = byteArrayOf(0x0a, 0x16, 0x08, 0x01, 0x12, 0x10) + nonce + byteArrayOf(0x18, 0x01)
        assertArrayEquals(expected, decoded.payloadBinary)
        val challenge = decodeDeviceAuthMessage(decoded.payloadBinary)!!.challenge!!
        assertEquals(SIG_RSASSA_PKCS1V15, challenge.signatureAlgorithm)
        assertEquals(HASH_SHA256, challenge.hashAlgorithm)
        assertArrayEquals(nonce, challenge.senderNonce)
    }

    @Test
    fun anAuthResponseRoundTrips() {
        val response =
            AuthResponse(
                signature = byteArrayOf(1, 2, 3),
                clientAuthCertificate = ByteArray(300) { it.toByte() },
                intermediateCertificates = listOf(byteArrayOf(9), byteArrayOf(8, 7)),
                signatureAlgorithm = SIG_RSASSA_PSS,
                senderNonce = nonce,
                hashAlgorithm = HASH_SHA256,
            )
        val decoded = decodeDeviceAuthMessage(encodeDeviceAuthMessage(DeviceAuthMessage(response = response)))!!
        assertNull(decoded.challenge)
        assertNull(decoded.errorType)
        val back = decoded.response!!
        assertArrayEquals(response.signature, back.signature)
        assertArrayEquals(response.clientAuthCertificate, back.clientAuthCertificate)
        assertEquals(2, back.intermediateCertificates.size)
        assertArrayEquals(byteArrayOf(8, 7), back.intermediateCertificates[1])
        assertEquals(SIG_RSASSA_PSS, back.signatureAlgorithm)
        assertArrayEquals(nonce, back.senderNonce)
        assertEquals(HASH_SHA256, back.hashAlgorithm)
    }

    @Test
    fun anAbsentAlgorithmFieldMeansThePkcs1Sha1DefaultAndMissingRequiredFieldsAreRefused() {
        // AuthResponse{signature(1)=[1], client_auth_certificate(2)=[2]} with no algorithm fields.
        val minimal = byteArrayOf(0x12, 0x06, 0x0a, 0x01, 0x01, 0x12, 0x01, 0x02)
        val response = decodeDeviceAuthMessage(minimal)!!.response!!
        assertEquals(SIG_RSASSA_PKCS1V15, response.signatureAlgorithm)
        assertEquals(HASH_SHA1, response.hashAlgorithm)
        assertNull(response.senderNonce)
        assertNull(decodeDeviceAuthMessage(byteArrayOf(0x12, 0x03, 0x0a, 0x01, 0x01)))
        assertNull(decodeDeviceAuthMessage(byteArrayOf(0x12, 0x09, 0x0a)))
    }

    @Test
    fun aGenuineResponseYieldsTheDeviceKey() {
        val payload = signedResponse(TV_A)
        val identity = verifyDeviceAuth(payload, nonce, tlsLeaf)!!
        assertArrayEquals(TV_A.keys.public.encoded, identity.spki)
        assertFalse(identity.legacy)
    }

    @Test
    fun sha1AndPssResponsesAreAcceptedWhenTheTvSaysSo() {
        assertNotNull(verifyDeviceAuth(signedResponse(TV_A, hash = HASH_SHA1), nonce, tlsLeaf))
        assertNotNull(verifyDeviceAuth(signedResponse(TV_A, algorithm = SIG_RSASSA_PSS), nonce, tlsLeaf))
        // Signed with PKCS#1 v1.5 but labelled PSS: refused.
        val mislabelled = signedResponse(TV_A, labelAlgorithm = SIG_RSASSA_PSS)
        assertNull(verifyDeviceAuth(mislabelled, nonce, tlsLeaf))
    }

    @Test
    fun aSignatureOverAnotherTlsCertificateIsRefused() {
        val relayed = signedResponse(TV_A, signedLeaf = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x06))
        assertNull(verifyDeviceAuth(relayed, nonce, tlsLeaf))
    }

    @Test
    fun aSignatureOverAnotherNonceIsRefusedEvenWhenTheNonceIsEchoed() {
        val replayed = signedResponse(TV_A, signedNonce = ByteArray(DEVICE_AUTH_NONCE_BYTES))
        assertNull(verifyDeviceAuth(replayed, nonce, tlsLeaf))
    }

    @Test
    fun aWrongEchoedNonceIsRefused() {
        assertNull(verifyDeviceAuth(signedResponse(TV_A, echoedNonce = ByteArray(DEVICE_AUTH_NONCE_BYTES)), nonce, tlsLeaf))
        // A wrong echo is refused even when the signature would pass the legacy leaf-only check.
        val leafOnly = signedResponse(TV_A, signedNonce = ByteArray(0), echoedNonce = ByteArray(DEVICE_AUTH_NONCE_BYTES))
        assertNull(verifyDeviceAuth(leafOnly, nonce, tlsLeaf))
    }

    @Test
    fun aTvThatEchoesNoNonceIsAcceptedWhenItSignedNonceAndLeaf() {
        val identity = verifyDeviceAuth(signedResponse(TV_A, echoedNonce = null), nonce, tlsLeaf)!!
        assertArrayEquals(TV_A.keys.public.encoded, identity.spki)
        assertFalse(identity.legacy)
    }

    @Test
    fun legacyFirmwareThatEchoesNoNonceAndSignsOnlyTheLeafIsAccepted() {
        val legacy = signedResponse(TV_A, signedNonce = ByteArray(0), echoedNonce = null)
        val identity = verifyDeviceAuth(legacy, nonce, tlsLeaf)!!
        assertArrayEquals(TV_A.keys.public.encoded, identity.spki)
        assertTrue(identity.legacy)
        // With the nonce echoed, a leaf-only signature is not enough.
        assertNull(verifyDeviceAuth(signedResponse(TV_A, signedNonce = ByteArray(0)), nonce, tlsLeaf))
    }

    @Test
    fun aTvThatEchoesNoNonceIsRefusedWhenItSignedAnythingElse() {
        val otherNonce = signedResponse(TV_A, signedNonce = ByteArray(DEVICE_AUTH_NONCE_BYTES), echoedNonce = null)
        assertNull(verifyDeviceAuth(otherNonce, nonce, tlsLeaf))
        val otherLeaf = signedResponse(TV_A, signedNonce = ByteArray(0), echoedNonce = null, signedLeaf = byteArrayOf(0x30, 0x00))
        assertNull(verifyDeviceAuth(otherLeaf, nonce, tlsLeaf))
        val noncePlusOtherLeaf = signedResponse(TV_A, echoedNonce = null, signedLeaf = byteArrayOf(0x30, 0x00))
        assertNull(verifyDeviceAuth(noncePlusOtherLeaf, nonce, tlsLeaf))
        val forged = signedResponse(certificateOf = TV_A, signer = TV_B.keys, signedNonce = ByteArray(0), echoedNonce = null)
        assertNull(verifyDeviceAuth(forged, nonce, tlsLeaf))
    }

    @Test
    fun aSignatureFromAKeyOtherThanTheCertificatesIsRefused() {
        val forged = signedResponse(certificateOf = TV_A, signer = TV_B.keys)
        assertNull(verifyDeviceAuth(forged, nonce, tlsLeaf))
    }

    @Test
    fun anAuthErrorOrGarbageIsRefused() {
        val error = encodeDeviceAuthMessage(DeviceAuthMessage(errorType = 1))
        assertEquals(1, decodeDeviceAuthMessage(error)!!.errorType)
        assertNull(verifyDeviceAuth(error, nonce, tlsLeaf))
        assertNull(verifyDeviceAuth(byteArrayOf(0x7f, 0x7f), nonce, tlsLeaf))
        val badCertificate =
            encodeDeviceAuthMessage(
                DeviceAuthMessage(
                    response = AuthResponse(byteArrayOf(1), byteArrayOf(0x30, 0x00), senderNonce = nonce, hashAlgorithm = HASH_SHA256),
                ),
            )
        assertNull(verifyDeviceAuth(badCertificate, nonce, tlsLeaf))
    }

    @Test
    fun theIdentityKeyPinLivesAcrossTlsRegenerationAndCatchesAnotherKey() {
        val pins = CastPins(MemoryPinStore())
        fun connect(tv: TestTv, leaf: ByteArray, now: Long): CastPins.Verdict {
            val key = verifyDeviceAuth(signedResponse(tv, signedLeaf = leaf), nonce, leaf)!!.spki
            return pins.check("tv-1", "Living Room", key, now)
        }
        val today = byteArrayOf(0x30, 0x01, 0x01)
        val tomorrow = byteArrayOf(0x30, 0x01, 0x02)
        val first = connect(TV_A, today, 0) as CastPins.Verdict.NeedsConfirm
        assertEquals(CastPins.Reason.FIRST_USE, first.reason)
        pins.approve(first.ticket, 0)
        assertEquals(CastPins.Verdict.PINNED, connect(TV_A, today, 1))
        // The receiver regenerated its TLS certificate; the device key is the same.
        assertEquals(CastPins.Verdict.MATCHED, connect(TV_A, tomorrow, 2))
        assertEquals(CastPins.Reason.KEY_CHANGED, (connect(TV_B, tomorrow, 3) as CastPins.Verdict.NeedsConfirm).reason)
        assertEquals(CastPins.Verdict.MATCHED, connect(TV_A, today, 4))
    }

    /** What a TV on the far end of the socket sees: every frame Orbis wrote, and its scripted reply. */
    private class Wire(reply: ByteArray) {
        val written = ByteArrayOutputStream()
        val input = reply.inputStream()
    }

    private fun authFrame(payload: ByteArray): ByteArray =
        frameCastMessage(
            CastMessage(CAST_RECEIVER_ID, CAST_SENDER_ID, NS_DEVICE_AUTH, "", payloadType = CastMessage.PAYLOAD_BINARY, payloadBinary = payload),
        )

    private fun framesIn(bytes: ByteArray): List<CastMessage> {
        val out = ArrayList<CastMessage>()
        var offset = 0
        while (offset < bytes.size) {
            val length = castDeclaredLength(bytes.copyOfRange(offset, offset + 4))
            out += decodeCastMessage(bytes.copyOfRange(offset + 4, offset + 4 + length))!!
            offset += 4 + length
        }
        return out
    }

    @Test
    fun anUnconfirmedDeviceReceivesOnlyTheDeviceAuthChallenge() {
        val pins = CastPins(MemoryPinStore())
        val wire = Wire(authFrame(signedResponse(TV_A)))
        val result = authenticateCastPeer(wire.input, wire.written, tlsLeaf, nonce, "tv-1", "Living Room", pins) { 0L }
        assertTrue("first connection asks: $result", result is CastPeerAuth.NeedsTrust)
        assertEquals(CastPins.Reason.FIRST_USE, (result as CastPeerAuth.NeedsTrust).reason)
        // The only bytes the device got are the one challenge frame: no CONNECT, no LAUNCH, no media address.
        assertArrayEquals(frameCastMessage(deviceAuthChallengeMessage(nonce)), wire.written.toByteArray())
        assertEquals(listOf(NS_DEVICE_AUTH), framesIn(wire.written.toByteArray()).map { it.namespace })
    }

    @Test
    fun afterConfirmTheSameKeyAndNameIsTrustedAndAnotherKeyAsksAgain() {
        val pins = CastPins(MemoryPinStore())
        val ask = authenticateCastPeer(Wire(authFrame(signedResponse(TV_A))).input, ByteArrayOutputStream(), tlsLeaf, nonce, "tv-1", "Living Room", pins) { 0L }
        pins.approve((ask as CastPeerAuth.NeedsTrust).ticket, 0L)
        // The reconnect after "Trust and cast" is answered by a different device: never auto-approved.
        val impostor = Wire(authFrame(signedResponse(TV_B)))
        val second = authenticateCastPeer(impostor.input, impostor.written, tlsLeaf, nonce, "tv-1", "Living Room", pins) { 1L }
        assertTrue(second is CastPeerAuth.NeedsTrust)
        assertEquals(1, framesIn(impostor.written.toByteArray()).size)
        // The real TV then proves the approved key.
        val real = Wire(authFrame(signedResponse(TV_A)))
        val third = authenticateCastPeer(real.input, real.written, tlsLeaf, nonce, "tv-1", "Living Room", pins) { 2L }
        assertEquals(CastPeerAuth.Trusted(CastPins.Verdict.PINNED, legacy = false), third)
        val again = Wire(authFrame(signedResponse(TV_A)))
        assertEquals(
            CastPeerAuth.Trusted(CastPins.Verdict.MATCHED, legacy = false),
            authenticateCastPeer(again.input, again.written, tlsLeaf, nonce, "tv-1", "Living Room", pins) { 3L },
        )
    }

    @Test
    fun aBadOrMissingReplyIsRefusedWithoutTouchingThePins() {
        val store = MemoryPinStore()
        val pins = CastPins(store)
        val forged = Wire(authFrame(signedResponse(certificateOf = TV_A, signer = TV_B.keys)))
        assertEquals(CastPeerAuth.AuthFailed, authenticateCastPeer(forged.input, forged.written, tlsLeaf, nonce, "tv-1", "TV", pins) { 0L })
        val silent = Wire(ByteArray(0))
        assertEquals(CastPeerAuth.AuthNone, authenticateCastPeer(silent.input, silent.written, tlsLeaf, nonce, "tv-1", "TV", pins) { 0L })
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun theReplyIsReadPastOtherFramesAndSilenceGivesUpAfterThreeSeconds() {
        val reply = signedResponse(TV_A)
        val other = frameCastMessage(CastMessage(CAST_RECEIVER_ID, CAST_SENDER_ID, NS_HEARTBEAT, """{"type":"PING"}"""))
        val auth =
            frameCastMessage(
                CastMessage(
                    CAST_RECEIVER_ID,
                    CAST_SENDER_ID,
                    NS_DEVICE_AUTH,
                    "",
                    payloadType = CastMessage.PAYLOAD_BINARY,
                    payloadBinary = reply,
                ),
            )
        assertArrayEquals(reply, awaitDeviceAuthReply((other + auth).inputStream()))

        var now = 0L
        val silent = ScriptedInput(onTimeout = { now += 400 })
        assertNull(awaitDeviceAuthReply(silent, nowMs = { now }))
        // Gives up on the first idle tick at or past the deadline (ticks are 400 ms apart here).
        assertEquals(3_200L, now)

        assertNull(awaitDeviceAuthReply(ByteArray(0).inputStream()))
    }

    private fun signedResponse(
        certificateOf: TestTv,
        signer: KeyPair = certificateOf.keys,
        algorithm: Int = SIG_RSASSA_PKCS1V15,
        labelAlgorithm: Int = algorithm,
        hash: Int = HASH_SHA256,
        signedNonce: ByteArray = nonce,
        echoedNonce: ByteArray? = nonce,
        signedLeaf: ByteArray = tlsLeaf,
    ): ByteArray {
        val signature =
            sign(signer, algorithm, hash, signedNonce + signedLeaf)
        return encodeDeviceAuthMessage(
            DeviceAuthMessage(
                response =
                    AuthResponse(
                        signature = signature,
                        clientAuthCertificate = certificateOf.certificate,
                        signatureAlgorithm = labelAlgorithm,
                        senderNonce = echoedNonce,
                        hashAlgorithm = hash,
                    ),
            ),
        )
    }

    private class TestTv(val keys: KeyPair, val certificate: ByteArray)

    private class MemoryPinStore : CastPinStore {
        private val map = HashMap<String, String>()

        override fun get(key: String): String? = map[key]

        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun all(): Map<String, String> = map.toMap()
    }

    /** Every read times out, as a quiet socket with soTimeout set does. */
    private class ScriptedInput(private val onTimeout: () -> Unit) : InputStream() {
        override fun read(): Int = read(ByteArray(1), 0, 1)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            onTimeout()
            throw SocketTimeoutException("idle")
        }
    }

    private companion object {
        /** Two throwaway RSA keys made for this test run, each in a minimal self-signed X.509 device certificate. */
        val TV_A = testTv("Test TV a")
        val TV_B = testTv("Test TV b")

        fun testTv(name: String): TestTv {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return TestTv(keys, selfSignedCertificate(keys, name))
        }

        fun sign(keys: KeyPair, algorithm: Int, hash: Int, data: ByteArray): ByteArray {
            val digest = if (hash == HASH_SHA256) "SHA-256" else "SHA-1"
            val signer =
                if (algorithm == SIG_RSASSA_PSS) {
                    Signature.getInstance("RSASSA-PSS").apply {
                        setParameter(PSSParameterSpec(digest, "MGF1", MGF1ParameterSpec(digest), if (hash == HASH_SHA256) 32 else 20, 1))
                    }
                } else {
                    Signature.getInstance(if (hash == HASH_SHA256) "SHA256withRSA" else "SHA1withRSA")
                }
            signer.initSign(keys.private)
            signer.update(data)
            return signer.sign()
        }

        fun selfSignedCertificate(keys: KeyPair, commonName: String): ByteArray {
            val sha256WithRsa = der(0x30, der(0x06, hex("2a864886f70d01010b")) + der(0x05, ByteArray(0)))
            val name =
                der(0x30, der(0x31, der(0x30, der(0x06, hex("550403")) + der(0x0c, commonName.toByteArray()))))
            val validity = der(0x30, der(0x17, "260101000000Z".toByteArray()) + der(0x17, "360101000000Z".toByteArray()))
            val tbs =
                der(
                    0x30,
                    der(0xa0, der(0x02, byteArrayOf(2))) +
                        der(0x02, BigInteger.valueOf(commonName.hashCode().toLong() and 0x7fffffff).toByteArray()) +
                        sha256WithRsa + name + validity + name + keys.public.encoded,
                )
            val signature = sign(keys, SIG_RSASSA_PKCS1V15, HASH_SHA256, tbs)
            return der(0x30, tbs + sha256WithRsa + der(0x03, byteArrayOf(0) + signature))
        }

        fun der(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val size = content.size
            when {
                size < 0x80 -> out.write(size)
                size < 0x100 -> {
                    out.write(0x81)
                    out.write(size)
                }
                else -> {
                    out.write(0x82)
                    out.write(size ushr 8)
                    out.write(size and 0xff)
                }
            }
            out.write(content)
            return out.toByteArray()
        }

        fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
