package tech.dmconsulting.voxbulle.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.BuildConfig
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.bubble.BubblePositionStore
import tech.dmconsulting.voxbulle.history.ClearHistoryDialog
import tech.dmconsulting.voxbulle.history.DictationHistory
import tech.dmconsulting.voxbulle.transcription.MistralKeyCheck
import tech.dmconsulting.voxbulle.ui.theme.Dimens
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import tech.dmconsulting.voxbulle.ui.components.DemoModeLabel
import tech.dmconsulting.voxbulle.ui.components.LocalDemoMode
import tech.dmconsulting.voxbulle.ui.components.NeonCard
import tech.dmconsulting.voxbulle.ui.components.NeonChip
import tech.dmconsulting.voxbulle.ui.components.NeonOutlinedButton
import tech.dmconsulting.voxbulle.ui.components.NeonSwitch
import tech.dmconsulting.voxbulle.ui.components.SectionLabel
import tech.dmconsulting.voxbulle.ui.theme.Neon

@get:StringRes
val DictationLanguage.label: Int
    get() = when (this) {
        DictationLanguage.Auto -> R.string.settings_language_auto
        DictationLanguage.French -> R.string.settings_language_french
        DictationLanguage.English -> R.string.settings_language_english
    }

/**
 * Screen 10 (neon mockup 06, DMC-213): offer card, bubble, auto-paste (DMC-141, through
 * screen 8), language, Mistral key (DMC-227), history, privacy, about. A tab: no back arrow.
 */
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    history: DictationHistory,
    bubble: BubbleToggle,
    autoPaste: AutoPasteToggle,
    mistralKey: MistralKeyStore,
    keyCheck: MistralKeyCheck,
    onOpenOffer: () -> Unit,
) {
    val settings by settingsStore.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var chooseLanguage by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var chooseHideMode by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val bubbleStore = remember { BubblePositionStore(context) }
    var hideToEdge by remember { mutableStateOf(bubbleStore.hideToEdge) }
    val current = settings ?: return

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.padding(start = Dimens.ScreenMargin, end = Dimens.ScreenMargin, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.screen_settings), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (LocalDemoMode.current) DemoModeLabel()
        }
        OfferCard(onOpenOffer, Modifier.padding(start = Dimens.ScreenMargin, end = Dimens.ScreenMargin, top = 20.dp))

        Section(R.string.settings_section_bubble)
        SwitchRow(
            title = R.string.settings_bubble,
            summary = stringResource(R.string.settings_bubble_summary),
            checked = bubble.shown == true,
            onCheckedChange = bubble.set,
        )
        SettingRow(
            title = R.string.settings_hide_mode,
            summary = stringResource(R.string.settings_hide_mode_summary),
            value = stringResource(if (hideToEdge) R.string.settings_hide_mode_edge else R.string.settings_hide_mode_notification),
            onClick = { chooseHideMode = true },
        )

        Section(R.string.settings_section_dictation)
        SwitchRow(
            title = R.string.settings_auto_paste,
            summary = stringResource(if (autoPaste.on == true) R.string.settings_auto_paste_summary_on else R.string.settings_auto_paste_summary),
            checked = autoPaste.on == true,
            onCheckedChange = autoPaste.set,
        )
        SettingRow(
            title = R.string.settings_language,
            summary = stringResource(current.language.label),
            onClick = { chooseLanguage = true },
        )

        Section(R.string.key_section)
        MistralKeySection(mistralKey, keyCheck)

        Section(R.string.settings_section_other)
        SettingRow(
            title = R.string.settings_clear_history,
            summary = stringResource(R.string.settings_clear_history_summary),
            onClick = { confirmClear = true },
        )
        SettingRow(title = R.string.settings_privacy, summary = stringResource(R.string.settings_privacy_summary))
        SettingRow(
            title = R.string.settings_about,
            summary = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
        )
        Spacer(Modifier.height(16.dp))
    }

    if (chooseLanguage) {
        LanguageDialog(
            selected = current.language,
            onSelect = { language ->
                chooseLanguage = false
                scope.launch { settingsStore.update { it.copy(language = language) } }
            },
            onDismiss = { chooseLanguage = false },
        )
    }

    if (chooseHideMode) {
        ChoiceDialog(
            title = R.string.settings_hide_mode,
            options = listOf(true to R.string.settings_hide_mode_edge, false to R.string.settings_hide_mode_notification),
            selected = hideToEdge,
            onSelect = { edge ->
                chooseHideMode = false
                bubbleStore.hideToEdge = edge
                hideToEdge = edge
            },
            onDismiss = { chooseHideMode = false },
        )
    }

    if (confirmClear) {
        ClearHistoryDialog(
            onConfirm = {
                confirmClear = false
                scope.launch { history.clear() }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** « Ton offre »: the free offer for now, and the way to the offer screen (slots only). */
@Composable
private fun OfferCard(onOpenOffer: () -> Unit, modifier: Modifier = Modifier) {
    NeonCard(modifier.fillMaxWidth(), important = true) {
        SectionLabel(stringResource(R.string.offer_your_offer))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.offer_free), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            NeonChip(stringResource(R.string.offer_current), color = Neon.Orange, textColor = Neon.OrangeText)
        }
        NeonOutlinedButton(
            stringResource(R.string.offer_upgrade),
            onClick = onOpenOffer,
            color = Neon.Magenta,
            textColor = Neon.Magenta,
            glow = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

@Composable
private fun Section(@StringRes label: Int) {
    SectionLabel(stringResource(label), Modifier.padding(start = Dimens.ScreenMargin, end = Dimens.ScreenMargin, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun RowText(@StringRes title: Int, summary: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val alpha = if (enabled) 1f else 0.38f
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

/** A 72 dp row, 16 dp padding, hairline below (mockup `.row`). */
private fun Modifier.settingRow() = fillMaxWidth()
    .heightIn(min = Dimens.SettingRowHeight)

@Composable
private fun RowFrame(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            modifier.padding(horizontal = Dimens.ListMargin, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing),
            content = content,
        )
        HorizontalDivider(color = Neon.Hairline)
    }
}

/** [value]: the current choice, in orange before the chevron (mockup « Liseré › »). */
@Composable
private fun SettingRow(@StringRes title: Int, summary: String, value: String? = null, onClick: (() -> Unit)? = null) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    RowFrame(Modifier.settingRow().then(clickable)) {
        RowText(title, summary, Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = Neon.OrangeText)
        }
        if (onClick != null) Text("›", style = MaterialTheme.typography.titleLarge, color = if (value != null) Neon.OrangeText else Neon.TextMuted)
    }
}

@Composable
private fun SwitchRow(
    @StringRes title: Int,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    // The whole row is the switch, for a 72 dp touch target and a single accessibility node.
    RowFrame(
        Modifier.settingRow()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        RowText(title, summary, Modifier.weight(1f), enabled)
        NeonSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LanguageDialog(
    selected: DictationLanguage,
    onSelect: (DictationLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceDialog(
        title = R.string.settings_language,
        options = DictationLanguage.entries.map { it to it.label },
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** One choice among [options] (value to label), radio buttons, cancel. */
@Composable
private fun <T> ChoiceDialog(
    @StringRes title: Int,
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (option, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.MinTouchTarget + 8.dp)
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
