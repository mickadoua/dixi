package tech.dmconsulting.voxbulle.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.VoxbulleApp
import tech.dmconsulting.voxbulle.bubble.BubbleSwitch
import tech.dmconsulting.voxbulle.history.copyToClipboard
import tech.dmconsulting.voxbulle.recording.MicrophoneRecorder
import tech.dmconsulting.voxbulle.recording.RecordingLimits
import tech.dmconsulting.voxbulle.ui.components.VoxbulleTopBar
import tech.dmconsulting.voxbulle.ui.theme.Dimens
import java.util.Locale

/**
 * Screen 2 to 5 frame (mockups): top bar with back arrow, content that
 * scrolls on small screens, actions pinned at the bottom.
 */
@Composable
private fun OnboardingFrame(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = { VoxbulleTopBar(title, onBack) },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(start = Dimens.ScreenMargin, end = Dimens.ScreenMargin, top = 8.dp, bottom = 16.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing),
                content = content,
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

@Composable
private fun FilledAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = Dimens.ButtonHeight)) { Text(text) }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeight)) { Text(text) }
}

/** Icon chip of screens 2 and 5: 64 dp, radius 20 dp. */
@Composable
private fun Badge(@DrawableRes icon: Int, container: Color, content: Color) {
    Box(
        Modifier.size(Dimens.IconChip).background(container, MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Body(text: String, color: Color = Color.Unspecified) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
}

private fun hasMicrophone(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Screen 2: why the microphone, then Android's own permission window.
 * Refused: the message and « Ouvrir les réglages » (app info page); granted
 * there, the trial opens on return.
 */
@Composable
fun MicPermissionScreen(onGranted: () -> Unit, onLater: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var refused by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(MicrophoneRecorder.TAG, "microphone permission granted=$granted")
        if (granted) onGranted() else refused = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (refused && hasMicrophone(context)) {
            Log.i(MicrophoneRecorder.TAG, "microphone allowed in the app info page")
            onGranted()
        }
    }

    OnboardingFrame(
        title = stringResource(R.string.onboarding_step, 1),
        onBack = onBack,
        actions = {
            if (refused) {
                FilledAction(stringResource(R.string.mic_open_settings), onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                })
            } else {
                FilledAction(stringResource(R.string.mic_allow), onClick = {
                    if (hasMicrophone(context)) onGranted() else request.launch(Manifest.permission.RECORD_AUDIO)
                })
            }
            TextAction(stringResource(R.string.mic_later), onLater)
        },
    ) {
        Badge(R.drawable.ic_mic, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        Heading(stringResource(R.string.mic_title))
        Body(stringResource(R.string.mic_body))
        if (refused) {
            Text(
                stringResource(R.string.error_mic_denied),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Holds the trial across rotations; closed when screen 3 leaves the back stack. */
class TrialViewModel(app: VoxbulleApp) : ViewModel() {
    val model = TrialModel(RecorderTrialAdapter(app.recorder), app.container.transcription::run, viewModelScope)

    override fun onCleared() = model.close()
}

/** « 1:05 » */
private fun formatCounter(millis: Long): String {
    val seconds = millis / 1000
    return String.format(Locale.FRANCE, "%d:%02d", seconds / 60, seconds % 60)
}

/**
 * Screen 3: a real dictation inside the app, before any other permission.
 * Big microphone button, counter, 5 min limit, then the « brut » and
 * « Texte Voxbulle » cards with « Copier » and « Continuer ».
 */
@Composable
fun TrialScreen(onContinue: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoxbulleApp
    val model = viewModel { TrialViewModel(app) }.model
    val state by model.state.collectAsState()
    LaunchedEffect(state) { Log.i(MicrophoneRecorder.TAG, "trial state ${state::class.simpleName}") }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copiedMessage = stringResource(R.string.text_copied)

    OnboardingFrame(
        title = stringResource(R.string.trial_top),
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            when (val current = state) {
                is TrialState.Result -> Row(horizontalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing)) {
                    FilledTonalButton(
                        onClick = {
                            if (copyToClipboard(context, current.transcript.text)) {
                                scope.launch { snackbar.showSnackbar(copiedMessage) }
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = Dimens.ButtonHeight),
                        // The mockup's « Copier »: primaryContainer, not the default secondaryContainer.
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.trial_copy))
                    }
                    FilledAction(stringResource(R.string.trial_continue), onContinue, Modifier.weight(1f))
                }

                // Without a result there is nothing to copy. Not stuck for all that
                // (no network, microphone taken back): the user may move on.
                is TrialState.Error -> TextAction(stringResource(R.string.trial_skip), onContinue)

                else -> Unit
            }
        },
    ) {
        Heading(stringResource(R.string.trial_title))
        when (val current = state) {
            is TrialState.Result -> TrialResult(current)

            TrialState.Processing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing),
                modifier = Modifier.padding(top = 24.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 4.dp)
                Body(stringResource(R.string.trial_processing))
            }

            else -> TrialRecorderPanel(state, model)
        }
    }
}

@Composable
private fun TrialRecorderPanel(state: TrialState, model: TrialModel) {
    val recording = state == TrialState.Recording
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) {
        elapsed = 0
        while (recording) {
            elapsed = model.elapsedMs()
            delay(250)
        }
    }

    Body(
        stringResource(if (recording) R.string.trial_hint_stop else R.string.trial_hint),
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing),
    ) {
        val colors = MaterialTheme.colorScheme
        // Listening also shows by the icon (stop square), not by the color alone.
        Surface(
            onClick = model::onMicTap,
            shape = CircleShape,
            color = if (recording) colors.tertiary else colors.primary,
            contentColor = if (recording) colors.onTertiary else colors.onPrimary,
            shadowElevation = 3.dp,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic),
                    contentDescription = stringResource(if (recording) R.string.trial_stop else R.string.trial_start),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Text(formatCounter(elapsed), style = MaterialTheme.typography.titleMedium)
        if (recording && elapsed >= RecordingLimits.current().warnAtMs) {
            Text(
                stringResource(R.string.notif_recording_warning),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.tertiary,
            )
        }
        if (state is TrialState.Error) {
            Text(
                stringResource(state.message),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun TrialResult(result: TrialState.Result) {
    val transcript = result.transcript
    TextCard(
        label = stringResource(R.string.trial_raw),
        text = transcript.raw,
        container = MaterialTheme.colorScheme.surfaceContainer,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        italic = true,
    )
    transcript.cleaned?.let {
        TextCard(
            label = stringResource(R.string.trial_clean),
            text = it,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    // Cleanup failed: the raw words are kept, and copied.
    transcript.cleanupError?.let {
        Body(stringResource(it.message), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TextCard(label: String, text: String, container: Color, content: Color, italic: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Dimens.ListMargin), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}

/**
 * Screen 4: what the bubble does, then Android's « Afficher par-dessus les
 * autres applications » page. On return, [Settings.canDrawOverlays] decides:
 * allowed → [onEnabled], refused → stay here with « Réglage non activé ».
 */
@Composable
fun BubbleIntroScreen(onEnabled: () -> Unit, onNotNow: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // Sent to the system page and not back with the permission yet.
    var asked by rememberSaveable { mutableStateOf(false) }
    var refused by rememberSaveable { mutableStateOf(false) }

    fun enable() {
        Log.i(MicrophoneRecorder.TAG, "overlay permission allowed")
        asked = false
        refused = false
        onEnabled()
    }

    val overlayPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (BubbleSwitch.canShow(context)) {
            enable()
        } else {
            Log.i(MicrophoneRecorder.TAG, "overlay permission refused")
            refused = true
        }
    }
    // Android 8 can answer canDrawOverlays late, after the result: check again on resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (asked && BubbleSwitch.canShow(context)) enable()
    }

    OnboardingFrame(
        title = stringResource(R.string.onboarding_step, 2),
        onBack = onBack,
        actions = {
            FilledAction(stringResource(R.string.bubble_intro_open), onClick = {
                if (BubbleSwitch.canShow(context)) {
                    enable()
                } else {
                    asked = true
                    refused = false
                    overlayPage.launch(BubbleSwitch.overlayPermissionIntent(context))
                }
            })
            TextAction(stringResource(R.string.bubble_intro_not_now), onNotNow)
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                .padding(Dimens.ListMargin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.ListMargin),
        ) {
            BubblePreview()
            Text(
                stringResource(R.string.bubble_intro_preview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Heading(stringResource(R.string.bubble_intro_title))
        Column {
            listOf(R.string.bubble_intro_step_tap, R.string.bubble_intro_step_stop, R.string.bubble_intro_step_copied)
                .forEach { Body("•  " + stringResource(it)) }
        }
        Body(stringResource(R.string.bubble_intro_system), MaterialTheme.colorScheme.onSurfaceVariant)
        if (refused) {
            Text(
                stringResource(R.string.bubble_intro_refused),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Screen 5: the bubble is shown; the real test happens in another app. Auto-paste leads to screen 8. */
@Composable
fun BubbleEnabledScreen(onFinish: () -> Unit, onAutoPaste: () -> Unit, onBack: () -> Unit) {
    OnboardingFrame(
        title = stringResource(R.string.onboarding_step, 3),
        onBack = onBack,
        actions = {
            FilledAction(stringResource(R.string.bubble_enabled_finish), onFinish)
            FilledTonalButton(
                onClick = onAutoPaste,
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeight),
            ) {
                Text(stringResource(R.string.bubble_enabled_auto_paste))
            }
        },
    ) {
        Badge(R.drawable.ic_check, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
        Heading(stringResource(R.string.bubble_enabled_title))
        Body(stringResource(R.string.bubble_enabled_body))
        Body(stringResource(R.string.bubble_enabled_note), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
