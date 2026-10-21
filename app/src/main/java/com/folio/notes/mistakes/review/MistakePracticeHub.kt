@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.folio.notes.mistakes.MistakeToday
import com.folio.notes.mistakes.TodayActions
import com.folio.notes.mistakes.TodayContent
import com.folio.notes.mistakes.isoTime
import com.folio.notes.ui.FolioShapes
import java.time.format.DateTimeFormatter

@Composable internal fun PracticeConfetti(event: Int, enabled: Boolean, finish: String, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    val context = LocalContext.current
    LaunchedEffect(event, enabled) {
        if (event > 0 && enabled && android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1000))
        }
    }
    val color = Color((MistakePracticeProgress.finishes.find { it.id == finish } ?: MistakePracticeProgress.finishes.first()).color)
    Canvas(modifier.clearAndSetSemantics {}) {
        val t = progress.value
        if (t < 1f) repeat(22) { i ->
            val x = size.width * (i * 37 % 101) / 100 + (if (i % 2 == 0) -1 else 1) * (20 + i * 3) * t
            val y = size.height.coerceAtMost(300.dp.toPx()) * t
            rotate(i * 43f * t, Offset(x, y)) {
                drawRect(color.copy(alpha = (1f - t) * .85f), Offset(x, y), Size(4.dp.toPx(), 7.dp.toPx()))
            }
        }
    }
}

@Composable internal fun MistakePracticeHub(content: TodayContent, actions: TodayActions) {
    val progress = content.progress
    val settings = content.preferences
    val finish = MistakePracticeProgress.finishes.find { it.id == settings.practiceFinish && progress.xp >= it.xp } ?: MistakePracticeProgress.finishes.first()
    val color = Color(finish.color)
    val active = content.mistakes.filterNot { it.suspended }.filter { content.focus.isBlank() || content.contexts[it.attemptId]?.subject.orEmpty().ifBlank { MistakeToday.UNSORTED } == content.focus }
    val slot = MistakePracticeProgress.nextWindow(settings, content.now)
    var collection by remember { mutableStateOf(false) }
    OutlinedCard(shape = FolioShapes.panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR PRACTICE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Level ${progress.level} · ${progress.xp} XP", style = MaterialTheme.typography.titleLarge)
                }
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(finish.label, style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(progress = { progress.xp % 100 / 100f }, color = color, modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${100 - progress.xp % 100} XP to the next level" })
            Text("10 XP per different card each day, whatever your rating. Each daily mission earns 20 extra. ${progress.established} cards established through spaced recall.", style = MaterialTheme.typography.bodySmall)
            progress.quests.forEach { quest ->
                val cards = MistakePracticeProgress.missionCards(active, quest.id, content.now)
                OutlinedCard(onClick = { actions.onMission(cards.take(maxOf(1, quest.target - quest.done)), false) }, enabled = !content.working && cards.isNotEmpty() && quest.done < quest.target, shape = FolioShapes.large) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (quest.done >= quest.target) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = color)
                            Text("${quest.label} · ${if (quest.done >= quest.target) "Complete" else "${quest.done}/${quest.target}"}", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(quest.detail, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { (quest.done.toFloat() / quest.target).coerceAtMost(1f) }, color = color, modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${quest.label}, ${minOf(quest.done, quest.target)} of ${quest.target}" })
                    }
                }
            }
            HorizontalDivider()
            Text("Weekly challenge · 75 XP", style = MaterialTheme.typography.titleMedium)
            Text(when {
                progress.weeklyComplete -> "Complete. Your finish is earned."
                progress.challenge.isEmpty() -> "Opens next week after you've practised some cards."
                else -> "${progress.weeklyCount}/${progress.weeklyTarget} cards · a mixed revisit of earlier practice"
            }, style = MaterialTheme.typography.bodySmall)
            OutlinedButton({
                val completed = content.mistakes.filter { m -> m.reviewHistory.any { it.challengeWeek == progress.week } }.map { it.id }.toSet()
                actions.onMission(progress.challenge.filterNot { it.id in completed }, true)
            }, enabled = !content.working && progress.challenge.isNotEmpty() && !progress.weeklyComplete, shapes = ButtonDefaults.shapes()) {
                Icon(if (progress.weeklyComplete) Icons.Rounded.EmojiEvents else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (progress.weeklyComplete) "Completed" else "Take the challenge")
            }
            HorizontalDivider()
            Text(slot?.let { "${if (it.toInstant().toEpochMilli() <= content.now) "Practice window open" else "Next practice"} · ${it.format(DateTimeFormatter.ofPattern("EEE HH:mm"))}" } ?: "Practice whenever it suits you", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(actions.onRoutine, enabled = !content.working) { Text("Edit routine & effects") }
                if (slot != null) TextButton({
                    val tomorrow = slot.toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    actions.onPreferences(settings.copy(practiceSnoozedUntil = isoTime(tomorrow)))
                }, enabled = !content.working) { Text("Skip this window") }
            }
            if (progress.predictions > 0) Text("Confidence matched recall on ${progress.calibration}% of ${progress.predictions} predictions. Guessing → Again, unsure → Hard, confident → Good/Easy.", style = MaterialTheme.typography.bodySmall)
            TextButton({ collection = !collection }) { Text(if (collection) "Hide finish collection" else "Finish collection") }
            if (collection) {
                Text("Earned finishes colour your progress and celebrations. Your collection stays earned when you take a day off.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MistakePracticeProgress.finishes.forEach { value ->
                        FilterChip(selected = finish.id == value.id, onClick = { actions.onPreferences(settings.copy(practiceFinish = value.id)) }, enabled = progress.xp >= value.xp && !content.working,
                            label = { Text(if (progress.xp < value.xp) "${value.label} · ${value.xp} XP" else value.label) }, leadingIcon = { Box(Modifier.size(8.dp).background(Color(value.color), CircleShape)) })
                    }
                }
            }
        }
    }
}
