package com.folio.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** The sticky note with its menu showing, framed in view fractions (0..1) like a lasso selection. */
internal data class StickyFocus(val box: TextBox, val frame: Rect, val typing: Boolean)

/** Words being typed into a note, held until typing ends so a note is one undo step, not one per key. */
internal data class StickyDraft(val id: String, val value: TextFieldValue)

/**
 * Typing happens in place: a transparent field over the note, at the note's own size and type,
 * while the page keeps drawing the note's paper and ink underneath. Back or losing focus ends it.
 */
@Composable internal fun StickyNoteField(
    box: TextBox, frame: Rect, viewWidth: Float, viewHeight: Float,
    value: TextFieldValue, onValue: (TextFieldValue) -> Unit, onDone: () -> Unit
) {
    val density = LocalDensity.current
    val left = frame.left * viewWidth; val top = frame.top * viewHeight
    val width = frame.width * viewWidth; val height = frame.height * viewHeight
    if (width <= 0f || height <= 0f || box.width <= 0f) return
    val unit = width / box.width
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(box.id) { focus.requestFocus() }
    BackHandler(onBack = onDone)
    val color = Color(box.color)
    with(density) {
        BasicTextField(value, onValue,
            modifier = Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(width.toDp(), height.toDp())
                .focusRequester(focus)
                .onFocusChanged { if (focused && !it.isFocused) onDone(); focused = it.isFocused }
                .padding((StickyNotes.PADDING * unit).toDp()),
            textStyle = TextStyle(color = color, fontSize = (box.size * unit).toSp(), fontFamily = FontFamily.Serif,
                fontWeight = if (box.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (box.italic) FontStyle.Italic else FontStyle.Normal),
            cursorBrush = SolidColor(color),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { field ->
                Box {
                    if (value.text.isEmpty()) androidx.compose.material3.Text("Type a note…",
                        style = TextStyle(color = color.copy(alpha = .45f), fontSize = (box.size * unit).toSp(), fontFamily = FontFamily.Serif))
                    field()
                }
            })
    }
}
