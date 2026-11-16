package tech.dmconsulting.dixi.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.ui.components.DixiTopBar
import tech.dmconsulting.dixi.ui.theme.Dimens

/** Screen 9: the last dictations, newest first. Tapping a line or its icon copies the text. */
@Composable
fun HistoryScreen(history: DictationHistory, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val entries by history.entries.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copiedMessage = stringResource(R.string.text_copied)
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val copy: (Dictation) -> Unit = { dictation ->
        if (copyToClipboard(context, dictation.text)) {
            scope.launch { snackbar.showSnackbar(copiedMessage) }
        }
    }

    Scaffold(
        topBar = {
            DixiTopBar(stringResource(R.string.screen_history), onBack) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.history_open_settings),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val list = entries
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Dimens.ListMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.history_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    // Still reading the file: show nothing rather than a false "empty".
                    list == null -> Unit

                    list.isEmpty() -> item {
                        Box(Modifier.fillMaxWidth().padding(vertical = Dimens.ScreenMargin), Alignment.Center) {
                            Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    else -> items(list) { dictation ->
                        HistoryItem(dictation, onCopy = { copy(dictation) })
                    }
                }
            }
            if (!list.isNullOrEmpty()) {
                TextButton(
                    onClick = { confirmClear = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ListMargin, vertical = 8.dp)
                        .heightIn(min = Dimens.ButtonHeight),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, Modifier.size(20.dp))
                    Text(stringResource(R.string.history_clear_all), Modifier.padding(start = 8.dp))
                }
            }
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
private fun HistoryItem(dictation: Dictation, onCopy: () -> Unit) {
    val date = dictationDateLabel(dictation.createdAt)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.history_copy_action), onClick = onCopy),
    ) {
        Row(
            Modifier.padding(start = Dimens.ListMargin, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    date,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    dictation.text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(Dimens.MinTouchTarget)) {
                Icon(
                    painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.history_copy),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
