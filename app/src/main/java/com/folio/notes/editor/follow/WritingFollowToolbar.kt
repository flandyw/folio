@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.editor.follow

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ink.InkView
import com.folio.notes.editor.PeekControl
import com.folio.notes.model.PeekMode
import com.folio.notes.ui.SubmenuItem
import com.folio.notes.input.guardUiTouches

/**
 * How much of the follow bar fits in a pane [width] wide, and whether the page navigator must sit
 * above it. Keeps the default Material shape, colors and elevation, with a shorter container;
 * secondary actions overflow in narrow companion panes instead of shrinking targets.
 */
internal class WritingFollowLayout(width: Dp, infinite: Boolean, followEnabled: Boolean) {
    val responseTextButton = infinite && width >= 420.dp
    val overviewControl = infinite && width >= 220.dp
    val inset = if (width < 180.dp) FolioSpacing.dp4 else FloatingToolbarDefaults.ScreenOffset
    // Peek has a 48dp hold target, wider than the other 40dp controls. Include it
    // and the actual outer inset when deciding whether the full group fits.
    val overflowActions = width - inset * 2 <
        40.dp * 5 + 48.dp + 8.dp + (if (responseTextButton) 120.dp else 0.dp) + (if (infinite) 40.dp else 0.dp)
    // What the follow bar actually occupies now, so the page navigator can tell whether it fits beside it.
    private val shownWidth = 8.dp + 48.dp + 40.dp + (if (responseTextButton) 120.dp else 40.dp) +
        (if (overviewControl) 40.dp else 0.dp) + (if (followEnabled && !overflowActions) 120.dp else 0.dp)
    private val navigatorWidth = 40.dp * 3 + 52.dp + 8.dp
    val navigatorAbove = width - inset * 2 < shownWidth + navigatorWidth + FolioSpacing.dp8
}

/** What the follow bar and its options menu ask the editor to do. */
internal class WritingFollowActions(
    /** Starts or finishes writing follow (a response, on a canvas). */
    val toggle: () -> Unit,
    /** Starts a new response column on a canvas. */
    val newColumn: () -> Unit,
    /** See all working, or return to it. */
    val overview: () -> Unit,
    val togglePause: () -> Unit,
    val toggleZoomPane: () -> Unit,
    /** Page follow is switched on before an answer area is picked. */
    val enablePageFollow: () -> Unit,
    val toggleAutoReturn: () -> Unit,
    val openSettings: () -> Unit,
    val pinPeek: (wholePage: Boolean) -> Unit,
    val removePeek: () -> Unit,
    /** Opens (held or latched) or closes the peek; false when it cannot open now. */
    val peek: (PeekMode?) -> Boolean,
)

private enum class FollowSub { AREAS, LINES, PEEK }

/**
 * Held, blocked and end-of-area messages show briefly above the follow controls, because
 * otherwise a still view looks broken; the full status stays in the options menu.
 */
