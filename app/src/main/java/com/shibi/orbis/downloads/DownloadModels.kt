package com.shibi.orbis.downloads

/** PAUSED keeps the part file and continues from it on resume (this run only: the address is never stored). */
enum class DownloadStatus { IN_PROGRESS, COMPLETED, FAILED, CANCELLED, PAUSED }

/**
 * One row of the downloads list. Deliberately holds no URL: only the host the file came from, so the persisted
 * index cannot leak browsing history beyond a host name.
 */
data class DownloadEntry(
    val id: Long,
    val fileName: String,
    val mimeType: String,
    val sourceHost: String?,
    /** -1 when the server did not say. */
    val totalBytes: Long,
    val receivedBytes: Long,
    val status: DownloadStatus,
    /** Absolute path of the final file (reserved while running). Null once failed/cancelled or before reservation. */
    val filePath: String?,
    val startedAt: Long,
    /**
     * Started from a private tab: never written to the index file, has no source host, and leaves the list (the file
     * stays) when private browsing ends.
     */
    val isPrivate: Boolean = false,
)
