package tech.dmconsulting.voxbulle.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InMemorySettingsStoreTest {
    @Test
    fun firstLaunchDefaults() {
        val settings = InMemorySettingsStore().settings.value
        assertFalse(settings.onboardingDone)
        assertFalse(settings.autoPasteEnabled)
        assertEquals(DictationLanguage.Auto, settings.language)
    }

    @Test
    fun updateAppliesTransform() = runBlocking {
        val store = InMemorySettingsStore()
        store.update { it.copy(language = DictationLanguage.English) }
        assertEquals(DictationLanguage.English, store.settings.value.language)
    }
}
