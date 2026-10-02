package com.folio.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Shared spatial spring. Compose honours the system animator duration scale. */
internal fun <T> folioSpring() = spring<T>(dampingRatio = .76f, stiffness = 380f)

/** An entrance on the existing composition: never retain a second editor or input surface. */
@Composable internal fun Modifier.folioEntrance(key: Any = Unit): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, folioSpring())
    }
    return graphicsLayer {
        val value = progress.value
        alpha = value.coerceIn(0f, 1f)
        translationY = (1f - value) * 16f * density
    }
}

/** Subtle selection emphasis without changing layout or touch targets. */
@Composable internal fun Modifier.folioSelected(selected: Boolean): Modifier {
    val scale = animateFloatAsState(if (selected) 1.06f else 1f, folioSpring(), label = "selectionSpring")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

@Composable internal fun FolioExpand(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + expandVertically(animationSpec = folioSpring()),
        exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)),
    ) { content() }
}

/** Rotate disclosure icons rather than swapping their glyph halfway through a resize. */
@Composable internal fun Modifier.folioDisclosure(expanded: Boolean): Modifier {
    val angle = animateFloatAsState(if (expanded) 180f else 0f, folioSpring(), label = "disclosure")
    return graphicsLayer { rotationZ = angle.value }
}

/** Keep only the modal alive until its exit finishes; never retain a document surface. */
@Composable internal fun FolioAnimatedDialog(
    onDismissRequest: () -> Unit,
    content: @Composable (progress: State<Float>, dismiss: () -> Unit) -> Unit,
) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val transition = updateTransition(visibility, label = "modal")
    val progress = transition.animateFloat(
        transitionSpec = { tween(if (targetState) 240 else 180, easing = FastOutSlowInEasing) },
        label = "modalProgress",
    ) { if (it) 1f else 0f }
    val dismiss = { visibility.targetState = false }
    LaunchedEffect(visibility.isIdle, visibility.currentState, visibility.targetState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) latestDismiss()
    }
    Dialog(dismiss, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let {
                WindowCompat.getInsetsController(it, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        content(progress, dismiss)
    }
}
