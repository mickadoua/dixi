package tech.dmconsulting.dixi.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import tech.dmconsulting.dixi.paste.AutoPasteService

/** The « Collage automatique » switch of screen 10 (DMC-141). [on] is null while the settings load. */
class AutoPasteToggle(val on: Boolean?, val set: (Boolean) -> Unit)

/**
 * On only when the user accepted screen 8 **and** the service is on in Android's
 * accessibility settings, read again each time the screen comes back and when screen 8 is accepted. Turning it on
 * always goes through screen 8 ([onDisclose]); turning it off also turns the service off.
 */
@Composable
fun rememberAutoPasteToggle(settingsStore: SettingsStore, onDisclose: () -> Unit): AutoPasteToggle {
    val settings by settingsStore.settings.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serviceOn by remember { mutableStateOf(AutoPasteService.isEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { serviceOn = AutoPasteService.isEnabled(context) }
    // Screen 8 accepted with the service already on: no trip to Android's settings, no resume.
    val accepted = settings?.autoPasteEnabled
    LaunchedEffect(accepted) { if (accepted == true) serviceOn = AutoPasteService.isEnabled(context) }

    return AutoPasteToggle(settings?.let { it.autoPasteEnabled && serviceOn }) { on ->
        if (on) {
            onDisclose()
        } else {
            scope.launch { settingsStore.update { it.copy(autoPasteEnabled = false) } }
            AutoPasteService.turnOff()
            serviceOn = false
        }
    }
}
