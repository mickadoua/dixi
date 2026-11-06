package tech.dmconsulting.voxbulle.paste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledServicesTest {
    private val pkg = "tech.dmconsulting.dixi"
    private val cls = "tech.dmconsulting.voxbulle.paste.AutoPasteService"

    private fun listed(setting: String?) = isServiceListed(setting, pkg, cls)

    @Test
    fun nothingEnabledIsOff() {
        assertFalse(listed(null))
        assertFalse(listed(""))
    }

    @Test
    fun fullClassNameIsFound() {
        assertTrue(listed("$pkg/$cls"))
    }

    @Test
    fun shortClassNameIsFound() {
        val samePackage = "tech.dmconsulting.voxbulle"
        assertTrue(isServiceListed("$samePackage/.paste.AutoPasteService", samePackage, cls))
    }

    // Since DMC-383 the applicationId (tech.dmconsulting.dixi) differs from the class package
    // (tech.dmconsulting.voxbulle): a short name is relative to the applicationId, so it is not ours.
    @Test
    fun shortNameRelativeToTheApplicationIdIsNotOurs() {
        assertFalse(listed("$pkg/.paste.AutoPasteService"))
    }

    @Test
    fun foundAmongOtherServices() {
        assertTrue(listed("com.example.reader/.Reader:$pkg/$cls:org.other/org.other.Svc"))
    }

    @Test
    fun anotherAppWithTheSameClassIsNotOurs() {
        // tech.dmconsulting.voxbulle is the old applicationId (test installs before DMC-383).
        assertFalse(listed("tech.dmconsulting.dixi.copy/$cls"))
        assertFalse(listed("tech.dmconsulting.voxbulle/$cls"))
    }

    @Test
    fun anotherServiceOfTheSamePackageIsNotOurs() {
        assertFalse(listed("$pkg/tech.dmconsulting.voxbulle.Other"))
        assertFalse(listed("$pkg/.Other"))
    }
}
