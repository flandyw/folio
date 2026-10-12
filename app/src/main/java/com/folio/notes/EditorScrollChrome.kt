@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal fun visibleCurrentPage(pages: LazyListState, pageCount: Int): Int? {
    val info = pages.layoutInfo
    return DocumentViewport.currentPage(
        info.visibleItemsInfo.map { DocumentViewport.VisiblePage(it.index, it.offset, it.size) },
        info.viewportStartOffset, info.viewportEndOffset, pageCount
    )
}

internal const val FastScrollChipHoldMs = 900L
/** Touch zone along the right edge, and the shortest the thumb gets, so the handle is easy to land on. */
internal val FastScrollStripWidth = 48.dp
internal val FastScrollMinThumb = 44.dp

internal data class FastScrollGeometry(val top: Float, val height: Float)

/** Shared geometry keeps the touch target aligned with the visible thumb. */
internal fun fastScrollGeometry(pages: LazyListState, pageCount: Int, height: Float, minimumThumb: Float): FastScrollGeometry? {
    val info = pages.layoutInfo
    if (pageCount <= 0 || height <= 0f || (!pages.canScrollBackward && !pages.canScrollForward)) return null
    // The LazyColumn holds one extra trailing item (the Add page button), so clamp to real
    // pages and count only pages: otherwise progress and thumb size drift off by one.
    val firstPageIndex = pages.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
    val firstPageSize = info.visibleItemsInfo.firstOrNull { it.index < pageCount }?.size
        ?: info.visibleItemsInfo.firstOrNull()?.size ?: 0
    val progress = if (!pages.canScrollForward) 1f else DocumentViewport.scrollProgress(firstPageIndex, pages.firstVisibleItemScrollOffset, firstPageSize, pageCount)
    val visiblePages = info.visibleItemsInfo.count { it.index < pageCount }.coerceAtLeast(1)
    val share = DocumentViewport.thumbFraction(visiblePages, pageCount)
    val thumb = (height * share).coerceAtLeast(minimumThumb).coerceAtMost(height)
    return FastScrollGeometry((height - thumb) * progress, thumb)
}

/** The page count follows the thumb without making the label a touch target. */
@Composable internal fun FastScrollTrack(pages: LazyListState, pageCount: Int, scrubbing: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // The chip is a transient affordance: it fades in for any scroll or thumb drag and
    // fades out again once the document settles, so it never clutters a still page.
    var chipActive by remember { mutableStateOf(false) }
    LaunchedEffect(pages, scrubbing) {
        snapshotFlow { pages.isScrollInProgress || scrubbing }.collectLatest { active ->
            if (active) chipActive = true else {
                delay(FastScrollChipHoldMs)
                chipActive = false
            }
        }
    }
    val chipAlpha by animateFloatAsState(if (chipActive) 1f else 0f,
        animationSpec = tween(if (chipActive) 140 else 220), label = "fastScrollChipAlpha")
    // The thumb brightens and thickens smoothly when grabbed instead of snapping.
    val thumbAlpha by animateFloatAsState(if (scrubbing) 1f else .7f, label = "fastScrollAlpha")
    val thumbWidth by animateDpAsState(if (scrubbing) 10.dp else 6.dp, animationSpec = folioSpring(), label = "fastScrollWidth")
    BoxWithConstraints(modifier) {
        val geometry = fastScrollGeometry(pages, pageCount, constraints.maxHeight.toFloat(), with(density) { FastScrollMinThumb.toPx() })
            ?: return@BoxWithConstraints
        Box(Modifier.align(Alignment.CenterEnd).width(FastScrollStripWidth).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxHeight().width(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .22f)))
            Box(Modifier.offset { IntOffset(0, geometry.top.roundToInt()) }.width(thumbWidth).height(with(density) { geometry.height.toDp() }).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = thumbAlpha)))
        }
        if (chipAlpha < 0.01f) return@BoxWithConstraints
        val labelHeightPx = with(density) { 32.dp.toPx() }
        val labelTop = (geometry.top + geometry.height / 2 - labelHeightPx / 2)
            .coerceIn(0f, (constraints.maxHeight - labelHeightPx).coerceAtLeast(0f))
        Surface(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, labelTop.roundToInt()) }.padding(end = FolioSpacing.dp32)
            .graphicsLayer {
                alpha = chipAlpha
                translationX = (1f - chipAlpha) * 8.dp.toPx()
                scaleX = .94f + .06f * chipAlpha
                scaleY = scaleX
            },
            shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = if (scrubbing) 6.dp else 2.dp, tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
            val current = visibleCurrentPage(pages, pageCount) ?: pages.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
            Text("${current + 1} / $pageCount",
                Modifier.padding(horizontal = FolioSpacing.dp10, vertical = FolioSpacing.dp8), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
        }
    }
}

