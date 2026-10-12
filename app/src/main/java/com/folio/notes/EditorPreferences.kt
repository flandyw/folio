package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.runtime.*

/** One observed snapshot for shared ink/navigation preferences; no page content or cameras. */
internal data class EditorPreferences(
    val palmRejectMs: Long,
    val panMultiplier: Float,
    val pageFollowEnabled: Boolean,
    val snapEnabled: Boolean,
    val shapeRecognition: Boolean,
    val eraserSingleStroke: Boolean,
    val eraserPressure: Boolean,
    val scribbleToErase: Boolean,
    val scribbleSensitivity: Float,
    val eraserWholeStroke: Boolean,
    val shapeMeasurements: Boolean,
    val multiTouchUndo: Boolean,
    val graphStyle: GraphStyle,
    val singlePage: Boolean,
    val focusMode: Boolean
)

@Composable internal fun rememberEditorPreferences(prefs: SharedPreferences, shapeRecognitionSetting: Boolean): State<EditorPreferences> =
    rememberPref(prefs, AppPrefs.PALM_MS, AppPrefs.FAST_PAN, AppPrefs.FAST_PAN_MULTIPLIER, "writingFollow", "mathSnap", "shapeRecognition", GraphStyle.PREF_KEY, AppPrefs.SINGLE_PAGE_VIEW, AppPrefs.EDITOR_FOCUS_MODE, EditorQuickPrefs.ERASER_SINGLE_STROKE, EditorQuickPrefs.ERASER_PRESSURE, EditorQuickPrefs.SCRIBBLE_TO_ERASE, EditorQuickPrefs.SCRIBBLE_SENSITIVITY, EditorQuickPrefs.ERASER_WHOLE_STROKE, EditorQuickPrefs.SHAPE_MEASUREMENTS, EditorQuickPrefs.MULTI_TOUCH_UNDO) { p ->
        EditorPreferences(
            palmRejectMs = AppPrefs.palmMs(p.getLong(AppPrefs.PALM_MS, AppPrefs.DEFAULT_PALM_MS).takeIf { p.contains(AppPrefs.PALM_MS) }),
            panMultiplier = AppPrefs.panFactor(p.getBoolean(AppPrefs.FAST_PAN, AppPrefs.DEFAULT_FAST_PAN), p.getFloat(AppPrefs.FAST_PAN_MULTIPLIER, AppPrefs.DEFAULT_FAST_PAN_MULTIPLIER)),
            pageFollowEnabled = p.getBoolean("writingFollow", false),
            snapEnabled = p.getBoolean("mathSnap", true),
            shapeRecognition = p.getBoolean("shapeRecognition", shapeRecognitionSetting),
            eraserSingleStroke = p.getBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, false),
            eraserPressure = p.getBoolean(EditorQuickPrefs.ERASER_PRESSURE, true),
            scribbleToErase = p.getBoolean(EditorQuickPrefs.SCRIBBLE_TO_ERASE, true),
            scribbleSensitivity = p.getFloat(EditorQuickPrefs.SCRIBBLE_SENSITIVITY, ScribbleSensitivity.DEFAULT),
            eraserWholeStroke = p.getBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, false),
            shapeMeasurements = p.getBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, true),
            multiTouchUndo = p.getBoolean(EditorQuickPrefs.MULTI_TOUCH_UNDO, true),
            graphStyle = GraphStyle.load(p),
            singlePage = p.getBoolean(AppPrefs.SINGLE_PAGE_VIEW, false),
            focusMode = p.getBoolean(AppPrefs.EDITOR_FOCUS_MODE, false)
        )
    }
