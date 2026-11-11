package com.shibi.orbis.cast

import java.net.InetAddress
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

internal const val SSDP_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
internal const val SSDP_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
internal const val SSDP_MULTICAST = "239.255.255.250"
internal const val SSDP_PORT = 1900

internal const val AV_TRANSPORT_URN = "urn:schemas-upnp-org:service:AVTransport:1"
internal const val RENDERING_CONTROL_URN = "urn:schemas-upnp-org:service:RenderingControl:1"

/** One SSDP reply. [location] is the device-description URL. */
internal data class SsdpResponse(
    val location: String,
    val searchTarget: String,
    val usn: String,
    val server: String,
    val uuid: String?,
)

/** A renderer we can tell to play. Control URLs are absolute. */
internal data class DlnaRenderer(
    val name: String,
    val uuid: String?,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
)

internal fun ssdpSearch(searchTarget: String, mxSeconds: Int = 2): String {
    val mx = mxSeconds.coerceIn(1, 5)
    return "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SSDP_MULTICAST:$SSDP_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $mx\r\n" +
        "ST: $searchTarget\r\n" +
        "\r\n"
}

/** Parses an SSDP `HTTP/1.1 200` reply. Anything else (including a NOTIFY advertisement) is ignored. */
internal fun parseSsdpResponse(raw: String): SsdpResponse? {
    if (raw.length > 16_384) return null
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val start = lines.firstOrNull()?.trim().orEmpty()
    if (!start.startsWith("HTTP/1.1 200") && !start.startsWith("HTTP/1.0 200")) return null
    val headers = LinkedHashMap<String, String>()
    for (line in lines.drop(1)) {
        if (line.isBlank()) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trim()
        if (name.isNotEmpty() && value.isNotEmpty() && name !in headers) headers[name] = value
    }
    val location = headers["location"] ?: return null
    if (!location.startsWith("http://") && !location.startsWith("https://")) return null
    if (location.length > 2048) return null
    val usn = headers["usn"].orEmpty()
    return SsdpResponse(
        location = location,
        searchTarget = headers["st"].orEmpty(),
        usn = usn,
        server = headers["server"].orEmpty().take(200),
        uuid = ssdpUuid(usn),
    )
}

internal fun ssdpUuid(usn: String): String? {
    val head = usn.substringBefore("::").trim()
    if (!head.startsWith("uuid:", ignoreCase = true)) return null
    val id = head.substring(5).trim()
    if (id.isEmpty() || id.length > 128) return null
    return id
}

/**
 * Reads a device description and returns a renderer only when it offers AVTransport.
 * [locationUrl] resolves a relative controlURL. Refuses a description that names an external entity.
 */
internal fun parseDlnaDescription(xml: String, locationUrl: String): DlnaRenderer? {
    if (xml.isBlank() || xml.length > 512 * 1024) return null
    // Android's DocumentBuilderFactory ignores the Xerces feature flags, so a DOCTYPE or entity is refused here,
    // before the parser sees the text.
    if (xmlHasDtd(xml)) return null
    val root =
        try {
            parseXml(xml)
        } catch (_: Exception) {
            return null
        } ?: return null
    var friendly = ""
    var udn: String? = null
    var av: String? = null
    var rendering: String? = null
    for (element in root.descendants()) {
        when (element.localNameSafe()) {
            "friendlyname" -> if (friendly.isEmpty()) friendly = element.textValue().take(80)
            "udn" -> if (udn == null) udn = ssdpUuid(element.textValue()) ?: element.textValue().removePrefix("uuid:").take(128).ifBlank { null }
            "service" -> {
                val type = element.childText("servicetype")
                val control = element.childText("controlurl")
                if (control.isEmpty()) continue
                if (av == null && type.contains("AVTransport", ignoreCase = true)) av = control
                if (rendering == null && type.contains("RenderingControl", ignoreCase = true)) rendering = control
            }
        }
    }
    val avUrl = av?.let { resolveControlUrl(locationUrl, it) } ?: return null
    val renderUrl = rendering?.let { resolveControlUrl(locationUrl, it) }
    val name = friendly.ifBlank { "Media renderer" }
    return DlnaRenderer(name = name, uuid = udn, avTransportUrl = avUrl, renderingControlUrl = renderUrl)
}

