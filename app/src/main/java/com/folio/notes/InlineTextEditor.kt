package com.folio.notes

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/** Coordinates from the ink camera, in local view pixels rather than screen or page units. */
internal data class InlineTextFrame(val id: String, val left: Float, val top: Float, val width: Float, val height: Float, val scale: Float)

/** One visit to a text box. The draft is saved across recreation and committed once on finishing. */
internal data class InlineTextSession(val pageId: String, val box: TextBox)

/** Native editing gives the page a real caret, IME composition, selection handles and clipboard. */
private class PageTextField(context: Context) : EditText(context) {
    var onValue: (String) -> Unit = {}
    var onDone: () -> Unit = {}
    var onCaret: (Rect) -> Unit = {}
    var onSelection: (Int, Int) -> Unit = { _, _ -> }
    private var binding = false

    init {
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        setLineSpacing(0f, 1.1f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        setHorizontallyScrolling(false)
        isFocusableInTouchMode = true
        contentDescription = "Text on page"
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!binding) onValue(s?.toString().orEmpty())
                post { reportCaret() }
            }
        })
        setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (code == KeyEvent.KEYCODE_ESCAPE || (code == KeyEvent.KEYCODE_ENTER && event.isCtrlPressed))) {
                onDone(); true
            } else false
        }
    }

    fun bind(box: TextBox, scale: Float) {
        binding = true
        // Never replace an unchanged Editable: doing so loses the composing region and selection.
        if (text.toString() != box.text) {
            setText(box.text)
            setSelection(box.text.length)
        }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, box.size.coerceIn(TextBox.MIN_SIZE, TextBox.MAX_SIZE) * scale)
        typeface = Typeface.create(Typeface.SERIF, if (box.bold) Typeface.BOLD else Typeface.NORMAL)
        paint.isFakeBoldText = box.bold
        paint.textSkewX = if (box.italic) -.25f else 0f
        paintFlags = if (box.underline) paintFlags or Paint.UNDERLINE_TEXT_FLAG else paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        val alpha = (android.graphics.Color.alpha(box.color) * box.opacity).toInt().coerceIn(0, 255)
        setTextColor((box.color and 0x00ffffff) or (alpha shl 24))
        gravity = Gravity.TOP or when (box.align) {
            TextAlignMode.LEFT -> Gravity.START
            TextAlignMode.CENTER -> Gravity.CENTER_HORIZONTAL
            TextAlignMode.RIGHT -> Gravity.END
        }
        binding = false
        post { reportCaret() }
    }

    private fun reportCaret() {
        val lines = layout ?: return
        val position = selectionEnd.coerceIn(0, text.length)
        val line = lines.getLineForOffset(position)
        val x = lines.getPrimaryHorizontal(position) - scrollX
        onCaret(Rect(x, lines.getLineTop(line).toFloat() - scrollY, x + 2f, lines.getLineBottom(line).toFloat() - scrollY))
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // TextView can call this during its constructor, before Kotlin fields are initialized.
        post {
            if (selectionStart >= 0 && selectionEnd >= 0) onSelection(selectionStart, selectionEnd)
            reportCaret()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        reportCaret()
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onDone()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}

@Composable internal fun InlineTextField(
    box: TextBox, frame: InlineTextFrame, page: NotePage,
    onChange: (TextBox) -> Unit, onDone: () -> Unit,
    topInset: Float, onRevealCaret: (Rect) -> Unit
) {
    if (frame.scale <= 0f || frame.width <= 0f) return
    val density = LocalDensity.current
    val currentBox by rememberUpdatedState(box)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val bringIntoView = remember { BringIntoViewRequester() }
    var caret by remember(box.id) { mutableStateOf<Rect?>(null) }
    var selectionStart by rememberSaveable(box.id) { mutableIntStateOf(box.text.length) }
    var selectionEnd by rememberSaveable(box.id) { mutableIntStateOf(box.text.length) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    // Camera translation alone must not pull the view back while the user pans or moves the box.
    LaunchedEffect(caret, imeBottom, frame.scale, topInset) {
        caret?.let {
            onRevealCaret(it)
            if (!page.infinite) bringIntoView.bringIntoView(
                it.copy(top = it.top - topInset, bottom = it.bottom + with(density) { 24.dp.toPx() }))
        }
    }
    fun move(dx: Float, dy: Float) {
        val source = currentBox
        val x = if (page.infinite) source.x + dx else (source.x + dx).coerceIn(0f, (page.width - source.width).coerceAtLeast(0f))
        val y = if (page.infinite) source.y + dy else (source.y + dy).coerceIn(0f, (page.height - source.size * 1.1f).coerceAtLeast(0f))
        change(source.copy(x = x, y = y))
    }
    fun resize(dx: Float) {
        val source = currentBox
        val limit = if (page.infinite) TextBox.MAX_WIDTH else (page.width - source.x).coerceAtLeast(TextBox.MIN_WIDTH).coerceAtMost(TextBox.MAX_WIDTH)
        change(source.copy(width = (source.width + dx).coerceIn(TextBox.MIN_WIDTH, limit)))
    }
    with(density) {
        Box(Modifier.offset { IntOffset((frame.left - 3.dp.toPx()).roundToInt(), (frame.top - 3.dp.toPx()).roundToInt()) }
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .size(frame.width.toDp() + 6.dp, frame.height.toDp() + 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary, FolioShapes.small))
        key(box.id) {
            AndroidView(
                factory = { context -> PageTextField(context).apply {
                    onValue = { value -> if (currentBox.id == box.id) change(currentBox.copy(text = value)) }
                    this.onDone = { done() }
                    onCaret = { caret = it }
                    onSelection = { start, end -> selectionStart = start; selectionEnd = end }
                    bind(box, frame.scale)
                    setSelection(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))
                    post {
                        requestFocus()
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                } },
                modifier = Modifier.offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size(frame.width.toDp(), frame.height.toDp())
                    .bringIntoViewRequester(bringIntoView),
                update = { it.bind(box, frame.scale) },
                onRelease = { field -> field.onValue = {}; field.onDone = {}; field.onCaret = {}; field.onSelection = { _, _ -> }; field.clearFocus() }
            )
        }
        IconButton({}, Modifier.offset { IntOffset(frame.left.roundToInt(), (frame.top - 46.dp.toPx()).roundToInt()) }
            .size(40.dp)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Move left") { move(-18f, 0f); true },
                    CustomAccessibilityAction("Move right") { move(18f, 0f); true },
                    CustomAccessibilityAction("Move up") { move(0f, -18f); true },
                    CustomAccessibilityAction("Move down") { move(0f, 18f); true })
            }
            .pointerInput(box.id, frame.scale) {
                detectDragGestures { event, delta -> event.consume(); move(delta.x / frame.scale, delta.y / frame.scale) }
            }, colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Icon(Icons.Rounded.OpenWith, "Move text box", Modifier.size(18.dp))
        }
        IconButton({}, Modifier.offset { IntOffset((frame.left + frame.width - 20.dp.toPx()).roundToInt(), (frame.top + frame.height + 5.dp.toPx()).roundToInt()) }
            .size(40.dp)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Make wider") { resize(18f); true },
                    CustomAccessibilityAction("Make narrower") { resize(-18f); true })
            }
            .pointerInput(box.id, frame.scale) {
                detectDragGestures { event, delta -> event.consume(); resize(delta.x / frame.scale) }
            }, colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Icon(Icons.AutoMirrored.Rounded.WrapText, "Resize text wrap width", Modifier.size(18.dp))
        }
    }
}

