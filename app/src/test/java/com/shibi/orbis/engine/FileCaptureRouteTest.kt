package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FileCaptureRouteTest {
    private val table = mapOf("jpg" to "image/jpeg", "png" to "image/png", "pdf" to "application/pdf")
    private val mapped: (String) -> String? = { ext -> table[ext] }

    private fun route(
        capture: Boolean,
        vararg types: String,
        camera: Boolean = true,
    ) = filePickRoute(capture, types.toList(), camera, mapped)

    // ---- no capture: always the plain picker ----

    @Test
    fun noCaptureWithImageAcceptIsStillThePlainPicker() {
        assertEquals(FilePickRoute.DOCUMENTS, route(false, "image/*"))
    }

    @Test
    fun noCaptureWithEmptyAcceptIsThePlainPicker() {
        assertEquals(FilePickRoute.DOCUMENTS, route(false))
    }

    @Test
    fun noCaptureNeverLeavesThePlainPickerWhateverTheAccept() {
        assertEquals(FilePickRoute.DOCUMENTS, route(false, "image/jpeg", "image/png"))
        assertEquals(FilePickRoute.DOCUMENTS, route(false, "*/*"))
        assertEquals(FilePickRoute.DOCUMENTS, route(false, "video/*"))
    }

    // ---- capture with image tokens only: camera ----

    @Test
    fun captureWithImageWildcardGoesToTheCamera() {
        assertEquals(FilePickRoute.CAMERA, route(true, "image/*"))
    }

    @Test
    fun captureWithImageJpegGoesToTheCamera() {
        assertEquals(FilePickRoute.CAMERA, route(true, "image/jpeg"))
    }

    @Test
    fun tokensAreLowercased() {
        assertEquals(FilePickRoute.CAMERA, route(true, "IMAGE/*"))
    }

    @Test
    fun tokensAreTrimmed() {
        assertEquals(FilePickRoute.CAMERA, route(true, " image/* "))
    }

    @Test
    fun mimeParametersAreDropped() {
        assertEquals(FilePickRoute.CAMERA, route(true, "image/*;capture=camera"))
    }

    @Test
    fun aMappedImageExtensionCountsAsAnImage() {
        assertEquals(FilePickRoute.CAMERA, route(true, ".jpg"))
        assertEquals(FilePickRoute.CAMERA, route(true, ".JPG"))
    }

    @Test
    fun severalImageTypesGoToTheCamera() {
        assertEquals(FilePickRoute.CAMERA, route(true, "image/jpeg", "image/png"))
    }

    @Test
    fun blankTokensAreIgnored() {
        assertEquals(FilePickRoute.CAMERA, route(true, "", "  ", "image/*"))
    }

    // ---- capture with unclear or mixed accept: ask the user ----

    @Test
    fun captureWithEmptyAcceptAsksTheUser() {
        assertEquals(FilePickRoute.CHOOSER, route(true))
    }

    @Test
    fun captureWithOnlyBlankTokensAsksTheUser() {
        assertEquals(FilePickRoute.CHOOSER, route(true, "", "   "))
    }

    @Test
    fun captureWithAnyTypeAsksTheUser() {
        assertEquals(FilePickRoute.CHOOSER, route(true, "*/*"))
    }

    @Test
    fun captureWithImageAndVideoAsksTheUser() {
        assertEquals(FilePickRoute.CHOOSER, route(true, "image/*", "video/*"))
    }

    @Test
    fun captureWithImageAndPdfExtensionAsksTheUser() {
        assertEquals(FilePickRoute.CHOOSER, route(true, "image/*", ".pdf"))
    }

    @Test
    fun captureWithImageAndAnUnmappedExtensionAsksTheUser() {
        assertEquals(FilePickRoute.CHOOSER, route(true, "image/*", ".xyz"))
    }

    // ---- capture without an image token: only photos are captured, so a plain pick ----

    @Test
    fun captureWithVideoIsThePlainPicker() {
        assertEquals(FilePickRoute.DOCUMENTS, route(true, "video/*"))
    }

    @Test
    fun captureWithAudioIsThePlainPicker() {
        assertEquals(FilePickRoute.DOCUMENTS, route(true, "audio/*"))
    }

    @Test
    fun captureWithPdfExtensionIsThePlainPicker() {
        assertEquals(FilePickRoute.DOCUMENTS, route(true, ".pdf"))
    }

    @Test
    fun captureWithAnUnmappedExtensionIsThePlainPicker() {
        assertEquals(FilePickRoute.DOCUMENTS, route(true, ".xyz"))
    }

    @Test
    fun anExtensionWithNothingAfterTheDotIsAnotherKind() {
        assertEquals(FilePickRoute.DOCUMENTS, route(true, "."))
    }

    // ---- no camera hardware ----

    @Test
    fun captureWithoutCameraHardwareIsThePlainPicker() {
        assertEquals(FilePickRoute.DOCUMENTS, route(true, "image/*", camera = false))
        assertEquals(FilePickRoute.DOCUMENTS, route(true, camera = false))
    }
}
