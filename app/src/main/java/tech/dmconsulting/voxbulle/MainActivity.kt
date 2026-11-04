package tech.dmconsulting.voxbulle

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.bubble.BubbleService
import tech.dmconsulting.voxbulle.bubble.BubbleSwitch
import tech.dmconsulting.voxbulle.history.Dictation
import tech.dmconsulting.voxbulle.onboarding.startScreen
import tech.dmconsulting.voxbulle.ui.navigation.Screen
import tech.dmconsulting.voxbulle.ui.navigation.VoxbulleNavHost
import tech.dmconsulting.voxbulle.ui.theme.VoxbulleTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as VoxbulleApp).container
        // Test hook kept from the PoC (scripts/emu-proof.sh): start the bubble
        // from the foreground activity. The real path is the onboarding, screen 4.
        if (intent.getBooleanExtra("showBubble", false) && Settings.canDrawOverlays(this)) {
            BubbleService.show(this)
        }
        // Bubble switched on in the settings (screen 10): bring it back after a restart.
        lifecycleScope.launch {
            if (container.settings.settings.first().bubbleEnabled && BubbleSwitch.canShow(this@MainActivity)) {
                BubbleSwitch.show(this@MainActivity)
            }
        }
        val demoMode = BuildConfig.DEBUG && container.usesFakeVoxtral
        val debugStart = debugStartScreen(container, firstCreate = savedInstanceState == null)
        setContent {
            VoxbulleTheme {
                // Read once per activity, then kept across rotations so the NavHost graph stays the same.
                var start by rememberSaveable { mutableStateOf(debugStart?.name) }
                LaunchedEffect(Unit) {
                    if (start == null) {
                        start = startScreen(container.settings.settings.first(), BubbleSwitch.canShow(this@MainActivity)).name
                    }
                }
                Screen.entries.firstOrNull { it.name == start }?.let { VoxbulleNavHost(container, demoMode, it) }
            }
        }
    }

    /**
     * Debug builds only, for emulator proofs: `--es screen History` opens a screen
     * directly, `--ei seedHistory N` adds N sample dictations (fictitious texts).
     */
    private fun debugStartScreen(container: AppContainer, firstCreate: Boolean): Screen? {
        if (!BuildConfig.DEBUG) return null
        val seed = intent.getIntExtra("seedHistory", 0)
        // Seed once per launch: a rotation recreates the activity with the same intent.
        if (seed > 0 && firstCreate) {
            lifecycleScope.launch {
                val now = Instant.now()
                (seed downTo 1).forEach { i ->
                    val text = SAMPLE_TEXTS[(i - 1) % SAMPLE_TEXTS.size]
                    container.history.add(Dictation(text, now.minus(Duration.ofHours(7L * (i - 1)))))
                }
            }
        }
        return Screen.entries.firstOrNull { it.name == intent.getStringExtra("screen") }
    }

    private companion object {
        val SAMPLE_TEXTS = listOf(
            "Bonjour Claire, je voulais confirmer le rendez-vous de jeudi à 14 h.",
            "Compte-rendu : le client valide le devis. Prochaine étape, l’envoi du contrat signé " +
                "et la planification de la réunion de lancement avec toute l’équipe la semaine prochaine.",
            "Pense à relancer le fournisseur pour la livraison de lundi.",
        )
    }
}
