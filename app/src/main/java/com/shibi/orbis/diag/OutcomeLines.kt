package com.shibi.orbis.diag

/** How a page load ended (see [OutcomeLines.load]). An engine error is [OutcomeLines.loadError]. */
enum class LoadKind(val text: String) {
    COMMITTED("committed"),
    REDIRECTED("redirected"),
    DOWNLOAD("download"),
    APP("app"),
    REFUSED("refused"),
    STOPPED("stopped"),
    UNFINISHED("unfinished"),
}

/** The engine's error category, by name only (never the error code, the address or any message). */
enum class ErrorCategory(val text: String) {
    UNKNOWN("unknown"),
    SECURITY("security"),
    NETWORK("network"),
    CONTENT("content"),
    URI("uri"),
    PROXY("proxy"),
    SAFEBROWSING("safebrowsing"),
}

enum class LinkVia(val text: String) {
    VIEW("view"),
    SEND("send"),
    CUSTOM_TAB("custom-tab"),
    SHORTCUT("shortcut"),
    OTHER("other"),
}

enum class PopupResult(val text: String) {
    OPENED("opened"),
    CLOSED_AT_TAB_LIMIT("closed-at-tab-limit"),
    BLOCKED("blocked"),
}

enum class AppLinkKind(val text: String) {
    INTENT("intent"),
    MARKET("market"),
    TEL("tel"),
    MAILTO("mailto"),
    SMS("sms"),
    OTHER("other"),
}

enum class AppLinkDecision(val text: String) {
    OPENED("opened"),
    ASKED("asked"),
    REFUSED("refused"),
    NO_APP("no-app"),
    WEB_FALLBACK_FOLLOWED("web-fallback-followed"),
    BLOCKED_IMPORT("blocked-import"),
}

enum class DesktopWhy(val text: String) {
    USER("user"),
    SITE_SETTING("site-setting"),
    AUTO_BLOCKED_PAGE("auto-blocked-page"),
    AUTO_EMPTY_PAGE("auto-empty-page"),
    AUTO_DROPPED("auto-dropped"),
    RESTORED("restored"),
}

/**
 * The one place the Error log's outcome lines are built. THE PRIVACY RULE IS THE TYPES: no function here takes a
 * String, CharSequence or Uri; only enums, Int, Long and Boolean. A caller passes facts, never text, so an address,
 * host, title, scheme, package name or error message cannot reach a line. A private tab's number is never written:
 * every builder that names a tab takes [isPrivate] and writes 0 for it.
 */
object OutcomeLines {
    /** The number a line shows for a tab: its own id, or 0 for a private tab. */
    fun tabNumber(isPrivate: Boolean, tab: Long): Long = if (isPrivate) 0L else tab

    /**
     * `load: <kind> tab=<n> ms=<t>`. [empty] adds ` empty=yes`, and ` blocked=<count>` when [blocked] is not negative
     * (a negative count means the engine gave none).
     */
    fun load(kind: LoadKind, isPrivate: Boolean, tab: Long, ms: Long, empty: Boolean = false, blocked: Int = -1): String =
        "load: ${kind.text} tab=${tabNumber(isPrivate, tab)} ms=$ms" + emptySuffix(empty, blocked)

    /** `load: error-<category> tab=<n> ms=<t>`. */
    fun loadError(category: ErrorCategory, isPrivate: Boolean, tab: Long, ms: Long): String =
        "load: error-${category.text} tab=${tabNumber(isPrivate, tab)} ms=$ms"

    private fun emptySuffix(empty: Boolean, blocked: Int): String =
        if (!empty) "" else " empty=yes" + if (blocked >= 0) " blocked=$blocked" else ""

    /** `link in: via=<..> tab=<n> new-tab=<yes|no>`. */
    fun linkIn(via: LinkVia, isPrivate: Boolean, tab: Long, newTab: Boolean): String =
        "link in: via=${via.text} tab=${tabNumber(isPrivate, tab)} new-tab=${yesNo(newTab)}"

    /** The result of a pop-up the host was handed: a child with no tab number was closed because the tab list was full. */
    fun popupResultOf(childTab: Long): PopupResult = if (childTab > 0L) PopupResult.OPENED else PopupResult.CLOSED_AT_TAB_LIMIT

    /** `popup: opener=<n> result=<..> child=<n or 0> count=<k>`. */
    fun popup(result: PopupResult, isPrivate: Boolean, opener: Long, child: Long, count: Int): String =
        "popup: opener=${tabNumber(isPrivate, opener)} result=${result.text} child=${tabNumber(isPrivate, child)} count=$count"

    /** `app link: kind=<..> decision=<..> asked=<yes|no> tab=<n>`. */
    fun appLink(kind: AppLinkKind, decision: AppLinkDecision, asked: Boolean, isPrivate: Boolean, tab: Long): String =
        "app link: kind=${kind.text} decision=${decision.text} asked=${yesNo(asked)} tab=${tabNumber(isPrivate, tab)}"

    /**
     * `page process killed: tabs=<open> awake=<with a session> selected=<yes|no>`. For a private tab the counts and the
     * selection could single the tab out, so they are written as 0, 0 and no.
     */
    fun processKilled(isPrivate: Boolean, tabs: Int, awake: Int, selected: Boolean): String =
        if (isPrivate) {
            "page process killed: tabs=0 awake=0 selected=no"
        } else {
            "page process killed: tabs=$tabs awake=$awake selected=${yesNo(selected)}"
        }

    /** `desktop mode: <on|off> tab=<n> why=<..>`. */
    fun desktopMode(on: Boolean, why: DesktopWhy, isPrivate: Boolean, tab: Long): String =
        "desktop mode: ${if (on) "on" else "off"} tab=${tabNumber(isPrivate, tab)} why=${why.text}"

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
