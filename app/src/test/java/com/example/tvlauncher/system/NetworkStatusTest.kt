package com.example.tvlauncher.system

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkStatusTest {

    @Test
    fun `no internet capability is offline regardless of transport`() {
        assertEquals(NetworkState.OFFLINE, classifyNetwork(hasInternet = false, hasWifi = true, hasEthernet = true))
        assertEquals(NetworkState.OFFLINE, classifyNetwork(hasInternet = false, hasWifi = false, hasEthernet = false))
    }

    @Test
    fun `ethernet takes priority when both transports are somehow reported`() {
        assertEquals(NetworkState.ETHERNET, classifyNetwork(hasInternet = true, hasWifi = true, hasEthernet = true))
    }

    @Test
    fun `wifi only is reported as wifi`() {
        assertEquals(NetworkState.WIFI, classifyNetwork(hasInternet = true, hasWifi = true, hasEthernet = false))
    }

    @Test
    fun `neither wifi nor ethernet but online falls back to other_online, never a hardcoded wifi icon`() {
        assertEquals(NetworkState.OTHER_ONLINE, classifyNetwork(hasInternet = true, hasWifi = false, hasEthernet = false))
    }
}
