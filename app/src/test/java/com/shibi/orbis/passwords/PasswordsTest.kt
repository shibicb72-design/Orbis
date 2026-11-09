package com.shibi.orbis.passwords

import com.shibi.orbis.engine.EngineLogin
import com.shibi.orbis.storage.AtomicTextFile
import com.shibi.orbis.ui.passwords.filterLogins
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class MemLoginStore(var data: List<SavedLogin> = emptyList()) : LoginStore {
    var saves = 0
    override fun load() = data

    override fun save(items: List<SavedLogin>) {
        saves++
        data = items
    }
}

private class MemNever : NeverSavePrefs {
    override var neverSave: Set<String> = emptySet()
}

/** Not encryption: a reversible stand-in so the file path can be tested without the Android Keystore. */
private class FlipCrypto : LoginCrypto {
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(sealed: ByteArray) = encrypt(sealed)
}

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
}
