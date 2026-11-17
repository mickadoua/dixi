package tech.dmconsulting.dixi.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.recording.MicrophoneRecorder
import tech.dmconsulting.dixi.transcription.KeyCheck
import tech.dmconsulting.dixi.transcription.MistralKeyCheck
import tech.dmconsulting.dixi.ui.theme.Dimens
import tech.dmconsulting.dixi.ui.theme.Neon
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/** Mistral's page where the user creates a key (texts DMC-765). */
const val MISTRAL_KEYS_URL = "https://console.mistral.ai/api-keys"

fun openMistralKeys(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MISTRAL_KEYS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w(MicrophoneRecorder.TAG, "no browser for the Mistral keys page")
    }
}

/** « Open the Mistral API Keys page »: Mistral's console in the browser. */
@Composable
fun MistralKeysLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(onClick = { openMistralKeys(context) }, modifier = modifier.heightIn(min = Dimens.MinTouchTarget)) {
        Text(stringResource(R.string.key_guide_link) + "  ›", color = Neon.OrangeText)
    }
}

/** Léa's five steps to create a key, with the plan and phone note under step 1. */
@Composable
fun MistralKeySteps() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.key_guide_step1), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.key_guide_step1_note),
            style = MaterialTheme.typography.bodyMedium,
            color = Neon.TextMuted,
            modifier = Modifier.padding(start = 16.dp),
        )
        listOf(R.string.key_guide_step2, R.string.key_guide_step3, R.string.key_guide_step4, R.string.key_guide_step5)
            .forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
    }
}

/**
 * The answer to « Test key »: « Key valid » and its line, « Key refused » and its cause, or the
 * cause alone (no network, Mistral not answering: not a refusal).
 */
@Composable
fun KeyCheckResult(check: KeyCheck, modifier: Modifier = Modifier) {
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            check == KeyCheck.Valid -> Text(
                stringResource(R.string.key_check_valid) + " ✓",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Neon.Cyan,
            )

            check.refused -> Text(
                stringResource(R.string.key_check_refused),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Neon.Magenta,
            )
        }
        Text(
            stringResource(check.message),
            style = MaterialTheme.typography.bodyMedium,
            color = if (check == KeyCheck.Valid) Neon.Text else Neon.TextMuted,
        )
    }
}

/**
 * The pasted key and its test, for the onboarding step. The key is saved only once Mistral
 * accepts it. remember, not rememberSaveable: the key must not go into the saved instance state.
 */
@Stable
class KeyTest(private val keys: MistralKeyStore, private val keyCheck: MistralKeyCheck, private val scope: CoroutineScope) {
    var input by mutableStateOf("")
    var testing by mutableStateOf(false)
        private set
    var result by mutableStateOf<KeyCheck?>(null)
        private set

    /** A message that is not a [KeyCheck]: wrong format, or the save failed. */
    var problem by mutableStateOf<Int?>(null)
        private set

    fun edit(text: String) {
        input = text
        result = null
        problem = null
    }

    fun test() {
        val key = MistralKey.normalize(input)
        if (key == null) {
            problem = R.string.key_format_invalid
            return
        }
        testing = true
        problem = null
        result = null
        scope.launch {
            val check = keyCheck.check(key)
            if (check == KeyCheck.Valid) {
                problem = try {
                    keys.save(key)
                    input = ""
                    null
                } catch (e: IOException) {
                    R.string.key_save_failed
                } catch (e: GeneralSecurityException) {
                    R.string.key_save_failed
                } catch (e: ProviderException) {
                    R.string.key_save_failed
                }
            }
            Log.i(MicrophoneRecorder.TAG, "key test $check")
            result = check.takeIf { problem == null }
            testing = false
        }
    }
}

@Composable
fun rememberKeyTest(keys: MistralKeyStore, keyCheck: MistralKeyCheck): KeyTest {
    val scope = rememberCoroutineScope()
    return remember(keys, keyCheck) { KeyTest(keys, keyCheck, scope) }
}

/** The masked field: password type, so no suggestions and no learning by the keyboard. */
@Composable
fun MistralKeyField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit = {}) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.key_field)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
