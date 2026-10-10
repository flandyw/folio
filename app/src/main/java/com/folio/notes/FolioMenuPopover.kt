package com.folio.notes

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Shared chrome for anchored palettes and menus, including menus opened inside another popup. */
@Composable internal fun FolioPopoverSurface(
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.guardUiTouches().graphicsLayer {
            alpha = progress.coerceIn(0f, 1f)
            scaleX = .94f + .06f * progress
            scaleY = scaleX
            transformOrigin = TransformOrigin(.5f, 0f)
        },
        shape = FolioShapes.extraLarge,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .65f)),
        shadowElevation = 16.dp,
    ) {
        Box(Modifier.background(Brush.verticalGradient(listOf(scheme.surfaceContainerLow, scheme.surfaceContainer)))) {
            content()
        }
    }
}

/** Place inside the trigger's Box. The card flips above it and stays inside the current window. */
@Composable internal fun FolioMenuPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    val transition = updateTransition(state, label = "Menu popover")
    val opacity by transition.animateFloat(transitionSpec = { tween(120) }, label = "Opacity") { if (it) 1f else 0f }
    val scale by transition.animateFloat(transitionSpec = { if (targetState) folioSpring() else tween(90) }, label = "Scale") { if (it) 1f else 0f }
    if (!state.currentState && !state.targetState) return
    val density = LocalDensity.current
    val provider = remember(density) { with(density) { PopoverPositionProvider(FolioSpacing.dp12.roundToPx(), FolioSpacing.dp8.roundToPx()) } }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        // Popup constraints follow split screen, rotation and the visible keyboard, unlike display metrics.
        BoxWithConstraints {
            val focusManager = LocalFocusManager.current
            FolioPopoverSurface(
                modifier.width(minOf(320.dp, (maxWidth - 24.dp).coerceAtLeast(0.dp)))
                    .heightIn(max = (maxHeight - FolioSpacing.dp24).coerceAtLeast(0.dp))
                    .graphicsLayer { alpha = opacity }
                    .semantics { paneTitle = title },
                progress = scale,
            ) {
                Column(Modifier.padding(FolioSpacing.dp8)) {
                    Text(title, Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp10).semantics { heading() },
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp4),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).focusGroup()
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) false
                            else when (it.key) {
                                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                                else -> false
                            }
                        },
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2), content = content)
                }
            }
        }
    }
}

/** Slot-based action row, so rich labels, shortcuts and switches survive menu migration. */
@Composable internal fun FolioMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    destructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val active = selected == true
    val foreground = when {
        !enabled -> scheme.onSurface.copy(alpha = .38f)
        destructive -> scheme.error
        active -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    val interaction = if (selected != null) Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Row(
        modifier.fillMaxWidth().clip(FolioShapes.large)
            .background(if (active && enabled) scheme.secondaryContainer else Color.Transparent)
            .then(interaction).heightIn(min = 52.dp).padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            if (leadingIcon != null) {
                val tileColor = when {
                    !enabled -> scheme.surfaceContainerHighest.copy(alpha = .4f)
                    destructive -> scheme.errorContainer
                    active -> scheme.secondaryContainer
                    else -> scheme.surfaceContainerHighest
                }
                Box(Modifier.size(32.dp).background(tileColor, FolioShapes.medium), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { leadingIcon() }
                }
            }
            Box(Modifier.weight(1f)) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)) { text() }
            }
            if (active) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp))
            else if (trailingIcon != null) Box(contentAlignment = Alignment.Center) { trailingIcon() }
        }
    }
}
