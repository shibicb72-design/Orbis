package com.shibi.orbis.passwords

import com.shibi.orbis.clear.BrowsingDataClearer
import com.shibi.orbis.clear.ClearCategory
import com.shibi.orbis.clear.ClearOutcome
import com.shibi.orbis.clear.ClearSelection
import com.shibi.orbis.clear.ClearTargets
import com.shibi.orbis.clear.clearSavedLogins
import com.shibi.orbis.engine.EngineLogin
import com.shibi.orbis.storage.AtomicTextFile
import com.shibi.orbis.ui.passwords.filterLogins
import java.io.File
import java.security.KeyStoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class MemLoginStore(var data: List<SavedLogin> = emptyList()) : LoginStore {
    var saves = 0
    var asides = 0
    override fun load() = data

    override fun save(items: List<SavedLogin>) {
        saves++
        data = items
    }

    override fun setAside() {
        asides++
        data = emptyList()
    }
}

private class MemNever : NeverSavePrefs {
    override var neverSave: Set<String> = emptySet()
}

/** Not encryption: a reversible stand-in so the file path can be tested without the Android Keystore. */
private class FlipCrypto : LoginCrypto {
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(sealed: ByteArray) = encrypt(sealed)

    override fun reset() {}
}

/** [FlipCrypto] whose first [failures] decrypts throw what the Android Keystore throws when it is briefly unavailable. */
private class FlakyKeystoreCrypto(var failures: Int = 1) : LoginCrypto {
    private val ok = FlipCrypto()

    override fun encrypt(plain: ByteArray) = ok.encrypt(plain)

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (failures > 0) {
            failures--
            throw KeyStoreException("keystore busy")
        }
        return ok.decrypt(sealed)
    }

    override fun reset() {}
}

/**
 * A Keystore key that stays unusable: every encrypt and decrypt throws [KeyStoreException] until [reset] (deleting the
 * alias) gives a fresh key, after which it works like [FlipCrypto].
 */
private class BrokenKeyCrypto : LoginCrypto {
    private val ok = FlipCrypto()
    var resets = 0

    override fun encrypt(plain: ByteArray): ByteArray {
        if (resets == 0) throw KeyStoreException("key unusable")
        return ok.encrypt(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (resets == 0) throw KeyStoreException("key unusable")
        return ok.decrypt(sealed)
    }

    override fun reset() {
        resets++
    }
}

/** Clear browsing data targets where only Passwords does anything, wired like the app. */
private class LoginsOnlyTargets(private val logins: LoginManager) : ClearTargets {
    override fun clearHistory() {}
    override fun closeNormalTabs() {}
    override fun clearDownloadsList() {}
    override fun clearBookmarks() {}
    override fun clearPasswords() = clearSavedLogins(logins)
    override fun hasPrivateTabs() = false
    override suspend fun clearEngine(cookiesAndSiteData: Boolean, cache: Boolean) = true
}

private val PASSWORDS_ONLY = ClearSelection(history = false, cookiesAndSiteData = false, cache = false, passwords = true)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PasswordsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var clock = 1_000L
    private var n = 0

    @After
    fun tearDown() = scope.cancel()

    private fun manager(store: LoginStore = MemLoginStore(), never: NeverSavePrefs = MemNever()): LoginManager =
        LoginManager(store, never, scope, log = {}, now = { clock }, io = dispatcher, newGuid = { "g${++n}" }).also {
            it.load()
            scheduler.advanceUntilIdle()
        }

    // ---- CSV ----

    @Test
    fun importsChromeAndFirefoxExportsByHeader() {
        val chrome = "name,url,username,password,note\r\nexample.com,https://example.com/login,alice,s3cr3t,\r\nbad,not a url,bob,x,\r\n"
        val c = LoginCsv.parse(chrome)
        assertEquals(listOf(ImportedLogin("https://example.com", "alice", "s3cr3t", "", "")), c.logins)
        assertEquals(1, c.skipped)
        val firefox =
            "﻿\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\"\n" +
                "\"https://a.test:443\",\"\",\"p,w\"\"q\",\"\",\"https://a.test/post\",\"{x}\",\"1\"\n"
        val f = LoginCsv.parse(firefox).logins.single()
        assertEquals("https://a.test", f.origin)
        assertEquals("p,w\"q", f.password)
        assertEquals("https://a.test", f.formActionOrigin)
    }

    @Test
    fun exportRoundTripsQuotesCommasAndNewlines() {
        val logins = listOf(SavedLogin("g", "https://a.test", username = "u,1", password = "line1\nline\"2"))
        val back = LoginCsv.parse(LoginCsv.export(logins)).logins.single()
        assertEquals("u,1", back.username)
        assertEquals("line1\nline\"2", back.password)
        assertTrue(LoginCsv.export(logins).startsWith("url,username,password,httpRealm,formActionOrigin"))
    }

