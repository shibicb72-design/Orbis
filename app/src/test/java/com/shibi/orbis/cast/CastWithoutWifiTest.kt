package com.shibi.orbis.cast

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A phone or TV box without Wi-Fi hardware: the system hands out no WifiManager, NsdManager or ConnectivityManager. */
private class NoWifiContext(base: Context, missing: Set<Class<*>>) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    private val missingNames = missing.mapNotNull { base.getSystemServiceName(it) }.toSet()

    override fun getSystemService(name: String): Any? = if (name in missingNames) null else super.getSystemService(name)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CastWithoutWifiTest {
    private val base: Context = RuntimeEnvironment.getApplication()

    private fun noWifi() = NoWifiContext(base, setOf(WifiManager::class.java, NsdManager::class.java))

    @Test
    fun chromecastDiscoveryStartsAndStopsAndListsNothing() {
        val discovery = ChromecastDiscovery(noWifi())
        var changes = 0
        discovery.onChanged = { changes++ }
        discovery.start()
        assertEquals(emptyList<ChromecastDevice>(), discovery.devices())
        discovery.stop()
        assertEquals(emptyList<ChromecastDevice>(), discovery.devices())
        assertEquals(1, changes)
    }

    @Test
    fun chromecastDiscoveryWithNsdButNoWifiManagerListsNothing() {
        val context = NoWifiContext(base, setOf(WifiManager::class.java))
        val discovery = ChromecastDiscovery(context)
        discovery.start()
        assertEquals(emptyList<ChromecastDevice>(), discovery.devices())
        discovery.stop()
        assertEquals(emptyList<ChromecastDevice>(), discovery.devices())
    }

    @Test
    fun dlnaSearchReturnsNoDevice() {
        val found = DlnaClient(noWifi()).discover(timeoutMs = 800)
        assertEquals(emptyList<DlnaRenderer>(), found)
    }

    @Test
    fun lanNetworkIsNullWithNoWifiAndNoEthernet() {
        val context = NoWifiContext(base, setOf(ConnectivityManager::class.java))
        assertNull(LanBind.lanNetwork(context))
    }
}
