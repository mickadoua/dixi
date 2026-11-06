package tech.dmconsulting.voxbulle.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.transcription.Transcript
import tech.dmconsulting.voxbulle.transcription.TranscriptionPipeline
import tech.dmconsulting.voxbulle.transcription.VoxtralResult
import tech.dmconsulting.voxbulle.ui.theme.Dimens
import java.io.File

/**
 * Demo-mode run of the transcription on screen 3: no microphone, the fake
 * services answer. Shows the « brut » and « Texte Dixi » cards or the error
 * message. Temporary: the real screen 3 (with recording) comes with DMC-140.
 */
@Composable
fun TrialPreview(pipeline: TranscriptionPipeline) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VoxtralResult<Transcript>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing)) {
        Button(
            onClick = {
                running = true
                scope.launch {
                    // The fakes never read the file: nothing is recorded or created.
                    result = pipeline.run(File(context.cacheDir, "demo.m4a"))
                    running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeight),
        ) {
            Text(stringResource(R.string.trial_demo_run))
        }
        if (running) CircularProgressIndicator()
        when (val current = result) {
            null -> Unit

            is VoxtralResult.Failure -> Text(
                stringResource(current.error.message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )

            is VoxtralResult.Success -> {
                TextCard(
                    label = stringResource(R.string.trial_raw),
                    text = current.value.raw,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextCard(
                    label = stringResource(R.string.trial_clean),
                    text = current.value.text,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                current.value.cleanupError?.let {
                    Text(stringResource(it.message), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TextCard(label: String, text: String, container: Color, content: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Dimens.ListMargin), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