    @Test
    fun codecRoundTripsAndSkipsBadEntries() {
        val l = SavedLogin("g1", "https://a.test", "https://a.test", "", "u", "p", 1, 2, 3)
        assertEquals(listOf(l), LoginCodec.decode(LoginCodec.encode(listOf(l))))
        val bad = """{"version":1,"logins":[{"guid":"x","origin":"javascript:1","username":"u","password":"p"},{"guid":"y","origin":"https://b.test","password":""}]}"""
        assertTrue(LoginCodec.decode(bad).isEmpty())
    }

    @Test
    fun domainMatchingCoversSubdomainsOnly() {
        assertTrue(loginMatchesDomain("https://accounts.example.com", "example.com"))
        assertTrue(loginMatchesDomain("https://example.com", "example.com"))
        assertFalse(loginMatchesDomain("https://notexample.com", "example.com"))
        assertFalse(loginMatchesDomain("https://example.com.evil.test", "example.com"))
    }

    // ---- manager ----

    @Test
    fun saveAddsThenUpdatesTheSameSiteAndUsername() {
        val store = MemLoginStore()
        val m = manager(store)
        assertTrue(m.save("https://a.test/login", "alice", "one"))
        assertFalse(m.isUpdate("https://a.test", "alice", "one"))
        assertTrue(m.isUpdate("https://a.test", "alice", "two"))
        m.save("https://a.test", "alice", "two")
        m.save("https://a.test", "bob", "three")
        scheduler.advanceUntilIdle()
        assertEquals(listOf("alice" to "two", "bob" to "three"), store.data.map { it.username to it.password })
        assertEquals(listOf("https://a.test", "https://a.test"), store.data.map { it.origin })
        assertEquals(2, m.forDomain("a.test").size)
        assertTrue(m.forDomain("b.test").isEmpty())
    }

    @Test
    fun usedAndDeleteAreRecorded() {
        val m = manager()
        m.save("https://a.test", "u", "p")
        val g = m.logins.value.single().guid
        clock = 5_000
        m.markUsed(g)
        assertEquals(1, m.logins.value.single().timesUsed)
        assertEquals(5_000L, m.logins.value.single().timeLastUsed)
        m.delete(g)
        assertTrue(m.logins.value.isEmpty())
    }

    @Test
    fun importKeepsExistingLoginsAndCountsSkips() {
        val m = manager()
        m.save("https://a.test", "alice", "mine")
        val r = m.importCsv("url,username,password\nhttps://a.test,alice,theirs\nhttps://b.test,bob,pw\nftp://c.test,c,pw\n")
        assertEquals(LoginImportResult(added = 1, skipped = 2), r)
        assertEquals("mine", m.logins.value.first { it.username == "alice" }.password)
        assertTrue(m.exportCsv().contains("https://b.test,bob,pw"))
    }

    @Test
    fun nothingIsOfferedOrSavedBeforeTheStoreIsLoaded() {
        val m = LoginManager(MemLoginStore(), MemNever(), scope, log = {}, now = { clock }, io = dispatcher)
        assertFalse(m.save("https://a.test", "u", "p"))
        assertTrue(m.forDomain("a.test").isEmpty())
    }

    @Test
    fun bridgeNeverOffersInPrivateTabsNeverSitesOrWithSavingOff() {
        val never = MemNever()
        val m = manager(never = never)
        var on = true
        val bridge = LoginBridgeImpl(m) { on }
        assertTrue(bridge.offerSave("https://a.test", isPrivate = false))
        assertFalse("private tab", bridge.offerSave("https://a.test", isPrivate = true))
        bridge.neverFor("https://a.test/login")
        assertFalse("never for this site", bridge.offerSave("https://a.test", isPrivate = false))
        assertEquals(setOf("https://a.test"), never.neverSave)
        assertTrue(bridge.offerSave("https://b.test", isPrivate = false))
        on = false
        assertFalse("setting off", bridge.offerSave("https://b.test", isPrivate = false))
        bridge.save(EngineLogin(null, "https://b.test", "", "", "u", "p"))
        assertEquals(listOf("https://b.test"), bridge.fetch("b.test").map { it.origin })
    }

    @Test
    fun searchMatchesSiteOrUsername() {
        val logins = listOf(SavedLogin("1", "https://www.mail.test", username = "zed", password = "p"), SavedLogin("2", "https://bank.test", username = "amy", password = "p"))
        assertEquals(listOf("2"), filterLogins(logins, "BANK").map { it.guid })
        assertEquals(listOf("1"), filterLogins(logins, "zed").map { it.guid })
        assertEquals(listOf("2", "1"), filterLogins(logins, "").map { it.guid })
    }

