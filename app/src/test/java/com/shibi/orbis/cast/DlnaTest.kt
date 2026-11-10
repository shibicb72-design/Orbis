package com.shibi.orbis.cast

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaTest {
    @Test
    fun parsesAnSsdpReply() {
        val raw =
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: http://192.168.1.20:8200/device.xml\r\n" +
                "SERVER: Linux/3 UPnP/1.0 OrbisTest/1\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "USN: uuid:abc-def::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "\r\n"
        val parsed = parseSsdpResponse(raw)
        assertEquals("http://192.168.1.20:8200/device.xml", parsed?.location)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", parsed?.searchTarget)
        assertEquals("abc-def", parsed?.uuid)
        assertTrue(parsed?.server.orEmpty().contains("UPnP"))
    }

    @Test
    fun ssdpHeadersAreCaseInsensitiveAndNotifyIsIgnored() {
        val raw =
            "HTTP/1.1 200 OK\n" +
                "Location: http://10.0.0.8:1234/desc.xml\n" +
                "St: urn:schemas-upnp-org:service:AVTransport:1\n" +
                "Usn: uuid:device-1::urn:schemas-upnp-org:service:AVTransport:1\n"
        val parsed = parseSsdpResponse(raw)
        assertEquals("http://10.0.0.8:1234/desc.xml", parsed?.location)
        assertEquals("device-1", parsed?.uuid)
        assertNull(parseSsdpResponse("NOTIFY * HTTP/1.1\r\nLOCATION: http://192.168.1.20/\r\n\r\n"))
        assertNull(parseSsdpResponse("HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\n\r\n"))
        assertNull(parseSsdpResponse("HTTP/1.1 404 Not Found\r\nLOCATION: http://192.168.1.20/\r\n\r\n"))
    }

    @Test
    fun mSearchNamesTheRenderer() {
        val packet = ssdpSearch(SSDP_MEDIA_RENDERER)
        assertTrue(packet.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(packet.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(packet.contains("ST: $SSDP_MEDIA_RENDERER\r\n"))
        assertTrue(packet.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(packet.endsWith("\r\n\r\n"))
    }

    @Test
    fun deviceDescriptionResolvesARelativeControlUrl() {
        val xml =
            """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>Living Room &amp; TV</friendlyName>
                <UDN>uuid:renderer-9</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/ctl/Rendering</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>ctl/AV</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
            """.trimIndent()
        val renderer = parseDlnaDescription(xml, "http://192.168.1.20:8200/device.xml")
        assertEquals("Living Room & TV", renderer?.name)
        assertEquals("renderer-9", renderer?.uuid)
        assertEquals("http://192.168.1.20:8200/ctl/AV", renderer?.avTransportUrl)
        assertEquals("http://192.168.1.20:8200/ctl/Rendering", renderer?.renderingControlUrl)
    }

    @Test
    fun descriptionWithoutAvTransportIsNotATarget() {
        val xml =
            """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device><friendlyName>Printer</friendlyName>
                <serviceList><service>
                  <serviceType>urn:schemas-upnp-org:service:PrintBasic:1</serviceType>
                  <controlURL>/print</controlURL>
                </service></serviceList>
              </device>
            </root>
            """.trimIndent()
        assertNull(parseDlnaDescription(xml, "http://192.168.1.9/device.xml"))
        val dtd = "<!DOCTYPE root [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root>&x;</root>"
        assertTrue(xmlHasDtd(dtd))
        assertTrue(xmlHasDtd("<root><!ENTITY x \"a\"></root>"))
        assertFalse(xmlHasDtd("<root><device><friendlyName>TV</friendlyName></device></root>"))
        assertNull(parseDlnaDescription(dtd, "http://10.0.0.1/d"))
    }

    @Test
    fun xmlEscapeKeepsEmojiAndDropsCharactersXmlForbids() {
        assertEquals("A \uD83C\uDFAC", xmlEscape("A \uD83C\uDFAC"))
        assertEquals("\uE000", xmlEscape("\uE000"))
        assertEquals("ok", xmlEscape("ok\u0000"))
        assertEquals("", xmlEscape("\uD800"))
        assertTrue(isXml10Char(0x1F3AC))
        assertFalse(isXml10Char(0xD800))
    }

    @Test
    fun didlEscapesTitleAndUrlAndPicksTheClass() {
        val video =
            CastableMedia(
                url = "https://cdn.example/a.mp4?x=1&y=2",
                mime = "video/mp4",
                video = true,
                title = "A & B <show>",
                poster = null,
                positionSec = 0.0,
                durationSec = 10.0,
            )
        val didl = buildDidlLite(video)
        assertTrue(didl.contains("<dc:title>A &amp; B &lt;show&gt;</dc:title>"))
        assertTrue(didl.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/mp4:*\""))
        assertTrue(didl.contains(">https://cdn.example/a.mp4?x=1&amp;y=2</res>"))
        val audio = video.copy(url = "https://cdn.example/a.mp3", mime = "audio/mpeg", video = false, title = "Song")
        val audioDidl = buildDidlLite(audio)
        assertTrue(audioDidl.contains("object.item.audioItem.musicTrack"))
        assertTrue(audioDidl.contains("http-get:*:audio/mpeg:*"))
        assertEquals("http-get:*:application/vnd.apple.mpegurl:*", didlProtocolInfo("application/vnd.apple.mpegurl"))
        assertEquals("http-get:*:application/dash+xml:*", didlProtocolInfo("application/dash+xml"))
        assertEquals("http-get:*:video/webm:*", didlProtocolInfo("video/webm"))
    }

    @Test
    fun soapSetAvTransportEscapesTheDidlAgain() {
        val didl = buildDidlLite(CastableMedia("https://cdn.example/a.mp4", "video/mp4", true, "Show", null, 0.0, 1.0))
        val call = soapSetAvTransportUri("https://cdn.example/a.mp4", didl)
        assertEquals("SetAVTransportURI", call.action)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", call.soapAction)
        assertTrue(call.body.contains("<CurrentURI>https://cdn.example/a.mp4</CurrentURI>"))
        assertTrue(call.body.contains("&amp;lt;DIDL-Lite") || call.body.contains("&lt;?xml"))
        assertFalse(call.body.contains("<DIDL-Lite"))
        assertTrue(soapPlay().body.contains("<u:Play"))
        assertTrue(soapPause().body.contains("<u:Pause"))
        assertTrue(soapStop().body.contains("<u:Stop"))
        val seek = soapSeek(90.0)
        assertTrue(seek.body.contains("<Unit>REL_TIME</Unit>"))
        assertTrue(seek.body.contains("<Target>00:01:30</Target>"))
        assertTrue(soapSetVolume(25).body.contains("<DesiredVolume>25</DesiredVolume>"))
        assertTrue(soapSetVolume(250).body.contains("<DesiredVolume>100</DesiredVolume>"))
        assertEquals("\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"", soapSetVolume(1).soapAction)
    }

    @Test
    fun relTimeAndTransportReplies() {
        assertEquals("01:02:03", formatRelTime(3723.0))
        assertEquals("00:00:00", formatRelTime(-4.0))
        assertEquals(3723.0, parseRelTime("01:02:03"))
        assertNull(parseRelTime("1:2"))
        assertNull(parseRelTime("00:60:00"))
        val position =
            parsePositionInfo(
                """<s:Body><u:GetPositionInfoResponse>
                    <RelTime>00:01:02</RelTime><TrackDuration>00:10:00</TrackDuration>
                   </u:GetPositionInfoResponse></s:Body>""",
            )
        assertEquals(62.0, position?.positionSec)
        assertEquals(600.0, position?.durationSec)
        assertEquals("PAUSED_PLAYBACK", parseTransportState("<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>"))
        assertEquals(40, parseVolume("<CurrentVolume>40</CurrentVolume>"))
    }

    @Test
    fun httpResponseParsing() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 5\r\n\r\nhelloEXTRA"
        val parsed = parseHttpResponse(raw.toByteArray())
        assertEquals(200, parsed?.code)
        assertEquals("text/xml", parsed?.headers?.get("content-type"))
        assertEquals("hello", parsed?.body)
        val request = String(encodeHttpRequest("POST", "/ctl/AV", "192.168.1.20:8200", mapOf("SOAPAction" to "\"urn#Play\""), 4))
        assertTrue(request.startsWith("POST /ctl/AV HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: 192.168.1.20:8200\r\n"))
        assertTrue(request.contains("SOAPAction: \"urn#Play\"\r\n"))
        assertTrue(request.contains("Content-Length: 4\r\n"))
    }

    @Test
    fun onlyLocalAddressesAreDialAndSoapTargets() {
        assertTrue(isLocalLanAddress(InetAddress.getByName("192.168.1.5")))
        assertTrue(isLocalLanAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(isLocalLanAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(isLocalLanAddress(InetAddress.getByName("169.254.1.1")))
        assertFalse(isLocalLanAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(isLocalLanAddress(InetAddress.getByName("::1")))
        assertFalse(isLocalLanAddress(InetAddress.getByName("172.32.0.1")))
        assertFalse(isLocalLanAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(isLocalLanAddress(InetAddress.getByName("239.255.255.250")))
        assertNotNull(localTarget("http://192.168.1.20:8200/ctl/AV"))
        assertEquals(8200, localTarget("http://192.168.1.20:8200/ctl/AV")?.port)
        assertNull(localTarget("http://8.8.8.8/ctl"))
        assertNull(localTarget("https://192.168.1.20/ctl"))
        assertNull(localTarget("http://example.com/device.xml"))
        assertNull(localTarget("http://127.0.0.1/device.xml"))
        assertNull(redirectTarget("http://192.168.1.20/device.xml", "http://8.8.8.8/evil.xml"))
        assertNull(redirectTarget("http://192.168.1.20/device.xml", "http://127.0.0.1/local.xml"))
        assertNull(redirectTarget("http://192.168.1.20/device.xml", "https://192.168.1.20/x"))
        assertNull(redirectTarget("http://192.168.1.20/device.xml", null))
        assertEquals(
            "http://192.168.1.21/desc.xml",
            redirectTarget("http://192.168.1.20/device.xml", "http://192.168.1.21/desc.xml"),
        )
    }

    @Test
    fun discoveryKeepsOnlyLocationsThatMatchTheReplySource() {
        fun hit(host: String, sourceHost: String = host) =
            SsdpHit(
                SsdpResponse("http://$host:8200/device.xml", SSDP_MEDIA_RENDERER, "", "Orbis", null),
                InetAddress.getByName(sourceHost),
            )
        val many = (1..20).map { hit("192.168.1.$it") }
        assertEquals(16, selectDiscoveryTargets(many).size)
        assertEquals("http://192.168.1.1:8200/device.xml", selectDiscoveryTargets(many).first().location)
        val one = hit("192.168.1.8")
        assertEquals(1, selectDiscoveryTargets(listOf(one, one)).size)
        assertTrue(selectDiscoveryTargets(listOf(hit("127.0.0.1"))).isEmpty())
        assertTrue(selectDiscoveryTargets(listOf(hit("10.0.0.5", "192.168.1.9"))).isEmpty())
        assertTrue(
            selectDiscoveryTargets(
                listOf(SsdpHit(SsdpResponse("http://tv.local/device.xml", "", "", "", null), InetAddress.getByName("192.168.1.9"))),
            ).isEmpty(),
        )
    }
}
