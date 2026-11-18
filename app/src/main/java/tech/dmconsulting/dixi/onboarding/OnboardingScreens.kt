package tech.dmconsulting.dixi.onboarding

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
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.bubble.BubbleEvents
import tech.dmconsulting.dixi.bubble.BubbleService
import tech.dmconsulting.dixi.bubble.BubbleSwitch
import tech.dmconsulting.dixi.bubble.Side
import tech.dmconsulting.dixi.permissions.NotificationAskStore
import tech.dmconsulting.dixi.permissions.needsNotificationsAfterMic
import tech.dmconsulting.dixi.recording.MicrophoneRecorder
import tech.dmconsulting.dixi.settings.KeyCheckResult
import tech.dmconsulting.dixi.settings.KeyStatus
import tech.dmconsulting.dixi.settings.MistralKeyField
import tech.dmconsulting.dixi.settings.MistralKeySteps
import tech.dmconsulting.dixi.settings.MistralKeyStore
import tech.dmconsulting.dixi.settings.MistralKeysLink
import tech.dmconsulting.dixi.settings.rememberKeyTest
import tech.dmconsulting.dixi.transcription.KeyCheck
import tech.dmconsulting.dixi.transcription.MistralKeyCheck
import tech.dmconsulting.dixi.ui.components.DixiTopBar
import tech.dmconsulting.dixi.ui.theme.Dimens
import tech.dmconsulting.dixi.ui.theme.Neon

/** Numbered onboarding steps after « Bienvenue »: micro, key, bubble, trial, ready. */
const val ONBOARDING_STEPS = 5

@Composable
private fun stepTitle(step: Int) = stringResource(R.string.welcome_step, step, ONBOARDING_STEPS)

/**
 * Onboarding frame (mockups): top bar with back arrow, content that scrolls on small
 * screens, actions pinned at the bottom. [topActions]: right of the top bar (« Passer »).
 */
@Composable
private fun OnboardingFrame(
    title: String,
    onBack: () -> Unit,
    topActions: @Composable RowScope.() -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(topBar = { DixiTopBar(title, onBack, topActions) }) { padding ->
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
private fun FilledAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Dimens.ButtonHeight)) { Text(text) }
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

@Composable
private fun ErrorLine(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun hasMicrophone(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Screen 2: why the microphone, then Android's own permission window, then the notifications
 * on Android 13+ (DMC-785): asked here, the first tap on the bubble in the trial dictates.
 * Refused: the message and « Ouvrir les réglages » (app info page); granted
 * there, the key step opens on return.
 */
@Composable
fun MicPermissionScreen(onGranted: () -> Unit, onLater: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var refused by rememberSaveable { mutableStateOf(false) }
    val askNotifications = remember { context.needsNotificationsAfterMic() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused notifications do not stop the onboarding: only « Texte copié » is lost.
        Log.i(MicrophoneRecorder.TAG, "notifications permission granted=$granted")
        onGranted()
    }
    fun next() {
        if (context.needsNotificationsAfterMic()) {
            NotificationAskStore(context).asked = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onGranted()
        }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(MicrophoneRecorder.TAG, "microphone permission granted=$granted")
        if (granted) next() else refused = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (refused && hasMicrophone(context)) {
            Log.i(MicrophoneRecorder.TAG, "microphone allowed in the app info page")
            refused = false
            next()
        }
    }

    OnboardingFrame(
        title = stepTitle(1),
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
                    if (hasMicrophone(context)) next() else request.launch(Manifest.permission.RECORD_AUDIO)
                })
            }
            TextAction(stringResource(R.string.mic_later), onLater)
        },
    ) {
        Badge(R.drawable.ic_mic, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        Heading(stringResource(R.string.mic_title))
        Body(stringResource(R.string.mic_body))
        if (askNotifications) Body(stringResource(R.string.mic_notifications_note), Neon.TextMuted)
        if (refused) ErrorLine(R.string.error_mic_denied)
    }
}

