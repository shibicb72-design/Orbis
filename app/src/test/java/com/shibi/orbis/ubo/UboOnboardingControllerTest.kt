package com.shibi.orbis.ubo

import com.shibi.orbis.engine.ExtensionHost
import com.shibi.orbis.engine.ExtensionInstallError
import com.shibi.orbis.engine.ExtensionInstallException
import com.shibi.orbis.engine.InstalledExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOUR = 60L * 60 * 1000

private fun ext(version: String = "1.0", enabled: Boolean = true, byUser: Boolean = false) =
    InstalledExtension(UBO_ID, version, enabled, byUser)

private class FakeHost : ExtensionHost {
    var installed: InstalledExtension? = null
    val installCalls = mutableListOf<Pair<String, String>>()
    var installDelayMillis = 0L
    var nextInstallError: ExtensionInstallError? = null
    var installResult = ext()
    var updateResult: InstalledExtension? = null
    var updateThrows = false
    var updateCalls = 0
    var uninstallCalls = 0

    override suspend fun find(id: String): InstalledExtension? = installed

    override suspend fun install(xpiUrl: String, expectedId: String): InstalledExtension {
        installCalls += xpiUrl to expectedId
        if (installDelayMillis > 0) delay(installDelayMillis)
        nextInstallError?.let { nextInstallError = null; throw ExtensionInstallException(it) }
        installed = installResult
        return installResult
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): InstalledExtension {
        val updated = installed!!.copy(enabled = enabled, disabledByUser = !enabled)
        installed = updated
        return updated
    }

    override suspend fun uninstall(id: String) {
        uninstallCalls++
        installed = null
    }

    override suspend fun checkForUpdate(id: String): InstalledExtension? {
        updateCalls++
        if (updateThrows) throw ExtensionInstallException(ExtensionInstallError.NETWORK)
        return updateResult?.also { installed = it }
    }
}

private class MemoryPrefs(
    override var decision: UboDecision = UboDecision.UNDECIDED,
    override var lastUpdateCheckMillis: Long = 0L,
) : UboPrefs

class UboOnboardingControllerTest {
    private val now = 100 * 24 * HOUR
    private val logs = mutableListOf<String>()

    private fun controller(
        scope: CoroutineScope,
        host: FakeHost,
        prefs: UboPrefs,
        timeoutMillis: Long = 180_000,
    ) = UboOnboardingController(host, prefs, scope, { logs += it }, { now }, timeoutMillis)

    private val scopeJobs = mutableListOf<Job>()

    // A real scope on the test scheduler: advanceUntilIdle() does not run backgroundScope work.
    private fun TestScope.controllerScope(): CoroutineScope {
        val job = SupervisorJob()
        scopeJobs += job
        return CoroutineScope(StandardTestDispatcher(testScheduler) + job)
    }

    @After
    fun cancelScopes() {
        scopeJobs.forEach { it.cancel() }
        scopeJobs.clear()
    }

    private fun TestScope.started(host: FakeHost, prefs: UboPrefs, timeoutMillis: Long = 180_000) =
        controller(controllerScope(), host, prefs, timeoutMillis).also {
            it.start()
            runCurrent()
        }

    @Test
    fun constantsAreTheAmoOnes() {
        assertEquals("uBlock0@raymondhill.net", UBO_ID)
        assertEquals(
            "https://addons.mozilla.org/firefox/downloads/latest/uBlock0@raymondhill.net/latest.xpi",
            UBO_XPI_URL,
        )
    }

    @Test
    fun firstRunShowsOnboarding() = runTest {
        val c = started(FakeHost(), MemoryPrefs())
        assertTrue(c.showOnboarding.value)
        assertEquals(UboState.NotInstalled, c.state.value)
    }

