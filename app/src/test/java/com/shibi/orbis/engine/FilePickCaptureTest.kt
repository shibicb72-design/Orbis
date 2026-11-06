package com.shibi.orbis.engine

import android.net.OrbisTestUri
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FilePickCaptureTest {
    /** A CameraBridge that records every call and lets the test decide when and how the permission is answered. */
    private class FakeCamera : CameraBridge {
        private val waiting = mutableListOf<(Boolean) -> Unit>()
        var permissionAsks = 0
        var outputs = 0
        var hasPictureCalls = 0
        var hasPictureCallsAfterRelease = 0
        var releases = 0
        var pictureWritten = false
        var outputFails = false
        var outputThrows = false
        var releaseThrows = false
        val output: Uri = OrbisTestUri("output")

        override fun ensurePermission(onResult: (Boolean) -> Unit) {
            permissionAsks++
            waiting += onResult
        }

        override fun newOutput(): Uri? {
            outputs++
            if (outputThrows) error("boom")
            return if (outputFails) null else output
        }

        override fun hasPicture(): Boolean {
            hasPictureCalls++
            if (releases > 0) hasPictureCallsAfterRelease++
            return pictureWritten
        }

        override fun release() {
            releases++
            if (releaseThrows) error("boom")
        }

        /** The system answers every permission question asked so far (calling it again answers them again). */
        fun answer(granted: Boolean) {
            waiting.toList().forEach { it(granted) }
        }
    }

    private class Harness(
        route: FilePickRoute,
        multiple: Boolean = false,
        val camera: FakeCamera = FakeCamera(),
        respondThrows: Boolean = false,
    ) {
        val prepared = mutableListOf<List<Uri>>()
        val responses = mutableListOf<List<Uri>?>()
        val prompt =
            SitePrompt.FilePick(
                "h",
                listOf("image/*"),
                multiple,
                prepare = { prepared += it },
                respond = {
                    responses += it
                    if (respondThrows) error("boom")
                },
                route = route,
                camera = camera,
            )
    }

    private fun photoFile() = listOf<Uri>(OrbisTestUri("copy"))

    // ---- plain picker: nothing changes ----

    @Test
    fun defaultFilePickIsAPlainPickerThatNeverUsesACamera() {
        val responses = mutableListOf<List<Uri>?>()
        val prepared = mutableListOf<List<Uri>>()
        val p = SitePrompt.FilePick("h", listOf("*/*"), false, prepare = { prepared += it }, respond = { responses += it })
        assertEquals(FilePickRoute.DOCUMENTS, p.route)
        assertEquals(FilePickStage.PICKING, p.stage.value)
        assertSame(FilePickLaunch.Documents, p.launchRequest.value)
        p.begin()
        p.takePicture()
        p.cameraResult(true)
        p.cameraLaunchFailed()
        assertSame(FilePickLaunch.Documents, p.launchRequest.value)
        assertEquals(FilePickStage.PICKING, p.stage.value)
        assertTrue(p.claimPickerLaunch())

        val picked = photoFile()
        p.submit(picked)
        assertEquals(listOf(picked), prepared)
        val files = photoFile()
        p.filesReady(files)
        assertEquals(listOf<List<Uri>?>(files), responses)
    }

    @Test
    fun documentsRouteWithABridgeNeverTouchesIt() {
        val h = Harness(FilePickRoute.DOCUMENTS)
        h.prompt.begin()
        h.prompt.takePicture()
        h.prompt.chooseDocuments()
        h.prompt.cameraResult(true)
        h.camera.answer(true)
        assertEquals(0, h.camera.permissionAsks)
        assertEquals(0, h.camera.outputs)
        assertSame(FilePickLaunch.Documents, h.prompt.launchRequest.value)
        assertEquals(FilePickStage.PICKING, h.prompt.stage.value)
        assertTrue(h.prepared.isEmpty())
    }

    @Test
    fun cameraRouteWithoutABridgeBehavesAsDocuments() {
        val p = SitePrompt.FilePick("h", emptyList(), false, prepare = {}, respond = {}, route = FilePickRoute.CAMERA)
        assertEquals(FilePickRoute.DOCUMENTS, p.route)
        assertEquals(FilePickStage.PICKING, p.stage.value)
        assertSame(FilePickLaunch.Documents, p.launchRequest.value)
        p.begin()
        assertSame(FilePickLaunch.Documents, p.launchRequest.value)

        val chooser = SitePrompt.FilePick("h", emptyList(), false, prepare = {}, respond = {}, route = FilePickRoute.CHOOSER)
        assertEquals(FilePickRoute.DOCUMENTS, chooser.route)
        assertEquals(FilePickStage.PICKING, chooser.stage.value)
        assertSame(FilePickLaunch.Documents, chooser.launchRequest.value)
    }

    @Test
    fun documentsPickAfterTheCameraFallbackStillUsesTheNormalSubmitPath() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.begin()
        h.camera.answer(true)
        h.prompt.cameraLaunchFailed()
        val picked = photoFile()
        h.prompt.submit(picked)
        assertEquals(listOf(picked), h.prepared)
        assertEquals(FilePickStage.PREPARING, h.prompt.stage.value)
    }

    // ---- camera route ----

    @Test
    fun cameraRouteStartsPickingWithNothingPublished() {
        val h = Harness(FilePickRoute.CAMERA)
        assertEquals(FilePickStage.PICKING, h.prompt.stage.value)
        assertNull(h.prompt.launchRequest.value)
        assertEquals(0, h.camera.permissionAsks)
        assertEquals(0, h.camera.outputs)
    }

    @Test
    fun beginTwiceAsksForThePermissionOnce() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.begin()
        h.prompt.begin()
        h.prompt.takePicture()
        assertEquals(1, h.camera.permissionAsks)
    }

    @Test
    fun noOutputAndNoLaunchExistBeforeThePermissionAnswerArrives() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.begin()
        assertEquals(1, h.camera.permissionAsks)
        assertEquals(0, h.camera.outputs)
        assertNull(h.prompt.launchRequest.value)
        assertFalse(h.prompt.isFinished)
    }

    @Test
    fun grantedPermissionCreatesTheOutputAndPublishesTheCameraLaunch() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.begin()
        h.camera.answer(true)
        assertEquals(1, h.camera.outputs)
        val launch = h.prompt.launchRequest.value as FilePickLaunch.Camera
        assertSame(h.camera.output, launch.output)
        assertEquals(FilePickStage.PICKING, h.prompt.stage.value)
        assertFalse(h.prompt.isFinished)
    }

    @Test
    fun deniedPermissionCreatesNoOutputPublishesNoLaunchAndRespondsNothingChosen() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.begin()
        h.camera.answer(false)
        assertEquals(0, h.camera.outputs)
        assertNull(h.prompt.launchRequest.value)
        assertTrue(h.prompt.isFinished)
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
        assertTrue(h.prepared.isEmpty())
    }

    @Test
    fun permissionAnswerAfterDismissalDoesNothing() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.begin()
        h.prompt.dismiss()
        h.camera.answer(true)
        assertEquals(0, h.camera.outputs)
        assertNull(h.prompt.launchRequest.value)
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
    }

    @Test
    fun deniedAnswerAfterDismissalDoesNotRespondTwice() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.begin()
        h.prompt.dismiss()
        h.camera.answer(false)
        assertEquals(listOf<List<Uri>?>(null), h.responses)
    }

    @Test
    fun beginOnAFinishedPromptDoesNothing() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.dismiss()
        h.prompt.begin()
        assertEquals(0, h.camera.permissionAsks)
    }

    @Test
    fun outputThatCannotBeCreatedFailsThenDismissRespondsNothingChosen() {
        val h = Harness(FilePickRoute.CAMERA)
        h.camera.outputFails = true
        h.prompt.begin()
        h.camera.answer(true)
        assertEquals(FilePickStage.FAILED, h.prompt.stage.value)
        assertFalse(h.prompt.isFinished)
        assertNull(h.prompt.launchRequest.value)
        assertTrue(h.responses.isEmpty())
        h.prompt.dismiss()
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
    }

    @Test
    fun outputThatThrowsFailsThenDismissRespondsNothingChosen() {
        val h = Harness(FilePickRoute.CAMERA)
        h.camera.outputThrows = true
        h.prompt.begin()
        h.camera.answer(true)
        assertEquals(FilePickStage.FAILED, h.prompt.stage.value)
        assertNull(h.prompt.launchRequest.value)
        h.prompt.dismiss()
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
    }

    @Test
    fun permissionAnsweredTwiceCreatesOneOutput() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.begin()
        h.camera.answer(true)
        h.camera.answer(true)
        assertEquals(1, h.camera.outputs)
    }

    // ---- camera result ----

    private fun launched(): Harness =
        Harness(FilePickRoute.CAMERA).also {
            it.prompt.begin()
            it.camera.answer(true)
        }

    @Test
    fun cancelledCameraRespondsNothingChosenAndReleases() {
        val h = launched()
        h.prompt.cameraResult(false)
        assertTrue(h.prompt.isFinished)
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
        assertTrue(h.prepared.isEmpty())
        assertEquals("a cancelled camera never looks for a picture", 0, h.camera.hasPictureCalls)
    }

    @Test
    fun aTakenPictureIsSubmittedOnceAndReleasedOnlyWhenTheCopyIsReady() {
        val h = launched()
        h.camera.pictureWritten = true
        h.prompt.cameraResult(true)
        assertEquals(FilePickStage.PREPARING, h.prompt.stage.value)
        assertEquals(1, h.prepared.size)
        assertEquals(1, h.prepared[0].size)
        assertSame(h.camera.output, h.prepared[0][0])
        assertEquals("the copy still reads the temp", 0, h.camera.releases)
        assertFalse(h.prompt.isFinished)

        val copy = photoFile()
        h.prompt.filesReady(copy)
        assertEquals(listOf<List<Uri>?>(copy), h.responses)
        assertEquals(1, h.camera.releases)
        assertEquals(0, h.camera.hasPictureCallsAfterRelease)
    }

    @Test
    fun aSecondCameraResultIsIgnored() {
        val h = launched()
        h.camera.pictureWritten = true
        h.prompt.cameraResult(true)
        h.prompt.cameraResult(true)
        h.prompt.cameraResult(false)
        assertEquals(1, h.prepared.size)
        assertFalse(h.prompt.isFinished)
        assertEquals(FilePickStage.PREPARING, h.prompt.stage.value)
    }

    @Test
    fun okWithoutAPictureFailsAndReleasesOnDismiss() {
        val h = launched()
        h.camera.pictureWritten = false
        h.prompt.cameraResult(true)
        assertEquals(FilePickStage.FAILED, h.prompt.stage.value)
        assertTrue(h.prepared.isEmpty())
        assertFalse(h.prompt.isFinished)
        assertEquals(0, h.camera.releases)
        h.prompt.dismiss()
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
        assertEquals(0, h.camera.hasPictureCallsAfterRelease)
    }

    @Test
    fun aStrayCameraResultWithNoLaunchOutstandingIsIgnored() {
        val h = Harness(FilePickRoute.CAMERA)
        h.camera.pictureWritten = true
        h.prompt.cameraResult(true)
        h.prompt.cameraResult(false)
        h.prompt.begin()
        h.prompt.cameraResult(true)
        h.prompt.cameraResult(false)
        assertFalse("still waiting for the permission", h.prompt.isFinished)
        assertTrue(h.prepared.isEmpty())
        assertEquals(0, h.camera.hasPictureCalls)
        assertEquals(FilePickStage.PICKING, h.prompt.stage.value)
    }

    @Test
    fun aCameraResultOnAnotherPromptDoesNotTouchThisOne() {
        val mine = launched()
        val other = Harness(FilePickRoute.CAMERA)
        other.prompt.begin()
        other.camera.pictureWritten = true
        other.prompt.cameraResult(true)
        assertFalse(mine.prompt.isFinished)
        assertTrue(mine.prepared.isEmpty())
        assertEquals(0, mine.camera.hasPictureCalls)
    }

    @Test
    fun aCameraResultAfterDismissalIsIgnored() {
        val h = launched()
        h.camera.pictureWritten = true
        h.prompt.dismiss()
        h.prompt.cameraResult(true)
        assertTrue(h.prepared.isEmpty())
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(0, h.camera.hasPictureCalls)
    }

    @Test
    fun multipleKeepsTheArrayPathWithTheSinglePhoto() {
        val h = Harness(FilePickRoute.CAMERA, multiple = true)
        assertTrue(h.prompt.multiple)
        h.prompt.begin()
        h.camera.answer(true)
        h.camera.pictureWritten = true
        h.prompt.cameraResult(true)
        assertEquals(1, h.prepared.size)
        assertSame(h.camera.output, h.prepared[0].single())
        val copy = photoFile()
        h.prompt.filesReady(copy)
        assertEquals(listOf<List<Uri>?>(copy), h.responses)
    }

    // ---- launch failure fallback ----

    @Test
    fun launchFailureFallsBackToTheDocumentPickerOnce() {
        val h = launched()
        assertTrue(h.prompt.claimPickerLaunch())
        assertFalse(h.prompt.claimPickerLaunch())

        h.prompt.cameraLaunchFailed()
        assertSame(FilePickLaunch.Documents, h.prompt.launchRequest.value)
        assertEquals(1, h.camera.releases)
        assertTrue("the picker may be launched now", h.prompt.claimPickerLaunch())
        assertFalse(h.prompt.isFinished)
        assertEquals(FilePickStage.PICKING, h.prompt.stage.value)

        // Once only: a second report changes nothing.
        h.prompt.cameraLaunchFailed()
        assertEquals(1, h.camera.releases)
        assertFalse("claim not handed out again", h.prompt.claimPickerLaunch())
    }

    @Test
    fun launchFailureWithNoLaunchOutstandingIsIgnored() {
        val h = Harness(FilePickRoute.CAMERA)
        h.prompt.cameraLaunchFailed()
        h.prompt.begin()
        h.prompt.cameraLaunchFailed()
        assertNull(h.prompt.launchRequest.value)
        assertEquals(0, h.camera.releases)
        h.camera.answer(true)
        assertTrue(h.prompt.launchRequest.value is FilePickLaunch.Camera)
    }

    @Test
    fun cameraResultAfterTheLaunchFailedIsIgnored() {
        val h = launched()
        h.camera.pictureWritten = true
        h.prompt.cameraLaunchFailed()
        h.prompt.cameraResult(true)
        assertTrue(h.prepared.isEmpty())
        assertEquals(0, h.camera.hasPictureCalls)
        assertFalse(h.prompt.isFinished)
    }

    @Test
    fun launchFailureAfterDismissalIsIgnored() {
        val h = launched()
        h.prompt.dismiss()
        val releases = h.camera.releases
        h.prompt.cameraLaunchFailed()
        assertEquals(releases, h.camera.releases)
        assertSame(h.camera.output, (h.prompt.launchRequest.value as FilePickLaunch.Camera).output)
    }

    // ---- chooser ----

    @Test
    fun chooserStartsChoosingAndPublishesNothing() {
        val h = Harness(FilePickRoute.CHOOSER)
        assertEquals(FilePickStage.CHOOSING, h.prompt.stage.value)
        assertNull(h.prompt.launchRequest.value)
        h.prompt.begin()
        assertEquals(FilePickStage.CHOOSING, h.prompt.stage.value)
        assertNull(h.prompt.launchRequest.value)
        assertEquals(0, h.camera.permissionAsks)
        assertEquals(0, h.camera.outputs)
    }

    @Test
    fun chooserTakePictureAsksThenPublishesTheCamera() {
        val h = Harness(FilePickRoute.CHOOSER)
        h.prompt.takePicture()
        assertEquals(FilePickStage.PICKING, h.prompt.stage.value)
        assertEquals(1, h.camera.permissionAsks)
        assertEquals(0, h.camera.outputs)
        assertNull(h.prompt.launchRequest.value)

        h.camera.answer(true)
        assertSame(h.camera.output, (h.prompt.launchRequest.value as FilePickLaunch.Camera).output)
    }

    @Test
    fun chooserTakePictureDeniedRespondsNothingChosen() {
        val h = Harness(FilePickRoute.CHOOSER)
        h.prompt.takePicture()
        h.camera.answer(false)
        assertEquals(0, h.camera.outputs)
        assertNull(h.prompt.launchRequest.value)
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
    }

    @Test
    fun chooserTakePictureTwiceAsksOnce() {
        val h = Harness(FilePickRoute.CHOOSER)
        h.prompt.takePicture()
        h.prompt.takePicture()
        assertEquals(1, h.camera.permissionAsks)
    }

    @Test
    fun chooserChooseDocumentsPublishesTheDocumentPicker() {
        val h = Harness(FilePickRoute.CHOOSER)
        h.prompt.chooseDocuments()
        assertEquals(FilePickStage.PICKING, h.prompt.stage.value)
        assertSame(FilePickLaunch.Documents, h.prompt.launchRequest.value)
        assertEquals(0, h.camera.permissionAsks)
        assertEquals(0, h.camera.outputs)
    }

    @Test
    fun chooserChoiceCannotBeChangedOnceMade() {
        val documents = Harness(FilePickRoute.CHOOSER)
        documents.prompt.chooseDocuments()
        documents.prompt.takePicture()
        assertEquals(0, documents.camera.permissionAsks)
        assertSame(FilePickLaunch.Documents, documents.prompt.launchRequest.value)

        val camera = Harness(FilePickRoute.CHOOSER)
        camera.prompt.takePicture()
        camera.camera.answer(true)
        camera.prompt.chooseDocuments()
        assertTrue(camera.prompt.launchRequest.value is FilePickLaunch.Camera)
    }

    @Test
    fun chooserCancelRespondsNothingChosenAndReleases() {
        val h = Harness(FilePickRoute.CHOOSER)
        h.prompt.dismiss()
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
        assertEquals(0, h.camera.outputs)
        h.prompt.takePicture()
        h.prompt.chooseDocuments()
        assertEquals(0, h.camera.permissionAsks)
        assertNull(h.prompt.launchRequest.value)
    }

    @Test
    fun chooserDocumentsPickSubmitsOnTheNormalPath() {
        val h = Harness(FilePickRoute.CHOOSER)
        h.prompt.chooseDocuments()
        val picked = photoFile()
        h.prompt.submit(picked)
        assertEquals(listOf(picked), h.prepared)
        val copy = photoFile()
        h.prompt.filesReady(copy)
        assertEquals(listOf<List<Uri>?>(copy), h.responses)
    }

    @Test
    fun submitWhileStillChoosingIsIgnored() {
        val h = Harness(FilePickRoute.CHOOSER)
        h.prompt.submit(photoFile())
        assertTrue(h.prepared.isEmpty())
        assertEquals(FilePickStage.CHOOSING, h.prompt.stage.value)
    }

    // ---- release ----

    @Test
    fun dismissDuringPreparingReleasesAndALateFilesReadyIsIgnored() {
        val h = launched()
        h.camera.pictureWritten = true
        h.prompt.cameraResult(true)
        assertEquals(0, h.camera.releases)
        h.prompt.dismiss()
        assertEquals(1, h.camera.releases)
        h.prompt.filesReady(photoFile())
        h.prompt.prepareFailed()
        h.prompt.dismiss()
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
        assertEquals(0, h.camera.hasPictureCallsAfterRelease)
    }

    @Test
    fun emptyFilesReadyDismissesAndReleases() {
        val h = launched()
        h.camera.pictureWritten = true
        h.prompt.cameraResult(true)
        h.prompt.filesReady(emptyList())
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertEquals(1, h.camera.releases)
    }

    @Test
    fun aReleaseThatThrowsDoesNotBreakCompletion() {
        val h = launched()
        h.camera.releaseThrows = true
        h.camera.pictureWritten = true
        h.prompt.cameraResult(true)
        val copy = photoFile()
        h.prompt.filesReady(copy)
        assertTrue(h.prompt.isFinished)
        assertEquals(listOf<List<Uri>?>(copy), h.responses)
        assertEquals(1, h.camera.releases)
    }

    @Test
    fun aReleaseThatThrowsDoesNotBreakDismissal() {
        val h = launched()
        h.camera.releaseThrows = true
        h.prompt.dismiss()
        assertTrue(h.prompt.isFinished)
        assertEquals(listOf<List<Uri>?>(null), h.responses)
    }

    @Test
    fun aRespondThatThrowsStillReleases() {
        val h = Harness(FilePickRoute.CAMERA, respondThrows = true)
        h.prompt.begin()
        h.camera.answer(true)
        try {
            h.prompt.dismiss()
            fail("respond's exception should reach the caller")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(1, h.camera.releases)
        assertTrue(h.prompt.isFinished)

        val ready = Harness(FilePickRoute.CAMERA, respondThrows = true)
        ready.prompt.begin()
        ready.camera.answer(true)
        ready.camera.pictureWritten = true
        ready.prompt.cameraResult(true)
        try {
            ready.prompt.filesReady(photoFile())
            fail("respond's exception should reach the caller")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(1, ready.camera.releases)
    }

    @Test
    fun everyEndOfACameraPromptReleasesExactlyOnce() {
        val denied = Harness(FilePickRoute.CAMERA).also { it.prompt.begin(); it.camera.answer(false) }
        val cancelled = launched().also { it.prompt.cameraResult(false) }
        val dismissed = launched().also { it.prompt.dismiss() }
        val delivered =
            launched().also {
                it.camera.pictureWritten = true
                it.prompt.cameraResult(true)
                it.prompt.filesReady(photoFile())
            }
        val all = mapOf("denied" to denied, "cancelled" to cancelled, "dismissed" to dismissed, "delivered" to delivered)
        for ((name, h) in all) {
            assertTrue(name, h.prompt.isFinished)
            assertEquals(name, 1, h.camera.releases)
        }
    }
}