/**
 * The Mistral key step (mockups DMC-764 § 3, texts DMC-765): Léa's five steps, the link to
 * Mistral's page, the field and « Test key ». The key is required: no « Later ». Once Mistral
 * accepts it, the field goes, « Key valid ✓ » and « Continue » show.
 */
@Composable
fun MistralKeyScreen(keys: MistralKeyStore, keyCheck: MistralKeyCheck, onContinue: () -> Unit, onBack: () -> Unit) {
    val status by keys.status.collectAsState()
    val entries by keys.entries.collectAsState()
    val test = rememberKeyTest(keys, keyCheck)
    val name = stringResource(R.string.key_default_name, entries.size + 1)
    // Accepted now, or saved before a relaunch on this step.
    val valid = test.result == KeyCheck.Valid || (test.result == null && !test.testing && status == KeyStatus.Saved)

    OnboardingFrame(
        title = stepTitle(2),
        onBack = onBack,
        actions = {
            if (valid) {
                FilledAction(stringResource(R.string.key_continue), onContinue)
            } else {
                FilledAction(
                    stringResource(if (test.testing) R.string.key_check_running else R.string.key_test),
                    onClick = { test.test(name) },
                    enabled = test.input.isNotBlank() && !test.testing,
                )
            }
        },
    ) {
        Heading(stringResource(R.string.key_guide_title))
        Body(stringResource(R.string.key_guide_intro), Neon.TextMuted)
        if (valid) {
            KeyCheckResult(KeyCheck.Valid)
        } else {
            MistralKeySteps()
            MistralKeysLink()
            MistralKeyField(test.input, onValueChange = test::edit, onDone = { if (test.input.isNotBlank()) test.test(name) })
            test.result?.let { KeyCheckResult(it) }
            test.problem?.let { ErrorLine(it) }
        }
        Text(stringResource(R.string.key_storage_note), style = MaterialTheme.typography.bodyMedium, color = Neon.TextMuted)
    }
}

/**
 * Screen 4: what the bubble does, then Android's « Afficher par-dessus les
 * autres applications » page. On return, [Settings.canDrawOverlays] decides:
 * allowed → [onEnabled] (the real bubble shows, then the guided trial), refused → stay
 * here with « Réglage non activé ». « Pas maintenant » ends the onboarding without the trial.
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
        title = stepTitle(3),
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
        if (refused) ErrorLine(R.string.bubble_intro_refused)
    }
}

/**
 * Guided trial with the real bubble (mockups DMC-764 § 3, step 5). The screen stays behind
 * with an empty field; a hint next to the bubble asks for one gesture at a time: tap, speak
 * and tap again, then tuck it at the edge and bring it back. The dictated text arrives in
 * the field (from [BubbleEvents], no accessibility). « Passer » skips it; the bubble stays.
 */
