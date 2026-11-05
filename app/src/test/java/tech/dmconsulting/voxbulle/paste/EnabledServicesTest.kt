package tech.dmconsulting.voxbulle.paste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledServicesTest {
    private val pkg = "tech.dmconsulting.voxbulle"
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
        assertTrue(listed("$pkg/.paste.AutoPasteService"))
    }

    @Test
    fun foundAmongOtherServices() {
        assertTrue(listed("com.example.reader/.Reader:$pkg/$cls:org.other/org.other.Svc"))
    }

    @Test
    fun anotherAppWithTheSameClassIsNotOurs() {
        assertFalse(listed("tech.dmconsulting.voxbulle.copy/$cls"))
        assertFalse(listed("tech.dmconsulting/.voxbulle.paste.AutoPasteService"))
    }

    @Test
    fun anotherServiceOfTheSamePackageIsNotOurs() {
        assertFalse(listed("$pkg/tech.dmconsulting.voxbulle.Other"))
        assertFalse(listed("$pkg/.Other"))
    }
}
