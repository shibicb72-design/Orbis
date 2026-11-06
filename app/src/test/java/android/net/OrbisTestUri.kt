package android.net

import android.os.Parcel

/**
 * A Uri for JVM tests. The unit-test android.jar cannot build one (Uri.parse and friends throw), and Uri's constructor
 * is package private, hence this class lives in android.net. Equality is identity, so tests can compare Uris without
 * calling into the stubbed Uri methods.
 */
class OrbisTestUri(private val label: String = "test-uri") : Uri() {
    override fun buildUpon(): Builder = throw UnsupportedOperationException()

    override fun getAuthority(): String? = null

    override fun getEncodedAuthority(): String? = null

    override fun getEncodedFragment(): String? = null

    override fun getEncodedPath(): String? = null

    override fun getEncodedQuery(): String? = null

    override fun getEncodedSchemeSpecificPart(): String? = null

    override fun getEncodedUserInfo(): String? = null

    override fun getFragment(): String? = null

    override fun getHost(): String? = null

    override fun getLastPathSegment(): String? = null

    override fun getPath(): String? = null

    override fun getPathSegments(): MutableList<String> = mutableListOf()

    override fun getPort(): Int = -1

    override fun getQuery(): String? = null

    override fun getScheme(): String? = null

    override fun getSchemeSpecificPart(): String? = null

    override fun getUserInfo(): String? = null

    override fun isHierarchical(): Boolean = false

    override fun isRelative(): Boolean = false

    override fun toString(): String = label

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = Unit

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
