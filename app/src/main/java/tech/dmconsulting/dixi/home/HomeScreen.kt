package tech.dmconsulting.dixi.home

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.bubble.BubbleService
import tech.dmconsulting.dixi.history.Dictation
import tech.dmconsulting.dixi.history.DictationHistory
import tech.dmconsulting.dixi.history.copyToClipboard
import tech.dmconsulting.dixi.history.dictationDateLabel
import tech.dmconsulting.dixi.settings.AutoPasteToggle
import tech.dmconsulting.dixi.settings.BubbleToggle
import tech.dmconsulting.dixi.settings.KeyStatus
import tech.dmconsulting.dixi.settings.MistralKeyStore
import tech.dmconsulting.dixi.ui.components.NeonCard
import tech.dmconsulting.dixi.ui.components.NeonChip
import tech.dmconsulting.dixi.ui.components.NeonOutlinedButton
import tech.dmconsulting.dixi.ui.components.NeonSwitch
import tech.dmconsulting.dixi.ui.components.NeonToggleCard
import tech.dmconsulting.dixi.ui.components.SectionLabel
import tech.dmconsulting.dixi.ui.theme.Dimens
import tech.dmconsulting.dixi.ui.theme.Neon

/** What the bubble card says (mockups DMC-764 § 4). */
enum class BubbleCardState { Active, Inactive, PermissionMissing }

fun bubbleCardState(toggle: BubbleToggle): BubbleCardState = when {
    !toggle.permitted -> BubbleCardState.PermissionMissing
    toggle.shown == true -> BubbleCardState.Active
    else -> BubbleCardState.Inactive
}

/**
 * Home (mockups DMC-764 § 4): see at a glance if Dixi is ready to dictate, and find the last
 * dictation. Nothing else. Cards: bubble (switch), key, auto-paste while off; then the last
 * dictation, the history, and « Dicter ici » to dictate without the bubble.
 * [autoPaste]: null while auto-paste is hidden. [onOpenOffer]: null while the offer screen is hidden.
 */
@Composable
fun HomeScreen(
    bubble: BubbleToggle,
    history: DictationHistory,
    keys: MistralKeyStore,
    autoPaste: AutoPasteToggle?,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenOffer: (() -> Unit)?,
) {
    val context = LocalContext.current
    val entries by history.entries.collectAsState(initial = null)
    val keyStatus by keys.status.collectAsState()
    val keyEntries by keys.entries.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.ScreenMargin),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp),
                modifier = Modifier.weight(1f),
            )
            if (onOpenOffer != null) {
                NeonChip(
                    stringResource(R.string.offer_free),
                    color = Neon.Orange,
                    textColor = Neon.OrangeText,
                    onClick = onOpenOffer,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.screen_settings), tint = Neon.TextMuted)
            }
        }
        Spacer(Modifier.height(4.dp))
        BubbleCard(bubble)
        KeyCard(keyStatus, keyEntries.firstOrNull { it.active }?.name, onOpenSettings)
        if (autoPaste != null && autoPaste.on == false) AutoPasteCard(onTurnOn = { autoPaste.set(true) })
        Spacer(Modifier.height(8.dp))
        SectionLabel(stringResource(R.string.home_last_dictation))
        LastDictation(entries?.firstOrNull(), onCopy = {
            // From Android 13 the system confirms the copy itself.
            if (copyToClipboard(context, it.text)) Toast.makeText(context, R.string.text_copied, Toast.LENGTH_SHORT).show()
        })
        TextButton(onClick = onOpenHistory, modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) {
            Text(stringResource(R.string.home_all_history) + "  ›", color = Neon.OrangeText)
        }
        NeonOutlinedButton(
            stringResource(R.string.home_dictate_here),
            onClick = { BubbleService.dictate(context) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** State dot: the color says it, and so does the text next to it. */
@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
private fun BubbleCard(bubble: BubbleToggle) {
    val state = bubbleCardState(bubble)
    val active = state == BubbleCardState.Active
    NeonToggleCard(
        checked = active,
        onCheckedChange = bubble.set,
        enabled = bubble.shown != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(
                        when (state) {
                            BubbleCardState.Active -> Neon.Orange
                            BubbleCardState.Inactive -> Neon.Outline
                            BubbleCardState.PermissionMissing -> Neon.Magenta
                        },
                    )
                    Text(
                        stringResource(
                            when (state) {
                                BubbleCardState.Active -> R.string.home_bubble_on
                                BubbleCardState.Inactive -> R.string.home_bubble_off
                                BubbleCardState.PermissionMissing -> R.string.home_bubble_permission
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                Text(
                    stringResource(
                        when (state) {
                            BubbleCardState.Active -> R.string.home_bubble_on_body
                            BubbleCardState.Inactive -> R.string.home_bubble_off_body
                            BubbleCardState.PermissionMissing -> R.string.home_bubble_permission_body
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Neon.TextMuted,
                )
            }
            NeonSwitch(checked = active, onCheckedChange = null, enabled = bubble.shown != null)
        }
    }
}

/**
 * The key in use, by its name ([activeName], DMC-766): valid, refused at the last dictation or
 * test, or missing. A tap opens its settings.
 */
@Composable
private fun KeyCard(status: KeyStatus, activeName: String?, onOpenSettings: () -> Unit) {
    NeonCard(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenSettings),
        border = BorderStroke(1.dp, if (status == KeyStatus.Saved) Neon.Hairline else Neon.Magenta),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    when (status) {
                        KeyStatus.Saved -> R.string.home_key_active
                        KeyStatus.Rejected -> R.string.home_key_refused
                        KeyStatus.None -> R.string.home_key_none
                    },
                ) + activeName?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (status == KeyStatus.Saved) Neon.Text else Neon.Magenta,
                modifier = Modifier.weight(1f),
            )
            if (status == KeyStatus.Saved) Text("✓", color = Neon.Cyan, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(
                when (status) {
                    KeyStatus.Saved -> R.string.home_key_change
                    KeyStatus.Rejected -> R.string.home_key_check
                    KeyStatus.None -> R.string.home_key_add
                },
            ) + "  ›",
            style = MaterialTheme.typography.bodyMedium,
            color = Neon.OrangeText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Shown only while auto-paste is off: « Activer » goes through its disclosure. */
@Composable
private fun AutoPasteCard(onTurnOn: () -> Unit) {
    NeonCard(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onTurnOn),
        border = BorderStroke(1.dp, Neon.Hairline),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_auto_paste_off),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.home_auto_paste_turn_on) + "  ›", style = MaterialTheme.typography.bodyMedium, color = Neon.OrangeText)
        }
    }
}

@Composable
private fun LastDictation(dictation: Dictation?, onCopy: (Dictation) -> Unit) {
    NeonCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Neon.Hairline)) {
        if (dictation == null) {
            Text(stringResource(R.string.home_last_dictation_empty), style = MaterialTheme.typography.bodyLarge, color = Neon.TextMuted)
            return@NeonCard
        }
        Text(dictation.text, style = MaterialTheme.typography.bodyLarge, maxLines = 4)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                dictationDateLabel(dictation.createdAt),
                style = MaterialTheme.typography.bodyMedium,
                color = Neon.TextMuted,
                modifier = Modifier.weight(1f),
            )
            NeonChip(
                stringResource(R.string.history_copy_action),
                color = Neon.Cyan,
                height = 32.dp,
                onClick = { onCopy(dictation) },
            )
        }
    }
}
