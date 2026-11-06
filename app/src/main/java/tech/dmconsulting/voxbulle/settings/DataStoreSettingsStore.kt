package tech.dmconsulting.voxbulle.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/** Settings kept on the phone (Preferences DataStore). A missing key reads as its [AppSettings] default. */
class DataStoreSettingsStore(private val store: DataStore<Preferences>) : SettingsStore {

    override val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[ONBOARDING_DONE] = next.onboardingDone
            prefs[BUBBLE_ENABLED] = next.bubbleEnabled
            prefs[AUTO_PASTE_ENABLED] = next.autoPasteEnabled
            prefs[LANGUAGE] = next.language.name
            prefs[HISTORY_ENABLED] = next.historyEnabled
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            onboardingDone = this[ONBOARDING_DONE] ?: defaults.onboardingDone,
            bubbleEnabled = this[BUBBLE_ENABLED] ?: defaults.bubbleEnabled,
            autoPasteEnabled = this[AUTO_PASTE_ENABLED] ?: defaults.autoPasteEnabled,
            language = DictationLanguage.entries.firstOrNull { it.name == this[LANGUAGE] } ?: defaults.language,
            historyEnabled = this[HISTORY_ENABLED] ?: defaults.historyEnabled,
        )
    }

    companion object {
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
        private val AUTO_PASTE_ENABLED = booleanPreferencesKey("auto_paste_enabled")
        private val LANGUAGE = stringPreferencesKey("language")
        private val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")

        /** One instance per [file] and per process. The file name must end in `.preferences_pb`. */
        fun create(
            file: File,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) = DataStoreSettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}
