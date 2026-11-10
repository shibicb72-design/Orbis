package com.shibi.orbis.cast

import java.net.InetAddress

/** Friendly name (`fn`), model (`md`) and id from a `_googlecast._tcp` TXT record. */
internal data class CastTxt(val id: String, val name: String, val model: String)

internal data class ChromecastDevice(
    val id: String,
    val name: String,
    val model: String,
    val address: InetAddress,
    val serviceName: String,
)

/**
 * Reads the Cast TXT record. [serviceName] is the DNS-SD instance name, used when `fn` is missing.
 * Values are UTF-8. A trailing NUL, which some devices append, is dropped.
 */
internal fun parseCastTxt(attributes: Map<String, ByteArray>, serviceName: String): CastTxt {
    val friendly = txtValue(attributes, "fn")
    val model = txtValue(attributes, "md")
    val id = txtValue(attributes, "id")
    val name = friendly.ifBlank { serviceName.trim() }.ifBlank { "Chromecast" }
    return CastTxt(id = id, name = name, model = model)
}

/**
 * The first host that is a private LAN address. Loopback and public addresses are refused, even when the TXT
 * record itself parsed.
 */
internal fun castDeviceFromTxt(
    attributes: Map<String, ByteArray>,
    serviceName: String,
    hosts: List<InetAddress>,
): ChromecastDevice? {
    val address = hosts.firstOrNull { isLocalLanAddress(it) } ?: return null
    val txt = parseCastTxt(attributes, serviceName)
    return ChromecastDevice(
        id = txt.id.ifBlank { address.hostAddress?.substringBefore('%').orEmpty() }.ifBlank { txt.name },
        name = txt.name,
        model = txt.model,
        address = address,
        serviceName = serviceName,
    )
}

private fun txtValue(attributes: Map<String, ByteArray>, key: String): String {
    val bytes = attributes[key] ?: attributes.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    if (bytes == null || bytes.isEmpty()) return ""
    return String(bytes, Charsets.UTF_8).trim().trim('\u0000')
}