internal fun resolveControlUrl(locationUrl: String, control: String): String? {
    val ref = control.trim()
    if (ref.isEmpty() || ref.length > 2048) return null
    val resolved =
        try {
            URI(locationUrl).resolve(ref).toASCIIString()
        } catch (_: Exception) {
            return null
        }
    if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) return null
    // The control URL must name the same device that served the description, by IP literal (no DNS lookup).
    val host = uriHost(resolved) ?: return null
    if (!isIpLiteral(host) || !sameHost(host, uriHost(locationUrl))) return null
    return resolved
}

/** The host of [url] without IPv6 brackets, or null when it has none or does not parse. */
internal fun uriHost(url: String): String? =
    try {
        URI(url).host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

internal fun sameHost(a: String, b: String?): Boolean = b != null && a.equals(b, ignoreCase = true)

/** DIDL-Lite document for SetAVTransportURI. The title and URL are escaped. */
internal fun buildDidlLite(media: CastableMedia): String {
    val klass = if (media.video) "object.item.videoItem" else "object.item.audioItem.musicTrack"
    val protocol = didlProtocolInfo(media.mime)
    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
        "<dc:title>${xmlEscape(media.title.ifBlank { "Media" })}</dc:title>" +
        "<upnp:class>$klass</upnp:class>" +
        "<res protocolInfo=\"${xmlEscape(protocol)}\">${xmlEscape(media.url)}</res>" +
        "</item></DIDL-Lite>"
}

internal fun didlProtocolInfo(mime: String): String = "http-get:*:$mime:*"

internal data class SoapCall(val action: String, val serviceUrn: String, val body: String) {
    val soapAction: String get() = "\"$serviceUrn#$action\""
}

internal fun soapSetAvTransportUri(mediaUrl: String, didl: String): SoapCall =
    soapCall(
        "SetAVTransportURI",
        AV_TRANSPORT_URN,
        "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${xmlEscape(mediaUrl)}</CurrentURI>" +
            "<CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>",
    )

internal fun soapPlay(): SoapCall =
    soapCall("Play", AV_TRANSPORT_URN, "<InstanceID>0</InstanceID><Speed>1</Speed>")

internal fun soapPause(): SoapCall =
    soapCall("Pause", AV_TRANSPORT_URN, "<InstanceID>0</InstanceID>")

internal fun soapStop(): SoapCall =
    soapCall("Stop", AV_TRANSPORT_URN, "<InstanceID>0</InstanceID>")

internal fun soapSeek(positionSec: Double): SoapCall =
    soapCall(
        "Seek",
        AV_TRANSPORT_URN,
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${formatRelTime(positionSec)}</Target>",
    )

internal fun soapGetPositionInfo(): SoapCall =
    soapCall("GetPositionInfo", AV_TRANSPORT_URN, "<InstanceID>0</InstanceID>")

internal fun soapGetTransportInfo(): SoapCall =
    soapCall("GetTransportInfo", AV_TRANSPORT_URN, "<InstanceID>0</InstanceID>")

internal fun soapSetVolume(percent: Int): SoapCall =
    soapCall(
        "SetVolume",
        RENDERING_CONTROL_URN,
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${percent.coerceIn(0, 100)}</DesiredVolume>",
    )

internal fun soapGetVolume(): SoapCall =
    soapCall("GetVolume", RENDERING_CONTROL_URN, "<InstanceID>0</InstanceID><Channel>Master</Channel>")

/** `HH:MM:SS` as UPnP REL_TIME. */
internal fun formatRelTime(positionSec: Double): String {
    val total = positionSec.toLong().coerceIn(0L, 100L * 60 * 60)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

/** Parses `HH:MM:SS` or `H:MM:SS`. Null when the text is not a time. */
internal fun parseRelTime(value: String?): Double? {
    if (value.isNullOrBlank()) return null
    val parts = value.trim().split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toLongOrNull() ?: return null
    if (hours < 0 || minutes !in 0..59 || seconds !in 0..59) return null
    return (hours * 3600 + minutes * 60 + seconds).toDouble()
}

internal data class DlnaPosition(val positionSec: Double, val durationSec: Double)

internal fun parsePositionInfo(xml: String): DlnaPosition? {
    val rel = parseRelTime(soapText(xml, "RelTime")) ?: return null
    val duration = parseRelTime(soapText(xml, "TrackDuration")) ?: 0.0
    return DlnaPosition(rel, duration)
}

/** PLAYING, PAUSED_PLAYBACK, STOPPED, or null when the reply has no state. */
internal fun parseTransportState(xml: String): String? {
    val state = soapText(xml, "CurrentTransportState")?.trim()?.uppercase() ?: return null
    if (state.isEmpty() || state.length > 40) return null
    return state
}

internal fun parseVolume(xml: String): Int? =
    soapText(xml, "CurrentVolume")?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }

internal fun soapText(xml: String, localName: String): String? {
    if (!localName.all { it.isLetter() }) return null
    val pattern = Regex("""<(?:[\w.-]+:)?$localName(?:\s[^>]*)?>([^<]*)</(?:[\w.-]+:)?$localName>""", RegexOption.IGNORE_CASE)
    val raw = pattern.find(xml)?.groupValues?.get(1) ?: return null
    return xmlUnescape(raw).trim()
}

/** XML 1.0 Char, including non-characters in the private-use range and supplementary characters (emoji). */
internal fun isXml10Char(codePoint: Int): Boolean =
    codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
        codePoint in 0x20..0xD7FF ||
        codePoint in 0xE000..0xFFFD ||
        codePoint in 0x10000..0x10FFFF

internal fun xmlEscape(value: String): String =
    buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            when (codePoint) {
                '&'.code -> append("&amp;")
                '<'.code -> append("&lt;")
                '>'.code -> append("&gt;")
                '"'.code -> append("&quot;")
                '\''.code -> append("&apos;")
                else -> if (isXml10Char(codePoint)) appendCodePoint(codePoint)
            }
        }
    }

