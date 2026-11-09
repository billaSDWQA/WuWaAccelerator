package com.example.wuwaaccelerator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedDevicePolicyTest {

    @Test
    fun acceptsOnlySamsungSmA5560() {
        assertTrue(SupportedDevicePolicy.isSupported("samsung", "SM-A5560"))
        assertTrue(SupportedDevicePolicy.isSupported("Samsung", " sm-a5560 "))
    }

    @Test
    fun rejectsOtherModelsAndManufacturers() {
        assertFalse(SupportedDevicePolicy.isSupported("samsung", "SM-A556E"))
        assertFalse(SupportedDevicePolicy.isSupported("Google", "SM-A5560"))
        assertFalse(SupportedDevicePolicy.isSupported(null, "SM-A5560"))
        assertFalse(SupportedDevicePolicy.isSupported("samsung", null))
    }
}
