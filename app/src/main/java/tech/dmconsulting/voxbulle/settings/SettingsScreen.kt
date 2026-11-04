package tech.dmconsulting.voxbulle.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import tech.dmconsulting.voxbulle.bubble.BubbleSwitch
import tech.dmconsulting.voxbulle.history.ClearHistoryDialog
import tech.dmconsulting.voxbulle.history.DictationHistory
import tech.dmconsulting.voxbulle.ui.components.VoxbulleTopBar
import tech.dmconsulting.voxbulle.ui.theme.Dimens

@get:StringRes
val DictationLanguage.label: Int
    get() = when (this) {
        DictationLanguage.Auto -> R.string.settings_language_auto
        DictationLanguage.French -> R.string.settings_language_french
        DictationLanguage.English -> R.string.settings_language_english
    }

/** Screen 10: bubble, auto-paste (wired by DMC-141), language, history, privacy, about. */
@Composable
fun SettingsScreen(settingsStore: SettingsStore, history: DictationHistory, onBack: () -> Unit) {
    val settings by settingsStore.settings.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canShowBubble by remember { mutableStateOf(BubbleSwitch.canShow(context)) }
    var chooseLanguage by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    fun setBubble(shown: Boolean) {
        scope.launch { settingsStore.update { it.copy(bubbleEnabled = shown) } }
        if (shown) BubbleSwitch.show(context) else BubbleSwitch.hide(context)
    }

    // Back from Android's overlay page: show the bubble only if the user allowed it.
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canShowBubble = BubbleSwitch.canShow(context)
        if (canShowBubble) setBubble(true)
    }

    Scaffold(topBar = { VoxbulleTopBar(stringResource(R.string.screen_settings), onBack) }) { padding ->
        val current = settings ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ListMargin, vertical = 8.dp),
        ) {
            SwitchRow(
                title = R.string.settings_bubble,
                summary = stringResource(R.string.settings_bubble_summary),
                checked = current.bubbleEnabled && canShowBubble,
                onCheckedChange = { shown ->
                    when {
                        !shown -> setBubble(false)
                        BubbleSwitch.canShow(context) -> setBubble(true)
                        else -> overlayPermission.launch(BubbleSwitch.overlayPermissionIntent(context))
                    }
                },
            )
            // Off and locked until the auto-paste ticket (DMC-141) wires the accessibility service.
            SwitchRow(
                title = R.string.settings_auto_paste,
                summary = stringResource(R.string.settings_auto_paste_summary),
                checked = false,
                enabled = false,
                onCheckedChange = {},
            )
            SettingRow(
                title = R.string.settings_language,
                summary = stringResource(current.language.label),
                onClick = { chooseLanguage = true },
            )
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

@Composable
private fun RowText(@StringRes title: Int, summary: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val alpha = if (enabled) 1f else 0.38f
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

@Composable
private fun SettingRow(@StringRes title: Int, summary: String, onClick: (() -> Unit)? = null) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.SettingRowHeight)
            .then(clickable)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, summary)
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
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.SettingRowHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing),
    ) {
        RowText(title, summary, Modifier.weight(1f), enabled)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LanguageDialog(
    selected: DictationLanguage,
    onSelect: (DictationLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(Modifier.selectableGroup()) {
                DictationLanguage.entries.forEach { language ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.MinTouchTarget + 8.dp)
                            .selectable(
                                selected = language == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(language) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing),
                    ) {
                        RadioButton(selected = language == selected, onClick = null)
                        Text(stringResource(language.label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
