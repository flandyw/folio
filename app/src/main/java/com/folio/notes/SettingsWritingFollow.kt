@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var details by rememberSaveable { mutableStateOf(false) }

    SettingsGroup("Start here", footer = "The view stays still while your pen is down. Touch down during a glide to stop it. Infinite canvases only follow after you choose Write a response; each response starts with automatic return off.") {
        SettingsPrefSwitch("writingFollow", false, "Follow on pages", "Keep following enabled when writing on notebook and PDF pages.")
        SettingsDivider()
        SettingsSegmentedRow("What you write", listOf(FollowMode.TEXT to "Text", FollowMode.MATH to "Maths"), f.mode, { change(f.copy(mode = it)) })
        SettingsBlock { SettingsBlockHint(if (f.mode == FollowMode.TEXT) "Text can follow sideways and down, then return to the start of the next line."
            else "Maths reveals room below your working and keeps its horizontal position. Use Next line when you want a new row.") }
        SettingsDivider()
        SettingsSegmentedRow("Writing direction", listOf(WritingDirection.LTR to "Left → right", WritingDirection.RTL to "Right → left"), f.direction, { change(f.copy(direction = it)) })
        SettingsDivider()
        SettingsSegmentedRow("Hand holding the pen", listOf(WritingHand.RIGHT to "Right", WritingHand.LEFT to "Left"), hand, { p.write { putString("writingHand", it.name) } })
        SettingsBlock { SettingsBlockHint("Leaves a little more room on the side where your palm rests.") }
    }
    SettingsGroup("Movement feel", footer = "Presets change movement thresholds and timing together. They keep your direction, hand, writing position and automatic-return choice.") {
        SettingsChipRow("Quick setup", listOf(0f to "Relaxed", .5f to "Balanced", 1f to "Responsive"),
            listOf(0f, .5f, 1f).firstOrNull { FollowComfort.matches(f, it) } ?: -1f, { change(FollowComfort.apply(f, it)) })
        SettingsSliderRow("Following feel", if (FollowComfort.matches(f, 0f)) "Relaxed" else if (FollowComfort.matches(f, 1f)) "Responsive" else if (FollowComfort.matches(f, .5f)) "Balanced" else "Custom",
            FollowComfort.value(f), 0f..1f, { change(FollowComfort.apply(f, it)) },
            subtitle = "Relaxed waits longer. Responsive reveals space sooner and moves faster.", lowLabel = "More relaxed", highLabel = "More responsive")
    }
    SettingsGroup("Position on screen") {
        SettingsBlock { FollowPreview(f, hand) }
        SettingsSliderRow("Writing height", "${f.positionPercent}% down", f.position, .35f.. .7f, { change(f.copy(position = it)) },
            subtitle = "Where the line sits after the view moves. 55% is just below the middle.", lowLabel = "Higher", highLabel = "Lower")
        SettingsDivider()
        SettingsSliderRow("Position after a sideways move", "${f.horizontalPercent}% across", f.horizontalPosition, .35f.. .65f, { change(f.copy(horizontalPosition = it)) },
            subtitle = "Where the end of your writing lands. Next line still returns to the answer margin.", enabled = f.mode == FollowMode.TEXT,
            lowLabel = "Further left", highLabel = "Further right")
    }
    SettingsGroup("Pale edge strip", footer = "A visual hint that there is more room ahead. Its width does not change when the page moves. The movement threshold is in Fine-tune movement below. Hidden at the answer area's final edge.") {
        SettingsBlock { FollowEdgeStripPreview(f) }
        SettingsSliderRow("Strip width", if (f.edgeStripWidth == 0f) "Hidden" else "${(f.edgeStripWidth * 100).roundToInt()}% of view",
            f.edgeStripWidth, 0f.. .35f, { change(f.copy(edgeStripWidth = AppPrefs.followEdgeStripWidth(it))) }, steps = 34,
            subtitle = "Adjust the pale rectangle at the edge of the writing view. It follows your writing direction.",
            onReset = { change(f.copy(edgeStripWidth = AppPrefs.DEFAULT_FOLLOW_EDGE_STRIP_WIDTH)) }, lowLabel = "Hidden", highLabel = "Wider")
    }
    SettingsGroup("Next line") {
        SettingsSwitchRow("Automatic line return", if (f.mode == FollowMode.TEXT) "Write across the line, then pause near the answer margin to return. Touch down to stop."
            else "Available in Text mode. In Maths, use the Next line button.", f.automaticReturn, { change(f.copy(automaticReturn = it)) }, enabled = f.mode == FollowMode.TEXT)
        SettingsDivider()
        SettingsSwitchRow("Show next-line landing guide", "A dot and dashed line show where a pending return will land.", f.showLandingGuide, { change(f.copy(showLandingGuide = it)) })
        SettingsDivider()
        SettingsSliderRow("Minimum pause before returning", FollowPreferences.returnDelayLabel(f.returnDelayMs), f.returnDelayMs / 1000f, .3f..2f,
            { change(f.copy(returnDelayMs = (it * 1000).roundToInt())) }, subtitle = "Three natural line breaks can teach a longer pause. Corrections restart the pause. Sideways movement learns your word pauses separately.", lowLabel = "Shorter wait", highLabel = "Longer wait")
    }
    SettingsGroup("Space between lines", footer = "Printed lines take priority. These controls apply where the page has no printed lines.") {
        SettingsSwitchRow("Learn my line spacing", "After two natural line breaks, use the spacing you write with.", f.adaptiveSpacing, { change(f.copy(adaptiveSpacing = it)) }, enabled = f.mode == FollowMode.TEXT)
        SettingsDivider()
        SettingsSliderRow("Starting line spacing", "${"%.1f".format(f.spacingMm)} mm", f.spacingMm, 4f..24f,
            { change(f.copy(spacing = FollowPreferences.fromMm(it))) }, steps = 19, subtitle = "7 mm resembles ruled paper; 8 mm gives a little more room. Measured on an A4 page.", lowLabel = "Closer", highLabel = "Further apart")
        SettingsBlock { SpacingExample(f.spacing) }
    }
    SettingsGroup("Answer areas", footer = "Use the editor's Answer areas menu to select, detect or clear the area on the current page. A canvas response keeps the column you explicitly started.") {
        SettingsPrefSwitch("follow.autoDetectAnswerAreas", false, "Detect answer areas as I scroll", "Look for answer lines on the current PDF page while following is on.")
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
    SettingsGroup("Fine-tune movement", footer = "Manual Next line, Previous line and Back view still work when automatic tracking is off.") {
        SettingsSwitchRow("Follow sideways in Text", "Reveal more of the current line after lifting the pen.", f.horizontalFollow, { change(f.copy(horizontalFollow = it)) }, enabled = f.mode == FollowMode.TEXT)
        SettingsDivider()
        SettingsSwitchRow("Follow down the page", "Reveal room below as your writing moves down.", f.verticalFollow, { change(f.copy(verticalFollow = it)) })
        SettingsDivider()
        SettingsBlock {
            TextButton({ details = !details }, shapes = ButtonDefaults.shapes()) { Text(if (details) "Hide movement details" else "Show movement details") }
        }
        if (details) {
            SettingsSliderRow("Start following at this zoom", "${"%.1f".format(f.minimumZoom)}×", f.minimumZoom, 1f..3f, { change(f.copy(minimumZoom = it)) }, subtitle = "Page following waits until you zoom in this far. Canvas responses use handwriting size on screen instead.")
            SettingsDivider()
            SettingsSliderRow("Start sideways movement", "${(f.edgeThreshold * 100).roundToInt()}% across", f.edgeThreshold, .55f.. .95f, { change(f.copy(edgeThreshold = it)) },
                subtitle = "Write past this point in the view, then lift the pen. Mirrored for right-to-left writing. Leaves room beyond your chosen writing position.", enabled = f.mode == FollowMode.TEXT, lowLabel = "Move sooner", highLabel = "Closer to edge")
            SettingsDivider()
            SettingsSliderRow("Room below the writing line", "${(f.verticalDeadBand * 100).roundToInt()}% of view", f.verticalDeadBand, .05f.. .3f, { change(f.copy(verticalDeadBand = it)) },
                subtitle = "How far below your chosen writing height you can write before the view moves.", lowLabel = "Move sooner", highLabel = "More room")
            SettingsDivider()
            SettingsSliderRow("Line-end return zone", "Last ${(f.endMargin * 100).roundToInt()}% of area", f.endMargin, .02f.. .2f, { change(f.copy(endMargin = it)) },
                subtitle = "How close to the answer margin your writing must finish before an automatic return is offered.", enabled = f.mode == FollowMode.TEXT, lowLabel = "Closer to margin", highLabel = "Larger zone")
            SettingsDivider()
            SettingsSliderRow("Glide duration", "${f.glideDurationMs} ms", f.glideDurationMs.toFloat(), 120f..800f, { change(f.copy(glideDurationMs = it.roundToInt())) }, subtitle = "Minimum time for a smooth move. Longer travel takes extra time.", lowLabel = "Quicker", highLabel = "Gentler")
            SettingsDivider()
            SettingsSliderRow("Next-line travel time", "${f.lineSpeedMs} ms / screen", f.lineSpeedMs.toFloat(), 250f..1500f, { change(f.copy(lineSpeedMs = it.roundToInt())) }, subtitle = "Time to cross a full screen during line return. Lower values move faster.", lowLabel = "Faster", highLabel = "Slower")
            SettingsBlock { FollowTimingPreview(f) }
        }
    }
    SettingsGroup("Reset") {
        SettingsBlock {
            SettingsBlockHint("Restore writing-follow defaults, including the hand, answer-area display, strip width and pane height. Saved answer areas and pinned peek views are kept.")
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
