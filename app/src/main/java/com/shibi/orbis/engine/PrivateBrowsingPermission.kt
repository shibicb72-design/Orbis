package com.shibi.orbis.engine

import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * True when the private-browsing permission has to be written. GeckoView reloads an active add-on on every
 * `setAllowedInPrivateBrowsing` call, even with an unchanged value, so a matching value must not be written again.
 */
internal fun privateBrowsingChangeNeeded(current: Boolean, wanted: Boolean): Boolean = current != wanted

/**
 * Sets the private-browsing permission of [ext] to [wanted], only when it differs from the current one. The result is
 * the extension to use afterwards (the same one when nothing changed).
 */
internal fun WebExtensionController.ensureAllowedInPrivateBrowsing(
    ext: WebExtension,
    wanted: Boolean,
): GeckoResult<WebExtension> =
    if (privateBrowsingChangeNeeded(ext.metaData.allowedInPrivateBrowsing, wanted)) {
        setAllowedInPrivateBrowsing(ext, wanted).map { it ?: ext }
    } else {
        GeckoResult.fromValue(ext)
    }
