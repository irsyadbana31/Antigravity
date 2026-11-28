package id.vern.antigravity.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundProfileTest {

    @Test
    fun detectOemBackgroundProfile_recognizesTranssionAndMajorOems() {
        for (vendor in listOf("INFINIX", "Infinix", "TECNO", "itel", "Transsion")) {
            val profile = PermissionHelper.detectOemBackgroundProfile(vendor)
            assertEquals("transsion", profile.vendorKey)
            assertTrue(profile.hasAggressiveFreezer)
            assertTrue(profile.guideEn.contains("Usf_Hiber"))
            assertTrue(profile.guideId.contains("Usf_Hiber"))
        }

        assertEquals("xiaomi", PermissionHelper.detectOemBackgroundProfile("Xiaomi").vendorKey)
        assertEquals("xiaomi", PermissionHelper.detectOemBackgroundProfile("POCO").vendorKey)
        assertEquals("samsung", PermissionHelper.detectOemBackgroundProfile("samsung").vendorKey)
        assertEquals("oplus", PermissionHelper.detectOemBackgroundProfile("OnePlus").vendorKey)
        assertEquals("vivo", PermissionHelper.detectOemBackgroundProfile("vivo").vendorKey)
        assertEquals("huawei", PermissionHelper.detectOemBackgroundProfile("HUAWEI").vendorKey)

        val generic = PermissionHelper.detectOemBackgroundProfile("Google")
        assertEquals("generic", generic.vendorKey)
        assertFalse(generic.hasAggressiveFreezer)
    }
}