/** True when the text declares a DTD. Checked before parsing because Android's parser does not honor disallow-doctype. */
internal fun xmlHasDtd(xml: String): Boolean {
    val lower = xml.lowercase()
    return "<!doctype" in lower || "<!entity" in lower
}

internal fun xmlUnescape(value: String): String =
    value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

/**
 * True for link-local and private (RFC1918 / IPv6 ULA) addresses. Loopback is not a TV on the LAN.
 * Multicast and the wildcard address are not devices.
 */
internal fun isLocalLanAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLoopbackAddress) return false
    if (address.isLinkLocalAddress || address.isSiteLocalAddress) return true
    val bytes = address.address
    if (bytes.size == 16) {
        val first = bytes[0].toInt() and 0xFF
        if (first and 0xFE == 0xFC) return true
    }
    return false
}

private fun soapCall(action: String, urn: String, inner: String): SoapCall {
    val body =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$urn\">$inner</u:$action></s:Body></s:Envelope>"
    return SoapCall(action, urn, body)
}

/** An SSDP reply plus the address the packet came from. The description is fetched only when those two match. */
internal data class SsdpHit(val response: SsdpResponse, val source: InetAddress)

internal const val DLNA_MAX_LOCATIONS = 16

/**
 * Locations worth fetching: distinct, at most [limit], and only when the LOCATION host is the packet's source address.
 * A reply that names some other host (or a loopback address) is dropped.
 */
internal fun selectDiscoveryTargets(hits: List<SsdpHit>, limit: Int = DLNA_MAX_LOCATIONS): List<SsdpResponse> {
    val cap = limit.coerceAtLeast(0)
    val seen = HashSet<String>()
    val out = ArrayList<SsdpResponse>(cap.coerceAtMost(hits.size))
    for (hit in hits) {
        if (out.size >= cap) break
        if (!locationHostMatchesSource(hit.response.location, hit.source)) continue
        if (!seen.add(hit.response.location)) continue
        out += hit.response
    }
    return out
}

/** True when [location]'s host is an IP literal equal to [source], and [source] is a LAN address. */
internal fun locationHostMatchesSource(location: String, source: InetAddress): Boolean {
    if (!isLocalLanAddress(source)) return false
    val host =
        try {
            URI(location).host
        } catch (_: Exception) {
            null
        } ?: return false
    if (!isIpLiteral(host)) return false
    val parsed =
        try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            return false
        }
    return parsed.address.contentEquals(source.address)
}

internal fun isIpLiteral(host: String): Boolean {
    if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return true
    if (!host.contains(':')) return false
    return host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
}

private fun parseXml(xml: String): Element? {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isExpandEntityReferences = false
    runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
    val document = builder.parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    return document.documentElement
}

private fun Element.descendants(): List<Element> {
    val out = ArrayList<Element>()
    fun walk(node: Element) {
        out += node
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) walk(child)
        }
    }
    walk(this)
    return out
}

private fun Element.localNameSafe(): String = (localName ?: nodeName).substringAfter(':').lowercase()

private fun Element.childText(local: String): String {
    val children = childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child is Element && child.localNameSafe() == local) return child.textValue()
    }
    return ""
}

private fun Node.textValue(): String = textContent?.trim().orEmpty()