@Composable
fun GuidedTrialScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // Shown even if the user had hidden it before.
    LaunchedEffect(Unit) { BubbleService.show(context) }
    val state by BubbleEvents.state.collectAsState()
    val docked by BubbleEvents.docked.collectAsState()
    val lastText by BubbleEvents.lastText.collectAsState()
    val error by BubbleEvents.errorMessage.collectAsState()
    val anchor by BubbleEvents.anchor.collectAsState()
    var step by rememberSaveable { mutableStateOf(GuideStep.Tap) }
    // Texts copied before this screen do not count.
    val firstTextId = rememberSaveable { lastText?.id ?: 0L }
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state) { step = step.next(GuideEvent.State(state)) }
    LaunchedEffect(docked) { step = step.next(GuideEvent.Docked(docked)) }
    LaunchedEffect(lastText) {
        val dictated = lastText ?: return@LaunchedEffect
        if (dictated.id <= firstTextId) return@LaunchedEffect
        text = dictated.text
        step = step.next(GuideEvent.Text)
    }
    LaunchedEffect(step) {
        Log.i(MicrophoneRecorder.TAG, "guided trial step $step")
        if (step == GuideStep.Done) {
            delay(DONE_PAUSE_MS)
            onDone()
        }
    }

    val view = LocalView.current
    var boxTop by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            val onScreen = IntArray(2).also { view.getLocationOnScreen(it) }
            boxTop = onScreen[1] + it.positionInWindow().y.toInt()
        },
    ) {
        OnboardingFrame(
            title = stepTitle(4),
            onBack = onBack,
            topActions = {
                TextButton(onClick = onDone, modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) {
                    Text(stringResource(R.string.trial_skip), color = Neon.TextMuted)
                }
            },
            actions = {},
        ) {
            Heading(stringResource(R.string.trial_title))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.trial_field_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (step >= GuideStep.Dock) {
                Text(
                    stringResource(R.string.trial_it_works),
                    style = MaterialTheme.typography.titleMedium,
                    color = Neon.Cyan,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        val hint = when (step) {
            GuideStep.Tap -> R.string.trial_hint_tap
            GuideStep.Speak -> R.string.trial_hint_speak
            GuideStep.Dock -> R.string.trial_hint_dock
            GuideStep.Undock, GuideStep.Done -> R.string.trial_hint_undock
        }
        GuideHint(
            hint = hint,
            error = error.takeIf { step == GuideStep.Tap },
            side = anchor?.side,
            y = anchor?.let { it.centerY - boxTop },
        )
    }
}

/** Pause on the last hint before « Dixi est prêt ». */
private const val DONE_PAUSE_MS = 800L

/**
 * The hint card that points at the real bubble: beside it, on the inner side, centered on
 * its height ([y], pixels in this box). Without the bubble on screen, under the field.
 */
@Composable
private fun BoxScope.GuideHint(@StringRes hint: Int, @StringRes error: Int?, side: Side?, y: Int?) {
    var height by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { BUBBLE_ROOM.roundToPx() }
    val right = side != Side.Left
    val top = y?.let { (it - height / 2).coerceAtLeast(0) }
    Column(
        Modifier
            .align(
                if (top == null) {
                    Alignment.Center
                } else if (right) {
                    Alignment.TopEnd
                } else {
                    Alignment.TopStart
                },
            )
            .offset {
                IntOffset(
                    if (top == null) {
                        0
                    } else if (right) {
                        -gap
                    } else {
                        gap
                    },
                    top ?: 0,
                )
            }
            .widthIn(max = 240.dp)
            .onSizeChanged { height = it.height }
            .background(Neon.Surface2, MaterialTheme.shapes.medium)
            .border(1.5.dp, Neon.Orange, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!right && top != null) Text("◂", color = Neon.Orange)
            Text(stringResource(hint), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
            if (right && top != null) Text("▸", color = Neon.Orange)
        }
        if (error != null) {
            Text(stringResource(error), style = MaterialTheme.typography.bodyMedium, color = Neon.Magenta)
            Text(stringResource(R.string.trial_retry), style = MaterialTheme.typography.bodyMedium, color = Neon.TextMuted)
        }
    }
}

/** Room between the hint and the screen edge: the bubble (48 dp) and its margins. */
private val BUBBLE_ROOM = 64.dp

/**
 * « Dixi est prêt » (mockups DMC-764 § 3, step 6): the bubble stays on the screen. Auto-paste,
 * if shown, leads to its disclosure first (screen 8).
 */
@Composable
fun BubbleEnabledScreen(onFinish: () -> Unit, onAutoPaste: (() -> Unit)?, onBack: () -> Unit) {
    OnboardingFrame(
        title = stepTitle(5),
        onBack = onBack,
        actions = {
            FilledAction(stringResource(R.string.bubble_enabled_finish), onFinish)
            if (onAutoPaste != null) {
                FilledTonalButton(
                    onClick = onAutoPaste,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeight),
                ) {
                    Text(stringResource(R.string.bubble_enabled_auto_paste))
                }
            }
        },
    ) {
        Badge(R.drawable.ic_check, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
        Heading(stringResource(R.string.bubble_enabled_title))
        Body(stringResource(R.string.bubble_enabled_body))
        Body(stringResource(R.string.bubble_enabled_note), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
