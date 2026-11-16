package tech.dmconsulting.dixi.settings

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import tech.dmconsulting.dixi.bubble.BubbleService
import tech.dmconsulting.dixi.bubble.BubbleSwitch
import tech.dmconsulting.dixi.bubble.BubbleVisibility

/** The « Afficher la bulle » switch, shared by the main screen and the settings. */
class BubbleToggle(val shown: Boolean?, val set: (Boolean) -> Unit)

/**
 * What the switch shows: the bubble really on screen, saved setting or not (the
 * « Touchez, parlez » button shows it too, DMC-433). Null while the settings load.
 */
internal fun bubbleSwitchState(settingsLoaded: Boolean, bubbleVisible: Boolean, canShowBubble: Boolean): Boolean? = if (settingsLoaded) bubbleVisible && canShowBubble else null

/**
 * Shows or hides the bubble and saves the choice. Turning it on without the
 * « display over other apps » permission opens Android's page first; back from
 * it, the bubble shows only if the user allowed it. The permission is read again each
 * time the screen comes back. [BubbleToggle.shown] follows [BubbleVisibility].
 */
@Composable
fun rememberBubbleToggle(settingsStore: SettingsStore): BubbleToggle {
    val settings by settingsStore.settings.collectAsState(initial = null)
    val bubbleVisible by BubbleVisibility.visible.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canShowBubble by remember { mutableStateOf(BubbleSwitch.canShow(context)) }

    fun setBubble(shown: Boolean) {
        scope.launch { settingsStore.update { it.copy(bubbleEnabled = shown) } }
        // ACTION_SHOW: the switch also brings back a bubble hidden from its own menu.
        if (shown) BubbleService.show(context) else BubbleSwitch.hide(context)
    }

    // The permission can be given or taken back in Android's settings, outside the launcher (Victor, DMC-166).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canShowBubble = BubbleSwitch.canShow(context) }

    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canShowBubble = BubbleSwitch.canShow(context)
        if (canShowBubble) setBubble(true)
    }

    return BubbleToggle(bubbleSwitchState(settings != null, bubbleVisible, canShowBubble)) { shown ->
        when {
            !shown -> setBubble(false)
            BubbleSwitch.canShow(context) -> setBubble(true)
            else -> overlayPermission.launch(BubbleSwitch.overlayPermissionIntent(context))
        }
    }
}
