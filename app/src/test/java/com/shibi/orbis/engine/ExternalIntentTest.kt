package com.shibi.orbis.engine

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExternalIntentTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun httpPassesThroughAndIsNotAnExternalIntent() {
        val route = routeExternalNavigation("https://example.com/watch?v=1", context.packageName)
        assertTrue(route is ExternalNavigation.Web)
        assertNull((route as ExternalNavigation.Web).url)
        assertNull(ExternalApps(context).forExternalScheme("https://example.com/watch?v=1"))
    }

    @Test
    fun magnetMarketAndUnknownSchemesAreViewIntents() {
        listOf(
            "magnet:?xt=urn:btih:abc123",
            "market://details?id=com.example.app",
            "myapp:open",
            "geo:0,0?q=paris",
        ).forEach { uri ->
            val route = routeExternalNavigation(uri, context.packageName)
            assertTrue(uri, route is ExternalNavigation.App)
            val app = route as ExternalNavigation.App
            val intent = app.intent!!
            assertEquals(uri, Intent.ACTION_VIEW, intent.action)
            assertEquals(uri, schemeOf(uri), intent.data?.scheme)
            assertTrue(uri, intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertNull(uri, intent.component)
            assertNull(uri, app.fallbackUrl)
            assertNull(uri, app.marketPackage)
        }
    }

    @Test
    fun anUnresolvedHandlerIsStillLaunchedInsteadOfDeclaredMissing() {
        // The phone can have the app installed and still return null from resolveActivity (package visibility).
        assertEquals(ExternalOpenChoice.LAUNCH, externalOpenChoice(hasIntent = true, resolvable = false))
    }

    @Test
    fun aMagnetOnlyOrbisHandlesIsNeverSentBackToOrbis() {
        // Orbis claims magnet: itself (LinkRouterActivity). With no other app, there is nothing to launch: the "no app"
        // offer follows instead of Orbis opening its own link.
        val target = ExternalApps(context).forExternalScheme("magnet:?xt=urn:btih:abc123")!!
        assertFalse(target.resolvable)
        assertNull(target.intent)
        assertEquals(ExternalOpenChoice.MISS, externalOpenChoice(target.intent != null, target.resolvable))
        assertEquals(ExternalMiss.NoHandler, planExternalMiss(target.fallbackUrl, target.marketPackage))
    }

    @Test
    fun intentLinkKeepsFallbackAndMarketAndDropsTheAttackSurface() {
        val uri =
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;" +
                "component=com.google.zxing.client.android/com.google.zxing.client.android.CaptureActivity;" +
                "launchFlags=0x10000000;" +
                "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffb;end"
        val route = routeExternalNavigation(uri, context.packageName)
        assertTrue(route is ExternalNavigation.App)
        val app = route as ExternalNavigation.App
        val intent = app.intent!!
        assertNull(intent.component)
        assertNull(intent.selector)
        assertEquals(0, intent.flags)
        assertNull("page extras are not forwarded", intent.getStringExtra("browser_fallback_url"))
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("zxing", intent.data?.scheme)
        assertEquals("https://example.com/fb", app.fallbackUrl)
        assertEquals("com.google.zxing.client.android", app.marketPackage)
        assertEquals("com.google.zxing.client.android", intent.`package`)
        // Fallback page wins when the app is not installed; the store link is the next try.
        assertEquals(ExternalMiss.OpenWeb("https://example.com/fb"), planExternalMiss(app.fallbackUrl, app.marketPackage))
        assertEquals(
            ExternalMiss.OpenMarket("market://details?id=com.google.zxing.client.android"),
            planExternalMiss(null, app.marketPackage),
        )
    }

    @Test
    fun intentLinkThatIsReallyHttpsStaysInTheBrowser() {
        val route = routeExternalNavigation("intent://example.com/path#Intent;scheme=https;end", context.packageName)
        assertTrue(route is ExternalNavigation.Web)
        assertEquals("https://example.com/path", (route as ExternalNavigation.Web).url)
    }

    @Test
    fun intentLinkCannotTargetThisAppAFileOrAScriptFallback() {
        val own = context.packageName
        assertTrue(routeExternalNavigation("intent:#Intent;package=$own;scheme=myapp;end", own) is ExternalNavigation.Blocked)
        assertTrue(
            routeExternalNavigation("intent://secret#Intent;scheme=file;end", own) is ExternalNavigation.Blocked,
        )
        val script =
            routeExternalNavigation(
                "intent://x#Intent;scheme=myapp;S.browser_fallback_url=javascript%3Aalert(1);end",
                own,
            ) as ExternalNavigation.App
        assertNull(script.fallbackUrl)
        assertEquals(ExternalMiss.NoHandler, planExternalMiss(script.fallbackUrl, script.marketPackage))
    }
}
