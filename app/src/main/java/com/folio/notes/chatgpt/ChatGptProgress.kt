package com.folio.notes.chatgpt

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import kotlinx.coroutines.delay
import java.text.NumberFormat

@Composable internal fun ChatGptProgress(state: ChatGptState, manager: ChatGptManager) {
    var elapsed by remember(state.startedAt) { mutableLongStateOf(0) }
    val inference = state.operation == ChatGptOperation.REVIEW || state.operation == ChatGptOperation.TEST
    LaunchedEffect(state.startedAt, inference) {
        if (inference && state.startedAt > 0) while (true) {
            elapsed = (SystemClock.elapsedRealtime() - state.startedAt).coerceAtLeast(0)
            delay(1000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text(state.status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            TextButton(manager::cancel, enabled = state.operation != ChatGptOperation.OPEN) { Text("Cancel") }
        }
        if (inference) {
            val step = when (state.stage) {
                ChatGptStage.PREPARING -> 1; ChatGptStage.SENDING -> 2
                ChatGptStage.WAITING, ChatGptStage.REASONING -> 3
                ChatGptStage.WRITING -> 4; ChatGptStage.CHECKING -> 5
            }
            Text("Step $step of 5 · ${ChatGptTimings.duration(elapsed)} elapsed · ${ChatGptInference.effortLabel(state.effort)} reasoning",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.operation == ChatGptOperation.REVIEW) Text(ChatGptTimings.remaining(state.estimatedMillis, elapsed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (elapsed >= 30_000 && state.stage.ordinal < ChatGptStage.WRITING.ordinal)
                Text("Complex pages and higher reasoning can take several minutes. You can close this panel and return while Folio stays open.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun ChatGptUsageLink() {
    val context = LocalContext.current
    TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }) {
        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Manage usage")
    }
}

@Composable internal fun ChatGptUsageContent(summary: ChatGptUsageSummary?) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text("ChatGPT usage", style = MaterialTheme.typography.titleMedium)
            if (summary != null) {
                Text("${summary.completed} completed ${if (summary.completed == 1) "request" else "requests"} in this Folio session", style = MaterialTheme.typography.bodyMedium)
                if (summary.reported > 0) Text("${numbers.format(summary.tokens)} reported tokens · ${summary.reported} ${if (summary.reported == 1) "request" else "requests"}", style = MaterialTheme.typography.bodyMedium)
                summary.last?.let { last ->
                    Text("Last response: ${numbers.format(last.input)} input · ${numbers.format(last.output)} output", style = MaterialTheme.typography.bodySmall)
                    last.reasoning?.let { Text("${numbers.format(it)} reasoning tokens, included in output", style = MaterialTheme.typography.bodySmall) }
                    last.cached?.takeIf { it > 0 }?.let { Text("${numbers.format(it)} cached input tokens", style = MaterialTheme.typography.bodySmall) }
                } ?: Text("The last response did not report token usage.", style = MaterialTheme.typography.bodySmall)
            } else Text("Reported token usage will appear after your first completed response.", style = MaterialTheme.typography.bodyMedium)
            Text("This covers completed requests reported to Folio during this app session. Canceled or failed requests may also use your plan. Remaining allowance and reset times are available in ChatGPT settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChatGptUsageLink()
        }
    }
}
