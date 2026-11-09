package tech.dmconsulting.voxbulle

import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.bubble.BubbleService
import tech.dmconsulting.voxbulle.bubble.BubbleSwitch
import tech.dmconsulting.voxbulle.history.Dictation
import tech.dmconsulting.voxbulle.settings.KeyStatus
import tech.dmconsulting.voxbulle.ui.navigation.Screen
import tech.dmconsulting.voxbulle.ui.navigation.VoxbulleNavHost
import tech.dmconsulting.voxbulle.ui.theme.VoxbulleTheme
import java.time.Duration
import java.time.Instant

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark only: light system bar icons whatever the system setting.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val container = (application as VoxbulleApp).container
        // Test hook kept from the PoC (scripts/emu-proof.sh): start the bubble
        // from the foreground activity. Onboarding (DMC-140) brings the real path.
        if (intent.getBooleanExtra("showBubble", false) && Settings.canDrawOverlays(this)) {
            BubbleService.show(this)
        }
        // Bubble switched on in the settings (screen 10): bring it back after a restart.
        lifecycleScope.launch {
            if (container.settings.settings.first().bubbleEnabled && BubbleSwitch.canShow(this@MainActivity)) {
                BubbleSwitch.show(this@MainActivity)
            }
        }
        val start = debugStartScreen(container, firstCreate = savedInstanceState == null)
            ?: Screen.History.takeIf { intent.getStringExtra(EXTRA_SCREEN) == it.name }
            ?: Screen.start
        val openSettings = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        setContent {
            // No saved key: the fakes answer and every screen says « Mode démo » (DMC-227).
            val keyStatus by container.mistralKey.status.collectAsState()
            VoxbulleTheme {
                VoxbulleNavHost(
                    container,
                    demoMode = keyStatus == KeyStatus.None,
                    start = start,
                    openOnTop = Screen.Settings.takeIf { openSettings },
                )
            }
        }
    }

    /**
     * Debug builds only, for emulator proofs: `--es screen Settings` opens any screen
     * directly (other builds: History only, from the « Texte copié » notification),
     * `--ei seedHistory N` adds N sample dictations (fictitious texts).
     */
    private fun debugStartScreen(container: AppContainer, firstCreate: Boolean): Screen? {
        if (!BuildConfig.DEBUG) return null
        val seed = intent.getIntExtra("seedHistory", 0)
        // Seed once per launch: a rotation recreates the activity with the same intent.
        if (seed > 0 && firstCreate) {
            lifecycleScope.launch {
                val now = Instant.now()
                val samples = resources.getStringArray(R.array.debug_sample_dictations)
                (seed downTo 1).forEach { i ->
                    val text = samples[(i - 1) % samples.size]
                    container.history.add(Dictation(text, now.minus(Duration.ofHours(7L * (i - 1)))))
                }
            }
        }
        return Screen.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SCREEN) }
    }

    companion object {
        /** Name of the [Screen] to open. */
        const val EXTRA_SCREEN = "screen"

        /** Opens screen 10 over the start screen: dictation notifications about the key. */
        const val EXTRA_OPEN_SETTINGS = "openSettings"
    }
}
