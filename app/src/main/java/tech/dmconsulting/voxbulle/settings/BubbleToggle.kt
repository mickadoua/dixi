package tech.dmconsulting.voxbulle.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.bubble.BubbleSwitch

/** The « Afficher la bulle » switch, shared by the main screen and the settings. */
class BubbleToggle(val shown: Boolean?, val set: (Boolean) -> Unit)

/**
 * Shows or hides the bubble and saves the choice. Turning it on without the
 * « display over other apps » permission opens Android's page first; back from
 * it, the bubble shows only if the user allowed it. [BubbleToggle.shown] is null
 * while the settings load.
 */
@Composable
fun rememberBubbleToggle(settingsStore: SettingsStore): BubbleToggle {
    val settings by settingsStore.settings.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canShowBubble by remember { mutableStateOf(BubbleSwitch.canShow(context)) }

    fun setBubble(shown: Boolean) {
        scope.launch { settingsStore.update { it.copy(bubbleEnabled = shown) } }
        if (shown) BubbleSwitch.show(context) else BubbleSwitch.hide(context)
    }

    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canShowBubble = BubbleSwitch.canShow(context)
        if (canShowBubble) setBubble(true)
    }

    return BubbleToggle(settings?.let { it.bubbleEnabled && canShowBubble }) { shown ->
        when {
            !shown -> setBubble(false)
            BubbleSwitch.canShow(context) -> setBubble(true)
            else -> overlayPermission.launch(BubbleSwitch.overlayPermissionIntent(context))
        }
    }
}
