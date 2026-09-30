package com.folio.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Shared spatial spring. Compose honours the system animator duration scale. */
internal fun <T> folioSpring() = spring<T>(dampingRatio = .76f, stiffness = 380f)

/** An entrance on the existing composition: never retain a second editor or input surface. */
@Composable internal fun Modifier.folioEntrance(key: Any = Unit): Modifier {
    val progress = remember { Animatable(1f) }
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
        enter = expandVertically(animationSpec = folioSpring()),
        exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)),
    ) { content() }
}