@Composable internal fun BoxScope.WritingFollowHint(status: WritingFollowStatus, enabled: Boolean, hand: WritingHand, inset: Dp, pageId: String) {
    var hint by remember(pageId) { mutableStateOf<String?>(null) }
    LaunchedEffect(status.message, status.attention, enabled) {
        val message = status.message
        if (!status.attention || !enabled) return@LaunchedEffect
        hint = message
        try { delay(2800) } finally { if (hint == message) hint = null }
    }
    hint?.let { shown ->
        Surface(
            Modifier.align(if (hand == WritingHand.RIGHT) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(inset).padding(bottom = 44.dp + FolioSpacing.dp8).widthIn(max = 320.dp).zIndex(11f),
            shape = FolioShapes.large,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Text(shown, Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8)
                .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The floating writing-follow bar on the writing hand's side: response/follow switch, See all
 * working, line controls, the follow and peek options menu, and the peek button. [view] is the
 * canvas the line controls drive; [overview] is true while See all working is showing.
 */
@Composable internal fun BoxScope.WritingFollowToolbar(
    layout: WritingFollowLayout, hand: WritingHand, infinite: Boolean, enabled: Boolean, status: WritingFollowStatus,
    peekOpen: Boolean, overview: Boolean, zoomPaneOpen: Boolean, response: CanvasWritingSession?, hasRegion: Boolean,
    pinnedPeek: Boolean, peekAvailable: Boolean, peekMode: PeekMode?, view: InkView?, actions: WritingFollowActions,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = Modifier.align(if (hand == WritingHand.RIGHT) Alignment.BottomStart else Alignment.BottomEnd)
            .padding(layout.inset)
            .height(44.dp)
            .guardUiTouches()
            .zIndex(11f),
        contentPadding = PaddingValues(horizontal = FolioSpacing.dp4, vertical = FolioSpacing.dp2),
    ) {
        val responseLabel = if (enabled) "Finish response" else "Write a response"
        if (layout.responseTextButton) TextButton(
            onClick = actions.toggle, enabled = !peekOpen && !overview,
            // Lit like the icon controls beside it while a response is open, so its state reads at a glance.
            colors = if (enabled) ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) else ButtonDefaults.textButtonColors(),
        ) { Text(responseLabel) }
        else WritingFollowControl(
            if (infinite) Icons.Rounded.TextFields else Icons.Rounded.SwipeRight,
            if (infinite) responseLabel else if (enabled) "Writing follow on — tap to turn off" else "Turn on writing follow",
            enabled = !peekOpen && !overview, active = enabled,
            onClick = actions.toggle
        )
        if (layout.overviewControl) WritingFollowControl(
            if (!overview) Icons.Rounded.FitScreen else Icons.AutoMirrored.Rounded.ArrowBack,
            if (!overview) "See all working" else "Return to working",
            enabled = !peekOpen, active = overview, onClick = actions.overview,
        )
        if (enabled && !layout.overflowActions) {
            WritingFollowControl(Icons.AutoMirrored.Rounded.KeyboardReturn, "Next writing line",
                enabled = !peekOpen && !overview, onClick = { view?.nextWritingLine() })
            WritingFollowControl(Icons.AutoMirrored.Rounded.Undo, "Undo the last follow move",
                enabled = !peekOpen && status.canGoBack, onClick = { view?.backWritingView() })
            WritingFollowControl(
                if (status.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                if (status.paused) "Resume writing follow" else "Pause writing follow",
                enabled = !peekOpen, active = status.paused,
                onClick = actions.togglePause
            )
        }
        Box {
            var menu by remember { mutableStateOf(false) }
            var followSub by remember { mutableStateOf<FollowSub?>(null) }
            val openSub: (FollowSub) -> Unit = { followSub = if (followSub == it) null else it }
            WritingFollowControl(Icons.Rounded.Tune, "Writing follow and peek options",
                enabled = !peekOpen, onClick = { followSub = null; menu = true })
            FolioMenuPopover(menu, { menu = false; followSub = null }, modifier = Modifier.guardUiTouches(), title = "Writing & peek") {
                FolioMenuItem({ Text(if (zoomPaneOpen) "Close zoom pane" else "Open zoom pane") },
                    { actions.toggleZoomPane(); menu = false },
                    enabled = !peekOpen && !overview,
                    leadingIcon = { Icon(Icons.Rounded.ZoomIn, null) })
                if (infinite) {
                    FolioMenuItem({ Text(responseLabel) },
                        { actions.toggle(); menu = false },
                        enabled = !overview,
                        leadingIcon = { Icon(Icons.Rounded.TextFields, null) })
                    Text("For paragraphs: your first stroke sets the column's margin. Leave it off for maths, short answers and diagrams.",
                        Modifier.widthIn(max = 280.dp).padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
                        style = MaterialTheme.typography.bodySmall)
                    if (response != null) FolioMenuItem({ Text("Start a new column") },
                        { actions.newColumn(); menu = false }, enabled = !overview)
                    FolioMenuItem({ Text(if (!overview) "See all working" else "Return to working") },
                        { actions.overview(); menu = false })
                }
                if (enabled) {
                    Text(status.message,
                        Modifier.widthIn(max = 280.dp).padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SubmenuItem("Lines & indentation…", Icons.AutoMirrored.Rounded.KeyboardReturn, followSub == FollowSub.LINES, { openSub(FollowSub.LINES) }) {
                        FolioMenuItem({ Text("Previous writing line") },
                            { view?.previousWritingLine(); menu = false },
                            enabled = !overview,
                            leadingIcon = { Icon(Icons.Rounded.KeyboardArrowUp, null) },
                            trailingIcon = { Text("Alt+↑", style = MaterialTheme.typography.labelSmall) })
                        FolioMenuItem({ Text("New paragraph · leave a blank line") },
                            { view?.nextWritingLine(paragraph = true); menu = false }, enabled = !overview)
                        FolioMenuItem({ Text("Indent next lines") },
                            { view?.indentWritingLine(); menu = false }, enabled = !overview)
                        FolioMenuItem({ Text("Reset indent") },
                            { view?.indentWritingLine(reset = true); menu = false }, enabled = !overview)
                    }
                    if (layout.overflowActions) {
                        FolioMenuItem({ Text("Next writing line") },
                            { view?.nextWritingLine(); menu = false },
                            enabled = !overview,
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, null) })
                        FolioMenuItem({ Text(if (status.paused) "Resume writing follow" else "Pause writing follow") },
                            { actions.togglePause(); menu = false },
                            leadingIcon = { Icon(if (status.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null) })
                        FolioMenuItem({ Text("Undo the last follow move") },
                            { view?.backWritingView(); menu = false },
                            enabled = status.canGoBack,
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, null) })
                    }
                    HorizontalDivider()
                }
                if (!infinite) SubmenuItem("Answer areas…", Icons.Rounded.CropFree, followSub == FollowSub.AREAS, { openSub(FollowSub.AREAS) }) {
                    FolioMenuItem({ Text("Select answer area") }, { actions.enablePageFollow(); view?.selectWritingRegion(); menu = false })
                    FolioMenuItem({ Text("Detect answer areas") }, { actions.enablePageFollow(); view?.suggestWritingRegion(); menu = false })
                    if (hasRegion) FolioMenuItem({ Text("Clear answer areas") }, { view?.clearWritingRegion(); menu = false })
                }
                if (infinite && response != null) FolioMenuItem(
                    { Text("Auto-return for this response") },
                    { actions.toggleAutoReturn(); menu = false },
                    trailingIcon = { Checkbox(checked = response.automaticReturn, onCheckedChange = null) })
                FolioMenuItem({ Text("Writing follow settings…") }, { actions.openSettings(); menu = false },
                    leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                HorizontalDivider()
                SubmenuItem("Peek view…", Icons.Rounded.PushPin, followSub == FollowSub.PEEK, { openSub(FollowSub.PEEK) }) {
                    FolioMenuItem(
                        { Text(if (!pinnedPeek) "Pin this view" else "Replace with this view") },
                        { actions.pinPeek(false); menu = false },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, null) }
                    )
                    FolioMenuItem(
                        { Text("Pin this whole page") },
                        { actions.pinPeek(true); menu = false },
                        leadingIcon = { Icon(Icons.Rounded.FitScreen, null) }
                    )
                    if (pinnedPeek) FolioMenuItem(
                        { Text("Remove peek view") },
                        { actions.removePeek(); menu = false },
                        leadingIcon = { Icon(Icons.Rounded.Close, null) }
                    , destructive = true)
                }
            }
        }
        if (!peekAvailable) {
            WritingFollowControl(Icons.Rounded.PushPin, "Pin this view to peek at later",
                onClick = { actions.pinPeek(false) })
        } else {
            PeekControl(peekMode, actions.peek)
        }
    }
}
