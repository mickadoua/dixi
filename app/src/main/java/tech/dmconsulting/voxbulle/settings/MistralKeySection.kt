package tech.dmconsulting.voxbulle.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.transcription.KeyCheck
import tech.dmconsulting.voxbulle.transcription.MistralKeyCheck
import tech.dmconsulting.voxbulle.ui.theme.Dimens
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

@get:StringRes
val KeyStatus.label: Int
    get() = when (this) {
        KeyStatus.None -> R.string.key_status_none
        KeyStatus.Saved -> R.string.key_status_saved
        KeyStatus.Rejected -> R.string.key_status_rejected
    }

/**
 * Screen 10, body of the « Clé API Mistral » section (DMC-227): paste, save, test, delete. Only the
 * [KeyStatus] is shown, never the key or its first characters.
 */
@Composable
fun MistralKeySection(keys: MistralKeyStore, keyCheck: MistralKeyCheck) {
    val status by keys.status.collectAsState()
    val scope = rememberCoroutineScope()
    // remember, not rememberSaveable: the pasted key must not go into the saved instance state.
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Int?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val key = MistralKey.normalize(input)
        if (key == null) {
            message = R.string.key_format_invalid
            return
        }
        scope.launch {
            message = try {
                keys.save(key)
                input = ""
                R.string.key_saved
            } catch (e: IOException) {
                R.string.key_save_failed
            } catch (e: GeneralSecurityException) {
                R.string.key_save_failed
            } catch (e: ProviderException) {
                R.string.key_save_failed
            }
        }
    }

    fun test() {
        testing = true
        message = R.string.key_check_running
        scope.launch {
            val key = keys.key()
            val result = if (key == null) null else keyCheck.check(key)
            when (result) {
                KeyCheck.Valid -> keys.markAccepted()
                KeyCheck.Rejected -> keys.markRejected()
                else -> Unit
            }
            message = result?.message ?: R.string.key_status_none
            testing = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ListMargin, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The « Clé API Mistral » heading is the screen's section label (neon charter).
        Text(
            stringResource(status.label),
            style = MaterialTheme.typography.bodyLarge,
            color = if (status == KeyStatus.Rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(R.string.key_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                message = null
            },
            label = { Text(stringResource(R.string.key_field)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            // Password type: no suggestions, no learning by the keyboard.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = ::save,
                enabled = input.isNotBlank(),
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
            ) { Text(stringResource(R.string.key_save)) }
            OutlinedButton(
                onClick = ::test,
                enabled = status != KeyStatus.None && !testing,
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
            ) { Text(stringResource(R.string.key_test)) }
        }
        TextButton(
            onClick = { confirmDelete = true },
            enabled = status != KeyStatus.None,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
        ) { Text(stringResource(R.string.key_delete)) }
        message?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.key_delete_title)) },
            text = { Text(stringResource(R.string.key_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        keys.delete()
                        message = R.string.key_deleted
                    }
                }) { Text(stringResource(R.string.key_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
