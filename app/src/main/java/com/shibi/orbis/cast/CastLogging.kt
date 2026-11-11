package com.shibi.orbis.cast

import java.security.MessageDigest
import org.json.JSONObject

/*
 * Helpers for the "Cast" lines in the debug log. A line may carry a device hash, the mDNS model, app ids, CASTV2
 * message types, namespaces, HTTP statuses, counts, durations, booleans, exception class names and generation
 * numbers. It never carries an IP address, a friendly name, a video id, a URL, a title, a lounge screen id, token,
 * SID or gsessionid, or a request or response body.
 */

/** First 6 hex characters of the SHA-256 of a device id (mDNS id, or DLNA uuid or control URL). */
internal fun deviceTag(id: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
    return digest.take(3).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

internal fun errName(t: Throwable): String = t.javaClass.simpleName

/** ASCII letters, digits and underscore only, capped. For message types and app ids that came from the device. */
internal fun logToken(value: String, max: Int = 32): String =
    value.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }.take(max)

/** The mDNS model string, reduced to plain characters and capped. */
internal fun logModel(value: String): String =
    value
        .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '-' || it == '_' || it == '.' }
        .trim()
        .take(40)

/** The log line for a frame the device sent on [namespace], or null when it is not worth a line. */
internal fun castRxLine(namespace: String, payload: String): String? {
    parseCastError(payload)?.let { return "rx error type=${logToken(it)}" }
    if (namespace != NS_RECEIVER) return null
    val snapshot = parseReceiverStatus(payload)
    if (snapshot != null) {
        val apps = snapshot.applications.joinToString(",") { logToken(it.appId) }.ifEmpty { "none" }
        return "rx receiver type=RECEIVER_STATUS apps=$apps reqId=${snapshot.requestId}"
    }
    val type = logToken(payloadType(payload)).ifEmpty { "unknown" }
    return "rx receiver type=$type"
}

/** The log line for a receiver-namespace frame the phone sends. Volume changes are not logged. */
internal fun castTxLine(namespace: String, payload: String): String? {
    if (namespace != NS_RECEIVER) return null
    val root = jsonObjectOrNull(payload) ?: return null
    val type = logToken(root.optString("type"))
    if (type.isEmpty() || type == "SET_VOLUME") return null
    val app = logToken(root.optString("appId")).let { if (it.isEmpty()) "" else " app=$it" }
    return "tx receiver type=$type$app reqId=${root.optInt("requestId")}"
}

internal fun payloadType(payload: String): String = jsonObjectOrNull(payload)?.optString("type").orEmpty()

private fun jsonObjectOrNull(payload: String): JSONObject? =
    try {
        JSONObject(payload)
    } catch (_: Exception) {
        null
    }
