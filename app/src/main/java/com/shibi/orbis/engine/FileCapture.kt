package com.shibi.orbis.engine

import java.io.File
import java.util.Locale
import java.util.UUID

/** How a file prompt starts: the system document picker, straight to the camera, or a Take picture / Choose file question. */
enum class FilePickRoute { DOCUMENTS, CAMERA, CHOOSER }

private enum class AcceptKind { IMAGE, ANY, OTHER }

/**
 * Picks the route for `<input type=file>`. Gecko gives only a yes/no for the capture attribute and the raw `accept`
 * tokens, so the decision is made here.
 *
 * Without capture the answer is always [FilePickRoute.DOCUMENTS], even when the page accepts only images: a plain picker
 * is what the page asked for. Capture without camera hardware is also a plain pick. Otherwise: only image tokens go to the
 * camera, no tokens at all or a mix with other kinds (including a star-slash-star token) ask the user, and everything
 * else (video, audio, a document type) stays a plain pick because only photos are captured. Tokens are trimmed and
 * lowercased, a mime token's `;params` are dropped, and a `.ext` token maps through [extensionToMime] (lowercased, no
 * dot); one that does not map counts as another kind.
 */
fun filePickRoute(
    captureRequested: Boolean,
    mimeTypes: List<String>,
    cameraAvailable: Boolean,
    extensionToMime: (String) -> String? = { null },
): FilePickRoute {
    if (!captureRequested || !cameraAvailable) return FilePickRoute.DOCUMENTS
    var images = 0
    var any = 0
    var other = 0
    for (raw in mimeTypes) {
        val token = raw.trim().lowercase(Locale.ROOT)
        if (token.isEmpty()) continue
        val mime =
            if (token.startsWith('.')) {
                token.substring(1).takeIf { it.isNotEmpty() }?.let(extensionToMime)?.trim()?.lowercase(Locale.ROOT)
            } else {
                token
            }
        when (mime?.let(::acceptKind) ?: AcceptKind.OTHER) {
            AcceptKind.IMAGE -> images++
            AcceptKind.ANY -> any++
            AcceptKind.OTHER -> other++
        }
    }
    return when {
        images + any + other == 0 -> FilePickRoute.CHOOSER
        any == 0 && other == 0 -> FilePickRoute.CAMERA
        images > 0 || any > 0 -> FilePickRoute.CHOOSER
        else -> FilePickRoute.DOCUMENTS
    }
}

private fun acceptKind(lowercaseToken: String): AcceptKind {
    val mime = lowercaseToken.substringBefore(';').trim()
    return when {
        mime == "*/*" -> AcceptKind.ANY
        mime.startsWith("image/") && mime.length > "image/".length -> AcceptKind.IMAGE
        else -> AcceptKind.OTHER
    }
}

/**
 * Temp files the camera app writes a photo into: `<cacheDir>/camera_capture/photo-<random>.jpg`. The name is random so it
 * carries no time and no page text. This directory is shared through the FileProvider so the camera can write there;
 * it must never be inside the upload cache, which is promised to stay out of every FileProvider path. A photo is copied
 * to the upload cache like any other pick and the temp is deleted when the prompt ends; [clearAll] sweeps leftovers.
 */
class CameraCaptureFiles(cacheDir: File) {
    val root: File = File(cacheDir, "camera_capture")

    /** A new, not yet created path for the camera to write to (the camera creates the file); null if the directory cannot be made. */
    fun newFile(): File? {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) return null
        return File(root, "photo-${UUID.randomUUID().toString().replace("-", "")}.jpg")
    }

    /** True once the camera wrote something: a missing or empty file means no photo. */
    fun hasPicture(file: File): Boolean = file.isFile && file.length() > 0

    /** Deletes a file [newFile] made. Only ever a direct child of the root: never a path the caller made up. */
    fun discard(file: File) {
        if (file.parentFile == root) file.delete()
    }

    /** Deletes the whole directory. Engine start only, on a background thread. */
    fun clearAll() {
        root.deleteRecursively()
    }
}
