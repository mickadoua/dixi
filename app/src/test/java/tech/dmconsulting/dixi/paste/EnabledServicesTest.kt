package tech.dmconsulting.dixi.paste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledServicesTest {
    private val pkg = "tech.dmconsulting.dixi"
    private val cls = "tech.dmconsulting.dixi.paste.AutoPasteService"

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

    // Since DMC-653 the class package equals the applicationId: the short name is ours.
    @Test
    fun shortClassNameIsFound() {
        assertTrue(listed("$pkg/.paste.AutoPasteService"))
    }

    // A short name is relative to the applicationId: with another class package, it is not ours.
    @Test
    fun shortNameRelativeToAnotherPackageIsNotOurs() {
        assertFalse(isServiceListed("$pkg/.paste.AutoPasteService", pkg, "com.example.paste.AutoPasteService"))
    }

    @Test
    fun foundAmongOtherServices() {
        assertTrue(listed("com.example.reader/.Reader:$pkg/$cls:org.other/org.other.Svc"))
    }

    @Test
    fun anotherAppWithTheSameClassIsNotOurs() {
        assertFalse(listed("tech.dmconsulting.dixi.copy/$cls"))
        assertFalse(listed("com.example.other/$cls"))
    }

    @Test
    fun anotherServiceOfTheSamePackageIsNotOurs() {
        assertFalse(listed("$pkg/tech.dmconsulting.dixi.Other"))
        assertFalse(listed("$pkg/.Other"))
    }
}
