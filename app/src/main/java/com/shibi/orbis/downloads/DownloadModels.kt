package com.shibi.orbis.downloads

enum class DownloadStatus { IN_PROGRESS, COMPLETED, FAILED, CANCELLED }

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
)