    @Test
    fun acceptSuccessInstallsFromAmoAndHidesOnboarding() = runTest {
        val host = FakeHost()
        val prefs = MemoryPrefs()
        val c = started(host, prefs)
        c.accept()
        advanceUntilIdle()
        assertEquals(UboState.Installed("1.0", true, false), c.state.value)
        assertFalse(c.showOnboarding.value)
        assertEquals(UboDecision.ACCEPTED, prefs.decision)
        assertEquals(listOf(UBO_XPI_URL to UBO_ID), host.installCalls)
    }

    @Test
    fun acceptFailureKeepsOnboardingThenRetrySucceeds() = runTest {
        val host = FakeHost().apply { nextInstallError = ExtensionInstallError.NETWORK }
        val c = started(host, MemoryPrefs())
        c.accept()
        advanceUntilIdle()
        assertEquals(UboState.Failed(UboFailure.NETWORK), c.state.value)
        assertTrue(c.showOnboarding.value)
        c.retry()
        advanceUntilIdle()
        assertEquals(UboState.Installed("1.0", true, false), c.state.value)
        assertFalse(c.showOnboarding.value)
        assertEquals(2, host.installCalls.size)
    }

    @Test
    fun declineFromFreshHidesOnboardingWithoutInstalling() = runTest {
        val host = FakeHost()
        val prefs = MemoryPrefs()
        val c = started(host, prefs)
        c.decline()
        advanceUntilIdle()
        assertFalse(c.showOnboarding.value)
        assertEquals(UboState.NotInstalled, c.state.value)
        assertEquals(UboDecision.DECLINED, prefs.decision)
        assertTrue(host.installCalls.isEmpty())
    }

    @Test
    fun declineFromFailedState() = runTest {
        val host = FakeHost().apply { nextInstallError = ExtensionInstallError.SIGNATURE }
        val prefs = MemoryPrefs()
        val c = started(host, prefs)
        c.accept()
        advanceUntilIdle()
        assertEquals(UboState.Failed(UboFailure.SIGNATURE), c.state.value)
        c.decline()
        assertFalse(c.showOnboarding.value)
        assertEquals(UboState.NotInstalled, c.state.value)
        assertEquals(UboDecision.DECLINED, prefs.decision)
    }

    @Test
    fun acceptedButNotInstalledAutoInstallsAtStart() = runTest {
        val host = FakeHost()
        val c = started(host, MemoryPrefs(decision = UboDecision.ACCEPTED))
        advanceUntilIdle()
        assertEquals(1, host.installCalls.size)
        assertEquals(UboState.Installed("1.0", true, false), c.state.value)
        assertFalse(c.showOnboarding.value)
    }

    @Test
    fun autoInstallFailureLeavesOnboardingHidden() = runTest {
        val host = FakeHost().apply { nextInstallError = ExtensionInstallError.NETWORK }
        val c = started(host, MemoryPrefs(decision = UboDecision.ACCEPTED))
        advanceUntilIdle()
        assertEquals(UboState.Failed(UboFailure.NETWORK), c.state.value)
        assertFalse(c.showOnboarding.value)
    }

    @Test
    fun declinedNeverAutoInstalls() = runTest {
        val host = FakeHost()
        val c = started(host, MemoryPrefs(decision = UboDecision.DECLINED))
        advanceUntilIdle()
        assertTrue(host.installCalls.isEmpty())
        assertEquals(UboState.NotInstalled, c.state.value)
        assertFalse(c.showOnboarding.value)
    }

    @Test
    fun alreadyInstalledHidesOnboardingAndRecordsAccepted() = runTest {
        val host = FakeHost().apply { installed = ext("1.2.3") }
        val prefs = MemoryPrefs(lastUpdateCheckMillis = now)
        val c = started(host, prefs)
        assertEquals(UboState.Installed("1.2.3", true, false), c.state.value)
        assertFalse(c.showOnboarding.value)
        assertEquals(UboDecision.ACCEPTED, prefs.decision)
        assertTrue(host.installCalls.isEmpty())
    }

