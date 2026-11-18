package tech.dmconsulting.dixi.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.transcription.KeyCheck
import tech.dmconsulting.dixi.transcription.MistralKeyCheck
import tech.dmconsulting.dixi.ui.theme.Dimens
import java.io.IOException

/** What a saved key's row says under its name. */
@get:StringRes
val KeyEntry.stateLabel: Int
    get() = when {
        rejected -> R.string.key_state_refused
        active -> R.string.key_state_active
        else -> R.string.key_state_idle
    }

/** A line under the section, with the key name when it needs one. */
private data class Line(@StringRes val text: Int, val arg: String? = null)

/**
 * Screen 10, body of the « Clé API Mistral » section (DMC-227, several keys since DMC-766):
 * the saved keys by name, the active one picked like a radio button, a delete button on each
 * row, « Test the active key », and « Add a key » (tested before it is saved, then active).
 * Only names and states are shown, never a key or its first characters.
 */
@Composable
fun MistralKeySection(keys: MistralKeyStore, keyCheck: MistralKeyCheck) {
    val status by keys.status.collectAsState()
    val entries by keys.entries.collectAsState()
    val scope = rememberCoroutineScope()
    val adding = rememberKeyTest(keys, keyCheck)
    // remember, not rememberSaveable: the pasted key must not go into the saved instance state.
    var addOpen by remember { mutableStateOf(entries.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var line by remember { mutableStateOf<Line?>(null) }
    var check by remember { mutableStateOf<KeyCheck?>(null) }
    var testing by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<KeyEntry?>(null) }
    val defaultName = stringResource(R.string.key_default_name, entries.size + 1)

    // A key accepted by « Test key » in the form is saved and active: close the form.
    LaunchedEffect(adding.result) {
        if (adding.result == KeyCheck.Valid) {
            addOpen = false
            name = ""
            check = null
            line = Line(R.string.key_added)
        }
    }

    /** Runs a change of the saved keys. A disk error shows a line instead of crashing the app. */
    suspend fun saved(change: suspend () -> Unit): Boolean = try {
        change()
        true
    } catch (e: IOException) {
        check = null
        line = Line(R.string.key_change_failed)
        false
    }

    fun testActive() {
        testing = true
        check = null
        line = Line(R.string.key_check_running)
        scope.launch {
            val key = keys.key()
            val result = if (key == null) null else keyCheck.check(key)
            val ok = saved {
                when {
                    result == KeyCheck.Valid -> keys.markAccepted()
                    result?.rejectsKey == true -> keys.markRejected()
                }
            }
            if (ok) {
                check = result
                line = if (result == null) Line(R.string.key_status_none) else null
            }
            testing = false
        }
    }

    fun testNew() {
        if (adding.input.isNotBlank() && !adding.testing) adding.test(MistralKey.name(name, defaultName))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ListMargin, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The « Clé API Mistral » heading is the screen's section label (neon charter).
        if (status == KeyStatus.None) {
            Text(
                stringResource(R.string.key_status_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        entries.forEach { entry ->
            KeyRow(
                entry,
                onActivate = {
                    scope.launch {
                        if (saved { keys.activate(entry.id) }) {
                            check = null
                            line = Line(R.string.key_activated, entry.name)
                        }
                    }
                },
                onDelete = { toDelete = entry },
            )
        }
        if (status != KeyStatus.None) {
            OutlinedButton(
                onClick = ::testActive,
                enabled = !testing,
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
            ) { Text(stringResource(R.string.key_test_active)) }
        }
        check?.let { KeyCheckResult(it) }
        line?.let { (text, arg) ->
            Text(
                if (arg == null) stringResource(text) else stringResource(text, arg),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (addOpen) {
            Text(stringResource(R.string.key_add), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MistralKey.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.key_name_field)) },
                placeholder = { Text(defaultName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MistralKeysLink()
            MistralKeyField(adding.input, onValueChange = adding::edit, onDone = ::testNew)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = ::testNew,
                    enabled = adding.input.isNotBlank() && !adding.testing,
                    modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
                ) { Text(stringResource(if (adding.testing) R.string.key_check_running else R.string.key_test)) }
                if (entries.isNotEmpty()) {
                    // Not during the test: a key Mistral accepts is saved and made active even if the form is closed.
                    TextButton(
                        onClick = {
                            addOpen = false
                            adding.edit("")
                        },
                        enabled = !adding.testing,
                        modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            adding.result?.takeIf { it != KeyCheck.Valid }?.let { KeyCheckResult(it) }
            adding.problem?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        } else {
            TextButton(
                onClick = {
                    adding.edit("")
                    line = null
                    addOpen = true
                },
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
            ) { Text(stringResource(R.string.key_add) + "  +") }
        }
        Text(
            stringResource(R.string.key_storage_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    toDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.key_delete_title, entry.name)) },
            text = { Text(stringResource(if (entries.size <= 1) R.string.key_delete_last_body else R.string.key_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        if (saved { keys.delete(entry.id) }) {
                            check = null
                            line = Line(R.string.key_deleted, entry.name)
                            if (keys.entries.value.isEmpty()) addOpen = true
                        }
                    }
                }) { Text(stringResource(R.string.key_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** One saved key: a tap on the row makes it active (radio button), the bin deletes it after a confirmation. */
@Composable
private fun KeyRow(entry: KeyEntry, onActivate: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .selectable(selected = entry.active, role = Role.RadioButton, onClick = onActivate),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = entry.active, onClick = null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(entry.stateLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.key_delete_one, entry.name),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
