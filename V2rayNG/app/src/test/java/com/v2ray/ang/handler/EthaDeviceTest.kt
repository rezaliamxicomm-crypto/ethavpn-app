package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EthaDeviceTest {
    @Test
    fun theCodeIsSixteenHexOfTheIdAndThePackageAndNothingWithoutAnId() {
        val a = EthaDevice.hashOf("9774d56d682e549c", "com.allion.skyray")!!
        assertEquals(16, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(a, EthaDevice.hashOf(" 9774d56d682e549c ", "com.allion.skyray"))
        assertNotEquals(a, EthaDevice.hashOf("9774d56d682e549d", "com.allion.skyray"))
        assertNotEquals(a, EthaDevice.hashOf("9774d56d682e549c", "com.other.app"))
        assertNull(EthaDevice.hashOf("", "com.allion.skyray"))
        assertNull(EthaDevice.hashOf(null, "com.allion.skyray"))
    }

    @Test
    fun theCarrierComesOnlyFromAMobileSignatureWithProperDigits() {
        assertEquals("43235", EthaNetwork.carrier("cell:43235"))
        assertEquals("432011", EthaNetwork.carrier("cell:432011"))
        assertNull(EthaNetwork.carrier("cell:unknown"))
        assertNull(EthaNetwork.carrier("cell:4321"))
        assertNull(EthaNetwork.carrier("wifi"))
        assertNull(EthaNetwork.carrier("other"))
    }
}
