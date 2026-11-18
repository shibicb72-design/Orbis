package com.shibi.orbis.addons

import com.shibi.orbis.engine.AddonAccess
import com.shibi.orbis.engine.ExtensionHost
import com.shibi.orbis.engine.ExtensionInstallError
import com.shibi.orbis.engine.ExtensionInstallException
import com.shibi.orbis.engine.InstalledExtension
import com.shibi.orbis.ubo.UBO_ID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val DARK_READER = AddonCatalog.entries.first { it.name == "Dark Reader" }
private val ACCESS = AddonAccess(listOf("storage", "tabs"), listOf("<all_urls>"))

/**
 * Models the engine: the confirm path grants only when [confirm] says yes and never allows private tabs; the
 * uBlock Origin path (no confirm) grants and allows private tabs, so a manager using it would be caught.
 */
private class FakeHost : ExtensionHost {
    val installed = linkedMapOf<String, InstalledExtension>()
    val installCalls = mutableListOf<Pair<String, String>>()
    val calls = mutableListOf<String>()
    var reportedId: String? = null
    var nextError: ExtensionInstallError? = null
    var engineDelayMillis = 0L
    var hang = false

    override suspend fun find(id: String): InstalledExtension? = installed[id]

    override suspend fun list(): List<InstalledExtension> = installed.values.toList()

    override suspend fun install(xpiUrl: String, expectedId: String): InstalledExtension {
        installCalls += xpiUrl to expectedId
        return ext(expectedId, allowedInPrivate = true).also { installed[it.id] = it }
    }

    override suspend fun install(
        xpiUrl: String,
        expectedId: String,
        confirm: suspend (AddonAccess) -> Boolean,
    ): InstalledExtension {
        installCalls += xpiUrl to expectedId
        if (engineDelayMillis > 0) delay(engineDelayMillis)
        nextError?.let { throw ExtensionInstallException(it) }
        if (!confirm(ACCESS)) throw ExtensionInstallException(ExtensionInstallError.CANCELLED)
        if (hang) awaitCancellation()
        val result = ext(reportedId ?: expectedId, allowedInPrivate = false)
        installed[result.id] = result
        return result
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): InstalledExtension {
        calls += "setEnabled $id $enabled"
        return installed.getValue(id).copy(enabled = enabled, disabledByUser = !enabled).also { installed[id] = it }
    }

    override suspend fun allowInPrivateBrowsing(id: String) {
        calls += "allowInPrivateBrowsing $id"
    }

    override suspend fun setAllowedInPrivateBrowsing(id: String, allowed: Boolean): InstalledExtension {
        calls += "setAllowedInPrivate $id $allowed"
        return installed.getValue(id).copy(allowedInPrivate = allowed).also { installed[id] = it }
    }

    override suspend fun uninstall(id: String) {
        calls += "uninstall $id"
        installed.remove(id)
    }

    override suspend fun checkForUpdate(id: String): InstalledExtension? = null
}

private fun ext(id: String, allowedInPrivate: Boolean = false, version: String = "4.9.135") =
    InstalledExtension(id, version, enabled = true, disabledByUser = false, allowedInPrivate = allowedInPrivate)

@OptIn(ExperimentalCoroutinesApi::class)
class AddonManagerTest {
    private val logs = mutableListOf<String>()
    private val scopeJobs = mutableListOf<Job>()

    // A real scope on the test scheduler: advanceUntilIdle() does not run backgroundScope work.
    private fun TestScope.manager(host: FakeHost, timeoutMillis: Long = 180_000): AddonManager {
        val job = SupervisorJob()
        scopeJobs += job
        return AddonManager(host, CoroutineScope(StandardTestDispatcher(testScheduler) + job), { logs += it }, timeoutMillis)
    }

    @After
    fun cancelScopes() {
        scopeJobs.forEach { it.cancel() }
        scopeJobs.clear()
    }

    @Test
    fun `confirmed install ends Installed and passes the reported access to confirm`() =
        runTest {
            val host = FakeHost()
            val m = manager(host)
            var seen: AddonAccess? = null

            assertTrue(m.install(DARK_READER.id) { seen = it; true })
            assertEquals(AddonState.Installing, m.states.value[DARK_READER.id])
            advanceUntilIdle()

            assertEquals(ACCESS, seen)
            assertEquals(listOf(DARK_READER.xpiUrl to DARK_READER.id), host.installCalls)
            assertEquals(AddonState.Installed("4.9.135", true, false, null), m.states.value[DARK_READER.id])
        }

    @Test
    fun `install of an id outside the catalogue fails without calling the host`() =
        runTest {
            val host = FakeHost()
            val m = manager(host)

            assertFalse(m.install(UBO_ID) { true })
            assertFalse(m.install("evil@example.com") { true })
            advanceUntilIdle()

            assertTrue(host.installCalls.isEmpty())
            assertTrue(m.states.value.isEmpty())
        }