    // ---- file ----

    @Test
    fun fileStoreIsNotPlainTextAndRoundTrips() {
        val file = File(tmp.root, "logins/logins.enc")
        val s = FileLoginStore(AtomicTextFile(file), FlipCrypto())
        s.save(listOf(SavedLogin("g", "https://a.test", username = "alice", password = "hunter2")))
        assertFalse(file.readText().contains("hunter2"))
        assertFalse(file.readText().contains("alice"))
        assertEquals("hunter2", FileLoginStore(AtomicTextFile(file), FlipCrypto()).load().single().password)
    }

    @Test
    fun anUndecryptableFileIsSetAsideNotOverwrittenSilently() {
        val file = File(tmp.root, "logins/logins.enc")
        file.parentFile!!.mkdirs()
        file.writeText("not base64 or not ours")
        val s = FileLoginStore(AtomicTextFile(file), FlipCrypto())
        assertTrue(s.load().isEmpty())
        assertTrue(File(file.parentFile, "logins.enc.corrupt").exists())
        s.save(emptyList())
        assertFalse("an emptied store drops the set-aside copy", File(file.parentFile, "logins.enc.corrupt").exists())
        assertNotEquals("not base64 or not ours", file.readText())
    }

    @Test
    fun badJsonInsideAValidCipherIsSetAside() {
        val file = File(tmp.root, "logins/logins.enc")
        file.parentFile!!.mkdirs()
        file.writeText(android.util.Base64.encodeToString(FlipCrypto().encrypt("{not json".toByteArray()), android.util.Base64.NO_WRAP))
        assertTrue(FileLoginStore(AtomicTextFile(file), FlipCrypto()).load().isEmpty())
        assertFalse(file.exists())
        assertTrue(File(file.parentFile, "logins.enc.corrupt").exists())
    }

    @Test
    fun aTransientKeystoreErrorKeepsTheFileAndRefusesToSave() {
        val file = File(tmp.root, "logins/logins.enc")
        FileLoginStore(AtomicTextFile(file), FlipCrypto()).save(listOf(SavedLogin("g", "https://a.test", username = "alice", password = "hunter2")))
        val before = file.readText()
        val s = FileLoginStore(AtomicTextFile(file), FlakyKeystoreCrypto())
        assertThrows(KeyStoreException::class.java) { s.load() }
        assertEquals("file untouched", before, file.readText())
        assertFalse("no aside for a transient error", File(file.parentFile, "logins.enc.corrupt").exists())
        assertThrows(IllegalStateException::class.java) { s.save(emptyList()) }
        assertEquals("a refused save does not write", before, file.readText())
        assertEquals("hunter2", s.load().single().password)
        s.save(emptyList())
        assertNotEquals("saving works again after a good load", before, file.readText())
    }

    @Test
    fun managerStaysUnloadedAfterATransientErrorAndRetries() {
        val file = File(tmp.root, "logins/logins.enc")
        FileLoginStore(AtomicTextFile(file), FlipCrypto()).save(listOf(SavedLogin("g", "https://a.test", username = "alice", password = "hunter2")))
        val logs = mutableListOf<String>()
        val m = LoginManager(FileLoginStore(AtomicTextFile(file), FlakyKeystoreCrypto()), MemNever(), scope, log = { logs += it }, now = { clock }, io = dispatcher)
        m.load()
        scheduler.advanceUntilIdle()
        assertFalse(m.loaded)
        assertEquals(listOf("logins load failed: KeyStoreException"), logs)
        assertFalse(m.save("https://b.test", "bob", "pw"))
        assertTrue(m.forDomain("a.test").isEmpty())
        m.load()
        scheduler.advanceUntilIdle()
        assertTrue(m.loaded)
        assertEquals(listOf("hunter2"), m.forDomain("a.test").map { it.password })
        assertFalse(File(file.parentFile, "logins.enc.corrupt").exists())
    }

