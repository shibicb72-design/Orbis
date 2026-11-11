package com.shibi.orbis.engine

/** What was long-pressed. Image is the only media type the menu saves; other elements are links only. */
internal enum class PageContextType { IMAGE, OTHER }

/** Fields copied off GeckoView's context element so the menu can be chosen without an engine type. */
internal data class ContextElementInfo(
    val type: PageContextType,
    val linkUri: String?,
    val srcUri: String?,
    val title: String?,
    val altText: String?,
    val linkText: String?,
)

enum class ContextMenuItem {
    OPEN_NEW_TAB,
    OPEN_NEW_PRIVATE_TAB,
    COPY_LINK_ADDRESS,
    COPY_LINK_TEXT,
    SHARE_LINK,
    DOWNLOAD_LINK,

    /** magnet: only; shown while Settings > Magnet links is "Download with Orbis" (the UI hides it otherwise). */
    DOWNLOAD_TORRENT,
    OPEN_EXTERNAL,
    OPEN_IMAGE_NEW_TAB,
    SAVE_IMAGE,
    COPY_IMAGE_ADDRESS,
    SHARE_IMAGE,
}

internal val ContextMenuItem.isImageAction: Boolean
    get() =
        this == ContextMenuItem.OPEN_IMAGE_NEW_TAB ||
            this == ContextMenuItem.SAVE_IMAGE ||
            this == ContextMenuItem.COPY_IMAGE_ADDRESS ||
            this == ContextMenuItem.SHARE_IMAGE

/** The long-press menu. [header] is truncated for display; [linkUrl] and [imageUrl] stay complete for copy and open. */
data class PageContextMenu(
    val header: String,
    val linkUrl: String?,
    val linkText: String?,
    val imageUrl: String?,
    val items: List<ContextMenuItem>,
)

internal const val CONTEXT_MENU_HEADER_MAX = 72

internal fun contextMenuHeader(url: String): String =
    if (url.length <= CONTEXT_MENU_HEADER_MAX) url else url.take(CONTEXT_MENU_HEADER_MAX - 1) + "…"

/**
 * Menu for a long-press. Http(s) links can open in a tab or download. Other schemes (magnet:) can be copied, shared,
 * or handed to an app, and are never loaded as a page. An image that is also a link gets both groups. Null when there
 * is nothing to offer.
 */
internal fun pageContextMenu(info: ContextElementInfo): PageContextMenu? {
    val link = info.linkUri?.trim()?.takeIf { it.isNotEmpty() && schemeOf(it) != null }
    val image = info.srcUri?.trim()?.takeIf { info.type == PageContextType.IMAGE && isWebUrl(it) }
    if (link == null && image == null) return null
    val text = if (link != null) chosenLinkText(info, link) else null
    val items = buildList {
        if (link != null) addAll(linkItems(link, text))
        if (image != null) addAll(imageItems())
    }
    if (items.isEmpty()) return null
    return PageContextMenu(
        header = contextMenuHeader(link ?: image!!),
        linkUrl = link,
        linkText = text,
        imageUrl = image,
        items = items,
    )
}

private fun chosenLinkText(info: ContextElementInfo, linkUrl: String): String? {
    val raw =
        sequenceOf(info.linkText, info.title, info.altText)
            .mapNotNull { it?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .firstOrNull() ?: return null
    if (raw == linkUrl) return null
    return raw
}

private fun linkItems(linkUrl: String, linkText: String?): List<ContextMenuItem> {
    val web = isWebUrl(linkUrl)
    val external = isExternalScheme(linkUrl)
    return buildList {
        if (web) {
            add(ContextMenuItem.OPEN_NEW_TAB)
            add(ContextMenuItem.OPEN_NEW_PRIVATE_TAB)
        }
        add(ContextMenuItem.COPY_LINK_ADDRESS)
        if (linkText != null) add(ContextMenuItem.COPY_LINK_TEXT)
        add(ContextMenuItem.SHARE_LINK)
        if (web) add(ContextMenuItem.DOWNLOAD_LINK)
        if (schemeOf(linkUrl) == "magnet") add(ContextMenuItem.DOWNLOAD_TORRENT)
        if (external) add(ContextMenuItem.OPEN_EXTERNAL)
    }
}

private fun imageItems(): List<ContextMenuItem> =
    listOf(
        ContextMenuItem.OPEN_IMAGE_NEW_TAB,
        ContextMenuItem.SAVE_IMAGE,
        ContextMenuItem.COPY_IMAGE_ADDRESS,
        ContextMenuItem.SHARE_IMAGE,
    )