    @Test
    fun `confirm returning false ends NotInstalled and the host reports CANCELLED`() =
        runTest {
            val host = FakeHost()
            val m = manager(host)
            var asked = 0

            assertTrue(m.install(DARK_READER.id) { asked++; false })
            advanceUntilIdle()

            assertEquals(1, asked)
            assertEquals(AddonState.NotInstalled, m.states.value[DARK_READER.id])
            assertTrue(host.installed.isEmpty())
            assertTrue(logs.any { it.contains("CANCELLED") })
        }

    @Test
    fun `host returning a different id ends Failed WRONG_EXTENSION`() =
        runTest {
            val host = FakeHost().apply { reportedId = "someone-else@example.com" }
            val m = manager(host)

            m.install(DARK_READER.id) { true }
            advanceUntilIdle()

            assertEquals(AddonState.Failed(AddonFailure.WRONG_EXTENSION), m.states.value[DARK_READER.id])
        }

    @Test
    fun `a new install is not allowed in private tabs`() =
        runTest {
            val host = FakeHost()
            val m = manager(host)

            m.install(DARK_READER.id) { true }
            advanceUntilIdle()

            val state = m.states.value[DARK_READER.id] as AddonState.Installed
            assertFalse(state.allowedInPrivate)
            assertFalse(host.calls.any { it.startsWith("allowInPrivateBrowsing") || it.startsWith("setAllowedInPrivate") })
        }

    @Test
    fun `engine errors map to Failed`() =
        runTest {
            val host = FakeHost().apply { nextError = ExtensionInstallError.BLOCKED }
            val m = manager(host)

            m.install(DARK_READER.id) { true }
            advanceUntilIdle()

            assertEquals(AddonState.Failed(AddonFailure.BLOCKED), m.states.value[DARK_READER.id])
        }

    @Test
    fun `time spent waiting for the user does not count, engine time does`() =
        runTest {
            val host = FakeHost()
            val m = manager(host, timeoutMillis = 10_000)
            val answer = CompletableDeferred<Boolean>()

            m.install(DARK_READER.id) { answer.await() }
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(AddonState.Installing, m.states.value[DARK_READER.id])
            answer.complete(true)
            advanceUntilIdle()
            assertTrue(m.states.value[DARK_READER.id] is AddonState.Installed)

            val slow = FakeHost().apply { hang = true }
            val m2 = manager(slow, timeoutMillis = 10_000)
            m2.install(DARK_READER.id) { true }
            advanceUntilIdle()
            assertEquals(AddonState.Failed(AddonFailure.TIMEOUT), m2.states.value[DARK_READER.id])
        }

    @Test
    fun `operations run one at a time`() =
        runTest {
            val host = FakeHost().apply { engineDelayMillis = 5_000 }
            val m = manager(host)
            val other = AddonCatalog.entries.first { it.name == "ClearURLs" }

            m.install(DARK_READER.id) { true }
            m.install(other.id) { true }
            advanceTimeBy(2_500)
            runCurrent()
            // The second install has not reached the engine while the first is in flight.
            assertEquals(1, host.installCalls.size)
            advanceUntilIdle()
            assertEquals(2, host.installCalls.size)
            assertTrue(m.states.value[other.id] is AddonState.Installed)
        }

    @Test
    fun `enable, private tabs and remove act only on installed catalogue add-ons`() =
        runTest {
            val host = FakeHost()
            host.installed[UBO_ID] = ext(UBO_ID, allowedInPrivate = true)
            val m = manager(host)

            m.refresh()
            advanceUntilIdle()
            assertEquals(AddonCatalog.entries.map { it.id }.toSet(), m.states.value.keys)
            assertTrue(m.states.value.values.all { it == AddonState.NotInstalled })

            assertFalse(m.setEnabled(UBO_ID, false))
            assertFalse(m.setAllowedInPrivate(UBO_ID, false))
            assertFalse(m.remove(UBO_ID))
            assertFalse(m.setEnabled(DARK_READER.id, false))

            m.install(DARK_READER.id) { true }
            advanceUntilIdle()
            assertTrue(m.setAllowedInPrivate(DARK_READER.id, true))
            assertTrue(m.setEnabled(DARK_READER.id, false))
            advanceUntilIdle()
            assertEquals(AddonState.Installed("4.9.135", false, true, null), m.states.value[DARK_READER.id])

            assertTrue(m.remove(DARK_READER.id))
            advanceUntilIdle()
            assertEquals(AddonState.NotInstalled, m.states.value[DARK_READER.id])
            assertTrue(host.calls.none { it.contains(UBO_ID) })
            assertTrue(UBO_ID in host.installed)
        }

    @Test
    fun `refresh reports installed catalogue add-ons with their switches`() =
        runTest {
            val host = FakeHost()
            host.installed[DARK_READER.id] = ext(DARK_READER.id, allowedInPrivate = true).copy(enabled = false)
            val m = manager(host)

            m.refresh()
            advanceUntilIdle()

            assertEquals(AddonState.Installed("4.9.135", false, true, null), m.states.value[DARK_READER.id])
            assertFalse(m.install(DARK_READER.id) { true })
        }
}
