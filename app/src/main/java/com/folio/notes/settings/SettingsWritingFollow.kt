@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.settings

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.folio.notes.app.AppPrefs
import com.folio.notes.editor.follow.FollowEdgeStripPreview
import com.folio.notes.editor.follow.FollowFeel
import com.folio.notes.editor.follow.FollowMode
import com.folio.notes.editor.follow.FollowPreferenceStore
import com.folio.notes.editor.follow.FollowPreferences
import com.folio.notes.editor.follow.FollowPreview
import com.folio.notes.editor.follow.FollowTimingPreview
import com.folio.notes.editor.follow.SpacingExample
import com.folio.notes.editor.follow.WritingDirection
import com.folio.notes.editor.follow.WritingHand
import kotlin.math.roundToInt

/** All follow defaults live here; the editor menu only acts on the current view or response. */
@Composable internal fun FollowPage() {
    val p = rememberPrefs()
    val f by rememberPref(p, *FollowPreferenceStore.keys, read = FollowPreferenceStore::read)
    val hand by rememberPref(p, "writingHand", read = FollowPreferenceStore::hand)
    val paneHeight by rememberPref(p, AppPrefs.ZOOM_PANE_HEIGHT) {
        AppPrefs.zoomPaneHeight(it.getFloat(AppPrefs.ZOOM_PANE_HEIGHT, AppPrefs.DEFAULT_ZOOM_PANE_HEIGHT))
    }
    val change: (FollowPreferences) -> Unit = { FollowPreferenceStore.write(p, it) }
    val text = f.mode == FollowMode.TEXT

    SettingsGroup("Start here", footer = "The view stays still while your pen is down. Touch down during a glide to stop it. Infinite canvases only follow after you choose Write a response; its first stroke sets the column's margin.") {
        SettingsPrefSwitch("writingFollow", false, "Follow on pages", "Keep following enabled when writing on notebook and PDF pages.")
        SettingsDivider()
        SettingsSegmentedRow("What you write", listOf(FollowMode.TEXT to "Text", FollowMode.MATH to "Maths"), f.mode, { change(f.copy(mode = it)) })
        SettingsBlock { SettingsBlockHint(if (text) "Text can follow sideways and down, then return to the start of the next line."
            else "Maths reveals room below your working and keeps its horizontal position. Use Next line when you want a new row.") }
        SettingsDivider()
        SettingsSegmentedRow("Writing direction", listOf(WritingDirection.LTR to "Left → right", WritingDirection.RTL to "Right → left"), f.direction, { change(f.copy(direction = it)) })
        SettingsDivider()
        SettingsSegmentedRow("Hand holding the pen", listOf(WritingHand.RIGHT to "Right", WritingHand.LEFT to "Left"), hand, { p.write { putString("writingHand", it.name) } })
        SettingsBlock { SettingsBlockHint("Leaves a little more room on the side where your palm rests.") }
    }
    // Three independent knobs: when it moves (pause), where it moves (room), how it moves (speed).
    // A preset only sets these three, so tuning one never silently changes another.
    val feel = FollowFeel.of(f)
    val balanced = FollowPreferences()
    val widestStrip = (f.maximumEdgeStripWidth(hand) * 100).roundToInt()
    SettingsGroup("Feel", footer = "A starting point sets the three controls below; adjust any of them afterwards. Your hand, direction, writing position, line settings and automatic-return choice are kept.") {
        SettingsChipRow<FollowFeel?>("Start from", FollowFeel.entries.map { it to it.label }, feel, { it?.apply(f)?.let(change) },
            subtitle = if (feel == null) "Custom · your own mix of the controls below." else null)
        SettingsDivider()
        SettingsSliderRow("Pause before moving", FollowPreferences.returnDelayLabel(f.returnDelayMs), f.returnDelayMs / 1000f, .3f..2f,
            { change(f.copy(returnDelayMs = ((it * 20).roundToInt() * 50).coerceIn(300, 2000))) }, steps = 33,
            subtitle = "How long the pen rests before the view glides. Every pen lift starts the full pause again. Automatic line return can learn a longer pause from your line breaks.",
            onReset = if (f.returnDelayMs != balanced.returnDelayMs) ({ change(f.copy(returnDelayMs = balanced.returnDelayMs)) }) else null,
            lowLabel = "Shorter", highLabel = "Longer")
        SettingsDivider()
        SettingsSliderRow("Room before moving", "${(f.edgeStripWidth(hand) * 100).roundToInt()}% strip", 1f - f.edgeStripWidth(hand), (1f - widestStrip / 100f).. .9f,
            { change(f.copy(room = FollowPreferences.roomForStripWidth((100 - (it * 100).roundToInt()) / 100f))) }, steps = widestStrip - 11,
            subtitle = (if (text) "The edge strip is ${(f.edgeStripWidth(hand) * 100).roundToInt()}% of the view: write into it, then lift and pause to reveal more space. D" else "D") +
                "own once you write ${(f.verticalDeadBand * 100).roundToInt()}% of the view below your writing height.",
            onReset = if (f.room != balanced.room) ({ change(f.copy(room = balanced.room)) }) else null,
            lowLabel = "Move sooner", highLabel = "Wait for the edge")
        SettingsBlock { FollowEdgeStripPreview(f, hand) }
        SettingsDivider()
        SettingsSliderRow("Movement speed", FollowPreferences.paceLabel(f.pace), f.pace, 0f..1f,
            { change(f.copy(pace = (it * 20).roundToInt() / 20f)) }, steps = 19,
            subtitle = "How quickly the view glides, both sideways and to the next line. Reduced motion in Android settings skips the glide.",
            onReset = if (f.pace != balanced.pace) ({ change(f.copy(pace = balanced.pace)) }) else null,
            lowLabel = "Gentler", highLabel = "Snappier")
        SettingsBlock { FollowTimingPreview(f) }
    }
    SettingsGroup("Where your writing sits", footer = "Room before moving controls the edge strip's width. It disappears when the answer area's final edge is already visible. Touch down during a glide to stop it.") {
        SettingsBlock { FollowPreview(f, hand) }
        SettingsSliderRow("Writing height", "${f.positionPercent}% down", f.position, .35f.. .7f, { change(f.copy(position = it)) },
            subtitle = "Where the line sits after the view moves. 55% is just below the middle.", lowLabel = "Higher", highLabel = "Lower")
        SettingsDivider()
        SettingsSliderRow("Position after a sideways move", "${f.horizontalPercent}% across", f.horizontalPosition, .35f.. .65f, { change(f.copy(horizontalPosition = it)) },
            subtitle = "Where the end of your writing lands. Next line still returns to the answer margin.", enabled = text,
            lowLabel = "Further left", highLabel = "Further right")
        SettingsDivider()
        SettingsSliderRow("Edge strip visibility", if (f.edgeStripOpacity == 0f) "Hidden" else "${(f.edgeStripOpacity * 100).roundToInt()}% opacity",
            f.edgeStripOpacity, 0f.. .5f, { change(f.copy(edgeStripOpacity = AppPrefs.followEdgeStripOpacity((it * 100).roundToInt() / 100f))) }, steps = 49,
            subtitle = "A stronger tint makes the movement zone easier to see. Hiding it keeps the same trigger. Ink remains visible through it.",
            onReset = if (f.edgeStripOpacity != AppPrefs.DEFAULT_FOLLOW_EDGE_STRIP_OPACITY) ({ change(f.copy(edgeStripOpacity = AppPrefs.DEFAULT_FOLLOW_EDGE_STRIP_OPACITY)) }) else null,
            enabled = text, lowLabel = "Hidden", highLabel = "Stronger")
        SettingsBlock { FollowEdgeStripPreview(f, hand) }
    }
    SettingsGroup("Next line", footer = "Printed lines take priority over spacing. Spacing applies where the page has no printed lines.") {
        SettingsSwitchRow("Automatic line return", if (text) "Write across the line, then pause near the answer margin to return. Touch down to stop."
            else "Available in Text mode. In Maths, use the Next line button.", f.automaticReturn, { change(f.copy(automaticReturn = it)) }, enabled = text)
        SettingsDivider()
        SettingsSliderRow("Line-end zone", "Last ${(f.endMargin * 100).roundToInt()}% of area", f.endMargin, .02f.. .2f, { change(f.copy(endMargin = it)) },
            subtitle = "How close to the answer margin your lines usually end. Widen it if you leave a gap at the end of lines.", enabled = text,
            lowLabel = "Right to the margin", highLabel = "Earlier")
        SettingsDivider()
        SettingsSwitchRow("Show where the next line lands", "A dot and dashed line mark a pending return.", f.showLandingGuide, { change(f.copy(showLandingGuide = it)) })
        SettingsDivider()
        SettingsSwitchRow("Learn my line spacing", "After two natural line breaks, use the spacing you write with.", f.adaptiveSpacing, { change(f.copy(adaptiveSpacing = it)) }, enabled = text)
        SettingsDivider()
        SettingsSliderRow(if (f.adaptiveSpacing && text) "Starting line spacing" else "Line spacing", "${"%.1f".format(f.spacingMm)} mm", f.spacingMm, 4f..24f,
            { change(f.copy(spacing = FollowPreferences.fromMm(it))) }, steps = 19, subtitle = "7 mm resembles ruled paper; 8 mm gives a little more room. Measured on an A4 page.", lowLabel = "Closer", highLabel = "Further apart")
        SettingsBlock { SpacingExample(f.spacing) }
    }
    SettingsGroup("When following runs", footer = "Next line, Previous line and Back view always work by hand.") {
        // (sideways, down). Maths never moves sideways, so it only offers the choices that differ there.
        val moves = if (text) listOf((true to true) to "Sideways and down", (false to true) to "Down only",
            (true to false) to "Sideways only", (false to false) to "Only when I tap")
            else listOf((f.horizontalFollow to true) to "Down the page", (f.horizontalFollow to false) to "Only when I tap")
        SettingsChipRow("Move the view", moves, f.horizontalFollow to f.verticalFollow,
            { (sideways, down) -> change(f.copy(horizontalFollow = sideways, verticalFollow = down)) })
        SettingsDivider()
        SettingsSliderRow("Start following at this zoom", "${"%.1f".format(f.minimumZoom)}×", f.minimumZoom, 1f..3f, { change(f.copy(minimumZoom = it)) },
            subtitle = "Normal page following waits until you zoom in this far. Writing into the edge strip still reveals more room. Canvas responses use handwriting size on screen instead.", lowLabel = "Always", highLabel = "Zoomed in")
    }
    SettingsGroup("Answer areas", footer = "Use the editor's Answer areas menu to select, detect or clear the area on the current page. A canvas response keeps the column you explicitly started.") {
        SettingsPrefSwitch("follow.autoDetectAnswerAreas", false, "Detect answer areas automatically", "Look for answer lines on the current PDF page while following is on, in scrolling or one-page view.")
        SettingsDivider()
        SettingsPrefSwitch("follow.showAnswerAreas", true, "Show answer area outlines", "Make the active writing area's boundaries visible.")
        SettingsDivider()
        SettingsSwitchRow("Switch to the area I write in", "Follow another detected area when you begin writing there. Turn off to keep the current area selected.", f.autoSwitchAreas, { change(f.copy(autoSwitchAreas = it)) })
    }
    SettingsGroup("Zoom pane & peek") {
        SettingsSliderRow("Zoom pane height", "${paneHeight.roundToInt()} dp", paneHeight, 180f..480f,
            { p.write { putFloat(AppPrefs.ZOOM_PANE_HEIGHT, AppPrefs.zoomPaneHeight(it)) } },
            subtitle = "Starting height of the enlarged writing pane. You can also drag its divider; smaller windows cap its height to leave room for the page.", lowLabel = "Shorter", highLabel = "Taller")
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.AUTO_PEEK, false, "Use the whole page for auto peek", "Show the page overview in peek without pinning a view. Applies to finite pages.")
    }
    SettingsGroup("Reset") {
        SettingsBlock {
            SettingsBlockHint("Restore writing-follow defaults, including the hand, answer-area display, movement zone and strip visibility, and pane height. Saved answer areas and pinned peek views are kept.")
            TextButton({
                FollowPreferenceStore.write(p, FollowPreferences())
                p.write {
                    putBoolean("writingFollow", false)
                    putString("writingHand", WritingHand.RIGHT.name)
                    putBoolean("follow.autoDetectAnswerAreas", false)
                    putBoolean("follow.showAnswerAreas", true)
                    putBoolean(AppPrefs.AUTO_PEEK, false)
                    putFloat(AppPrefs.ZOOM_PANE_HEIGHT, AppPrefs.DEFAULT_ZOOM_PANE_HEIGHT)
                }
            }, shapes = ButtonDefaults.shapes()) { Text("Reset writing follow") }
        }
    }
}