    @Test
    fun clearAllDeletesEveryLoginAndPersistsTheEmptyList() {
        val store = MemLoginStore(listOf(SavedLogin("g1", "https://a.test", username = "u", password = "p")))
        val m = manager(store)
        assertEquals(1, m.logins.value.size)
        m.clearAll()
        scheduler.advanceUntilIdle()
        assertTrue(m.logins.value.isEmpty())
        assertTrue(store.data.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun clearAllIsRefusedBeforeLoad() {
        LoginManager(MemLoginStore(), MemNever(), scope, log = {}, now = { clock }, io = dispatcher).clearAll()
    }

    // ---- unreadable store: the user's way out ----

    /** A file sealed with the old key, and a manager whose key stays unusable, after one failed load. */
    private fun unreadable(logs: MutableList<String> = mutableListOf()): Triple<File, BrokenKeyCrypto, LoginManager> {
        val file = File(tmp.root, "logins/logins.enc")
        FileLoginStore(AtomicTextFile(file), FlipCrypto()).save(listOf(SavedLogin("g", "https://a.test", username = "alice", password = "hunter2")))
        val crypto = BrokenKeyCrypto()
        val m = LoginManager(FileLoginStore(AtomicTextFile(file), crypto), MemNever(), scope, log = { logs += it }, now = { clock }, io = dispatcher)
        m.load()
        scheduler.advanceUntilIdle()
        return Triple(file, crypto, m)
    }

    @Test
    fun aStoreThatStaysUnreadableIsReportedAndNeverResetOnItsOwn() {
        val (file, crypto, m) = unreadable()
        val before = file.readText()
        repeat(3) {
            m.load()
            scheduler.advanceUntilIdle()
        }
        assertFalse(m.loaded)
        assertTrue(m.loadFailed.value)
        assertFalse(m.save("https://b.test", "bob", "pw"))
        assertEquals("no reset without the user", 0, crypto.resets)
        assertEquals("file untouched", before, file.readText())
        assertFalse(File(file.parentFile, "logins.enc.corrupt").exists())
    }

    @Test
    fun resetUnreadableStoreSetsTheFileAsideResetsTheKeyAndSavingWorksAgain() {
        val logs = mutableListOf<String>()
        val (file, crypto, m) = unreadable(logs)
        assertTrue(m.resetUnreadableStore())
        scheduler.advanceUntilIdle()
        assertEquals(1, crypto.resets)
        assertFalse(file.exists())
        assertTrue(File(file.parentFile, "logins.enc.corrupt").exists())
        assertTrue(m.loaded)
        assertFalse(m.loadFailed.value)
        assertTrue(m.logins.value.isEmpty())
        assertEquals(listOf("logins load failed: KeyStoreException", "logins store reset", "logins loaded 0"), logs)
        assertTrue(m.save("https://b.test", "bob", "pw"))
        scheduler.advanceUntilIdle()
        assertEquals(listOf("bob"), FileLoginStore(AtomicTextFile(file), FlipCrypto()).load().map { it.username })
        assertFalse("nothing to reset once loaded", m.resetUnreadableStore())
        assertEquals(1, crypto.resets)
    }

    @Test
    fun resetIsRefusedUnlessTheLastLoadFailed() {
        val store = MemLoginStore(listOf(SavedLogin("g1", "https://a.test", username = "u", password = "p")))
        val m = LoginManager(store, MemNever(), scope, log = {}, now = { clock }, io = dispatcher)
        assertFalse("before any load", m.resetUnreadableStore())
        m.load()
        assertFalse("load running", m.resetUnreadableStore())
        scheduler.advanceUntilIdle()
        assertFalse(m.loadFailed.value)
        assertFalse("loaded", m.resetUnreadableStore())
        scheduler.advanceUntilIdle()
        assertEquals(0, store.asides)
        assertEquals(1, m.logins.value.size)
    }

    @Test
    fun clearBrowsingDataPasswordsResetsAStoreThatStaysUnreadable() {
        val (file, crypto, m) = unreadable()
        val outcome = runBlocking { BrowsingDataClearer(LoginsOnlyTargets(m)).run(PASSWORDS_ONLY) }
        assertEquals(ClearOutcome.Done, outcome)
        scheduler.advanceUntilIdle()
        assertEquals(1, crypto.resets)
        assertFalse(file.exists())
        assertTrue(m.loaded)
        assertTrue(m.save("https://b.test", "bob", "pw"))
    }

    @Test
    fun clearBrowsingDataPasswordsStillEmptiesALoadedStoreAndFailsWhileLoading() {
        val store = MemLoginStore(listOf(SavedLogin("g1", "https://a.test", username = "u", password = "p")))
        val m = manager(store)
        assertEquals(ClearOutcome.Done, runBlocking { BrowsingDataClearer(LoginsOnlyTargets(m)).run(PASSWORDS_ONLY) })
        scheduler.advanceUntilIdle()
        assertTrue(store.data.isEmpty())
        assertEquals(0, store.asides)
        val loading = LoginManager(MemLoginStore(), MemNever(), scope, log = {}, now = { clock }, io = dispatcher)
        loading.load()
        val outcome = runBlocking { BrowsingDataClearer(LoginsOnlyTargets(loading)).run(PASSWORDS_ONLY) }
        assertEquals(ClearOutcome.Incomplete(setOf(ClearCategory.PASSWORDS), emptySet()), outcome)
    }
}
