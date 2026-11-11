package com.shibi.orbis.cast

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** S-01: each Chromecast's TLS certificate is pinned on first use and must match afterwards. */
class CastPinsTest {
    private class MemoryStore : CastPinStore {
        val map = HashMap<String, String>()

        override fun get(key: String): String? = map[key]

        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private val store = MemoryStore()
    private val pins = CastPins(store)

    @Test
    fun firstConnectionPinsAndIsAccepted() {
        PinnedCastTrust("tv-1", pins).checkServerTrusted(arrayOf(CERT_A), "ECDHE_ECDSA")
        assertEquals(1, store.map.size)
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", CERT_A.encoded))
    }

    @Test
    fun theSameCertificateIsAcceptedAgain() {
        PinnedCastTrust("tv-1", pins).checkServerTrusted(arrayOf(CERT_A), "ECDHE_ECDSA")
        val again = PinnedCastTrust("tv-1", pins)
        again.checkServerTrusted(arrayOf(CERT_A), "ECDHE_ECDSA")
        assertFalse(again.changed)
    }

    @Test
    fun aDifferentCertificateForTheSameDeviceIsRejectedAndThePinKept() {
        PinnedCastTrust("tv-1", pins).checkServerTrusted(arrayOf(CERT_A), "ECDHE_ECDSA")
        val pinned = store.map.toMap()
        val impostor = PinnedCastTrust("tv-1", pins)
        try {
            impostor.checkServerTrusted(arrayOf(CERT_B), "ECDHE_ECDSA")
            fail("a changed certificate must not be trusted")
        } catch (_: CastPinMismatchException) {
        }
        assertTrue(impostor.changed)
        assertEquals(pinned, store.map)
    }

    @Test
    fun devicesArePinnedIndependently() {
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-1", CERT_A.encoded))
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-2", CERT_B.encoded))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", CERT_A.encoded))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-2", CERT_B.encoded))
        assertEquals(CastPins.Verdict.CHANGED, pins.check("tv-2", CERT_A.encoded))
    }

    @Test
    fun forgetDropsThePinSoTheNextCertificateIsPinnedAnew() {
        pins.check("tv-1", CERT_A.encoded)
        pins.check("tv-2", CERT_A.encoded)
        pins.forget("tv-1")
        assertEquals(1, store.map.size)
        assertEquals(CastPins.Verdict.PINNED, pins.check("tv-1", CERT_B.encoded))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-1", CERT_B.encoded))
        assertEquals(CastPins.Verdict.MATCHED, pins.check("tv-2", CERT_A.encoded))
    }

    @Test
    fun anEmptyChainIsRejectedAndNothingIsPinned() {
        val trust = PinnedCastTrust("tv-1", pins)
        for (chain in listOf(emptyArray<X509Certificate>(), null)) {
            try {
                trust.checkServerTrusted(chain, "ECDHE_ECDSA")
                fail("an empty chain must not be trusted")
            } catch (e: CertificateException) {
                assertFalse(e is CastPinMismatchException)
            }
        }
        assertFalse(trust.changed)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun theStoreHoldsNeitherTheDeviceIdNorTheRawCertificate() {
        pins.check("tv-1", CERT_A.encoded)
        val (key, value) = store.map.entries.single()
        assertFalse(key.contains("tv-1"))
        assertEquals(64, key.length)
        assertEquals(64, value.length)
        assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun aMismatchMapsToTheUntrustedNotice() {
        val flow = CastFlow()
        flow.showPicker(youtube = false, searching = false)
        val generation = flow.beginConnect(0)
        assertTrue(flow.failConnect(generation, youtube = false, failure = CastNotice.UNTRUSTED))
        flow.updateSearching(searching = false, devicesEmpty = true)
        assertEquals(CastNotice.UNTRUSTED, flow.notice)
        flow.showNotice(null)
        assertNull(flow.notice)
    }

    private companion object {
        val CERT_A = cert(
            """
            MIIBfjCCASSgAwIBAgITJqXVAaQAaDHjb3ugKf5700vQzTAKBggqhkjOPQQDAjAU
            MRIwEAYDVQQDDAlUZXN0IFRWIGEwIBcNMjYxMTExMDMzNzMxWhgPMjEyNjEwMTgw
            MzM3MzFaMBQxEjAQBgNVBAMMCVRlc3QgVFYgYTBZMBMGByqGSM49AgEGCCqGSM49
            AwEHA0IABJpTrc18scdtrCMPeeit6GNCf34rF+UW1KM7ISIVWfZkvpM55DLAUiQk
            0cXLIQkzvrFpym7qC12fAgBTIRaBOSmjUzBRMB0GA1UdDgQWBBRTPSAUgPNlekPK
            4HIQip3CeYkGczAfBgNVHSMEGDAWgBRTPSAUgPNlekPK4HIQip3CeYkGczAPBgNV
            HRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0gAMEUCICYHOmgZ4bTQqEmbPHnHUoHf
            mUSF2nlHiG3pRPP2GJFkAiEA3yI/iIKAH8keMh9ddx57Yi7LieV3EMmWIGuxaSiq
            y6g=
            """,
        )

        val CERT_B = cert(
            """
            MIIBfzCCASWgAwIBAgIUfL3sR0hsKEq8OBNtizsHOH6ElQIwCgYIKoZIzj0EAwIw
            FDESMBAGA1UEAwwJVGVzdCBUViBiMCAXDTI2MTExMTAzMzczMVoYDzIxMjYxMDE4
            MDMzNzMxWjAUMRIwEAYDVQQDDAlUZXN0IFRWIGIwWTATBgcqhkjOPQIBBggqhkjO
            PQMBBwNCAASZEwGu01yhKjqA2PvG1+ir/kha4LBr4779Q1UCBL2YtPYQYo5RoIl1
            AutP+z2/kmwwA+8cwXLx0dK0qadUbz+mo1MwUTAdBgNVHQ4EFgQUzIyDUHQ18c9R
            EasCDA1BWCjLXj0wHwYDVR0jBBgwFoAUzIyDUHQ18c9REasCDA1BWCjLXj0wDwYD
            VR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiEAu0tvt/zLpWCy/9Ib8P75
            2wzd+mkEFYfdIg4fXanbF74CIFYvlzrtmzzWoWCLvjOLl50R6N2Q1o24pkMY+g/r
            bARe
            """,
        )

        /** Two throwaway self-signed test certificates ("Test TV a" and "Test TV b"); no private keys are kept. */
        fun cert(base64: String): X509Certificate {
            val pem = "-----BEGIN CERTIFICATE-----\n" + base64.trimIndent() + "\n-----END CERTIFICATE-----\n"
            return CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
        }
    }
}