/** Formatting belongs to the editor chrome and previews immediately on the page. */
@Composable internal fun InlineTextToolbar(
    box: TextBox, colors: List<Int>, onChange: (TextBox) -> Unit,
    onDone: () -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit
) {
    var expanded by remember(box.id) { mutableStateOf(false) }
    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onDone) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Text("Done") }
                TextFormatButton(Icons.Rounded.FormatBold, "Bold", box.bold) { onChange(box.copy(bold = !box.bold)) }
                TextFormatButton(Icons.Rounded.FormatItalic, "Italic", box.italic) { onChange(box.copy(italic = !box.italic)) }
                TextFormatButton(Icons.Rounded.FormatUnderlined, "Underline", box.underline) { onChange(box.copy(underline = !box.underline)) }
                TextFormatButton(Icons.Rounded.Remove, "Smaller text", false, box.size > TextBox.MIN_SIZE) { onChange(box.copy(size = (box.size - 2f).coerceAtLeast(TextBox.MIN_SIZE))) }
                Text("${box.size.roundToInt()} pt", style = MaterialTheme.typography.labelMedium)
                TextFormatButton(Icons.Rounded.Add, "Larger text", false, box.size < TextBox.MAX_SIZE) { onChange(box.copy(size = (box.size + 2f).coerceAtMost(TextBox.MAX_SIZE))) }
                TextFormatButton(Icons.Rounded.Tune, "More text formatting", expanded) { expanded = !expanded }
                TextFormatButton(Icons.Rounded.ContentCopy, "Duplicate text box", false, box.text.isNotBlank(), onDuplicate)
                TextFormatButton(Icons.Rounded.DeleteOutline, "Delete text box", false, onClick = onDelete)
            }
            if (expanded) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextFormatButton(Icons.Rounded.FormatAlignLeft, "Align left", box.align == TextAlignMode.LEFT) { onChange(box.copy(align = TextAlignMode.LEFT)) }
                    TextFormatButton(Icons.Rounded.FormatAlignCenter, "Align centre", box.align == TextAlignMode.CENTER) { onChange(box.copy(align = TextAlignMode.CENTER)) }
                    TextFormatButton(Icons.Rounded.FormatAlignRight, "Align right", box.align == TextAlignMode.RIGHT) { onChange(box.copy(align = TextAlignMode.RIGHT)) }
                    colors.forEach { color -> InkColorDot(color, color == box.color, { onChange(box.copy(color = color)) }, touch = 40.dp, dot = 24.dp, label = "Text colour") }
                }
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Opacity", style = MaterialTheme.typography.labelMedium)
                    Slider(box.opacity, { onChange(box.withOpacity(it)) }, valueRange = TextBox.MIN_OPACITY..TextBox.MAX_OPACITY,
                        modifier = Modifier.weight(1f).semanticsLabel("Text opacity"))
                    Text("${(box.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable private fun TextFormatButton(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(40.dp), enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)) {
        Icon(icon, label, Modifier.size(20.dp))
    }
}
