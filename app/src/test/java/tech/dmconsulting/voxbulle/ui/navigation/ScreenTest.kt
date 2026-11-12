package tech.dmconsulting.voxbulle.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {
    @Test
    fun screensFollowTheMockupNumbers() {
        assertEquals(listOf(1, 2, 3, 4, 5, 8, 9, 10, 12, 13), Screen.entries.map { it.number })
    }

    @Test
    fun startsOnWelcome() {
        assertEquals(Screen.Welcome, Screen.start)
    }

    @Test
    fun tabsAreHomeHistorySettings() {
        assertEquals(listOf(Screen.Home, Screen.History, Screen.Settings), Screen.tabs)
        assertEquals(Screen.tabs.toSet(), Screen.entries.filter { it.isTab }.toSet())
    }

    @Test
    fun onboardingEndsOnTheHomeTab() {
        assertEquals(Screen.Home, Screen.home)
    }

    @Test
    fun routesAreUnique() {
        assertEquals(Screen.entries.size, Screen.entries.map { it.route }.toSet().size)
    }
}
