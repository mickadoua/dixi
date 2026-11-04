package tech.dmconsulting.voxbulle.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {
    @Test
    fun screensFollowTheMockupNumbers() {
        assertEquals(listOf(1, 2, 3, 4, 5, 8, 9, 10), Screen.entries.map { it.number })
    }

    @Test
    fun startsOnWelcome() {
        assertEquals(Screen.Welcome, Screen.start)
    }

    @Test
    fun homeIsTheHistory() {
        assertEquals(Screen.History, Screen.home)
    }

    @Test
    fun routesAreUnique() {
        assertEquals(Screen.entries.size, Screen.entries.map { it.route }.toSet().size)
    }
}