    @Test
    fun removeRecordsDeclinedAndIsNotReinstalledOnNextStart() = runTest {
        val host = FakeHost().apply { installed = ext() }
        val prefs = MemoryPrefs(lastUpdateCheckMillis = now)
        val c = started(host, prefs)
        c.remove()
        advanceUntilIdle()
        assertEquals(UboState.NotInstalled, c.state.value)
        assertEquals(UboDecision.DECLINED, prefs.decision)
        assertEquals(1, host.uninstallCalls)

        val next = started(host, prefs)
        advanceUntilIdle()
        assertEquals(UboState.NotInstalled, next.state.value)
        assertTrue(host.installCalls.isEmpty())
        assertFalse(next.showOnboarding.value)
    }

    @Test
    fun setEnabledUpdatesState() = runTest {
        val host = FakeHost().apply { installed = ext() }
        val c = started(host, MemoryPrefs(lastUpdateCheckMillis = now))
        c.setEnabled(false)
        advanceUntilIdle()
        assertEquals(UboState.Installed("1.0", false, true), c.state.value)
        c.setEnabled(true)
        advanceUntilIdle()
        assertEquals(UboState.Installed("1.0", true, false), c.state.value)
    }

    @Test
    fun installTimeoutBecomesFailedTimeout() = runTest {
        val host = FakeHost().apply { installDelayMillis = 60_000 }
        val c = started(host, MemoryPrefs(), timeoutMillis = 1_000)
        c.accept()
        advanceUntilIdle()
        assertEquals(UboState.Failed(UboFailure.TIMEOUT), c.state.value)
        assertTrue(c.showOnboarding.value)
        assertEquals(null, host.installed)
    }

    @Test
    fun scopeCancellationIsNotReportedAsFailure() = runTest {
        val host = FakeHost().apply { installDelayMillis = 60_000 }
        val job = Job()
        val c = controller(CoroutineScope(StandardTestDispatcher(testScheduler) + job), host, MemoryPrefs())
        c.start()
        runCurrent()
        c.accept()
        runCurrent()
        assertEquals(UboState.Installing, c.state.value)
        job.cancel()
        advanceUntilIdle()
        assertEquals(UboState.Installing, c.state.value)
    }

    @Test
    fun doubleAcceptInstallsOnlyOnce() = runTest {
        val host = FakeHost().apply { installDelayMillis = 500 }
        val c = started(host, MemoryPrefs())
        c.accept()
        c.accept()
        runCurrent()
        c.accept()
        advanceUntilIdle()
        assertEquals(1, host.installCalls.size)
        assertEquals(UboState.Installed("1.0", true, false), c.state.value)
    }

    @Test
    fun updateCheckOnlyAfter24Hours() = runTest {
        val recent = FakeHost().apply { installed = ext() }
        val recentPrefs = MemoryPrefs(lastUpdateCheckMillis = now - 23 * HOUR)
        started(recent, recentPrefs)
        advanceUntilIdle()
        assertEquals(0, recent.updateCalls)
        assertEquals(now - 23 * HOUR, recentPrefs.lastUpdateCheckMillis)

        val stale = FakeHost().apply {
            installed = ext("1.0")
            updateResult = ext("1.1")
        }
        val stalePrefs = MemoryPrefs(lastUpdateCheckMillis = now - 25 * HOUR)
        val c = started(stale, stalePrefs)
        advanceUntilIdle()
        assertEquals(1, stale.updateCalls)
        assertEquals(now, stalePrefs.lastUpdateCheckMillis)
        assertEquals(UboState.Installed("1.1", true, false), c.state.value)
    }

    @Test
    fun updateFailureKeepsInstalled() = runTest {
        val host = FakeHost().apply {
            installed = ext("1.0")
            updateThrows = true
        }
        val c = started(host, MemoryPrefs(lastUpdateCheckMillis = now - 48 * HOUR))
        advanceUntilIdle()
        assertEquals(1, host.updateCalls)
        assertEquals(UboState.Installed("1.0", true, false), c.state.value)
    }
}
