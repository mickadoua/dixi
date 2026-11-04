package tech.dmconsulting.voxbulle.settings

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "settings.preferences_pb")

    private fun <T> withStore(file: File, block: suspend (DataStoreSettingsStore) -> T): T = runBlocking {
        val job = Job()
        try {
            block(DataStoreSettingsStore.create(file, CoroutineScope(Dispatchers.IO + job)))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun firstLaunchReadsDefaults() {
        assertEquals(AppSettings(), withStore(file()) { it.settings.first() })
    }

    @Test
    fun settingsSurviveARestart() {
        val file = file()
        val changed = AppSettings(
            onboardingDone = true,
            onboardingStep = OnboardingStep.BubbleIntro,
            bubbleEnabled = true,
            autoPasteEnabled = true,
            language = DictationLanguage.English,
            historyEnabled = false,
        )
        withStore(file) { store -> store.update { changed } }

        assertEquals(changed, withStore(file) { it.settings.first() })
    }

    @Test
    fun updateChangesOnlyWhatItTouches() {
        val settings = withStore(file()) { store ->
            store.update { it.copy(language = DictationLanguage.French) }
            store.update { it.copy(bubbleEnabled = true) }
            store.settings.first()
        }
        assertEquals(AppSettings(bubbleEnabled = true, language = DictationLanguage.French), settings)
    }
}
