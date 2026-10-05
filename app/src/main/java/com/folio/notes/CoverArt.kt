package com.folio.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val InkOnLight = Color(0xFF343931)
private val InkOnDark = Color(0xFFF6F1E7)

/**
 * Every tone a cover design uses, derived from the one cover colour. Ink flips between a deep olive
 * and warm paper by luminance, so a pale pastel and a dark custom colour both keep a readable title.
 */
@Immutable
private class CoverTones(val base: Color) {
    private val onLight = base.luminance() > .3f
    val ink: Color = if (onLight) InkOnLight else InkOnDark
    val quiet: Color = ink.copy(alpha = .7f)
    val line: Color = ink.copy(alpha = .2f)
    val faint: Color = ink.copy(alpha = .1f)
    val spine: Color = lerp(base, ink, .14f)
    val deep: Color = lerp(base, ink, .34f)
    val lift: Color = if (onLight) Color.White.copy(alpha = .42f) else Color.White.copy(alpha = .12f)
}

/**
 * The decorative notebook cover: a coloured board with one of the [CoverStyle] designs and the
 * title set on it. [cover] is the packed colour + design int a notebook stores. [showText] is off for
 * the small design tiles in the picker, which only need the artwork.
 */
@Composable fun CoverFace(
    title: String,
    document: Boolean,
    cover: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showText: Boolean = true,
) {
    val colors = coverColors(rememberCustomCoverColors())
    val base = colors[CoverStyle.colorIndex(cover).mod(colors.size)]
    val style = CoverStyle.styleOf(cover)
    val tones = remember(base) { CoverTones(base) }
    val kicker = if (document) "DOCUMENT" else "NOTEBOOK"
    BoxWithConstraints(modifier.clip(RoundedCornerShape(FolioShapes.smallRadius)).background(base)) {
        Canvas(Modifier.fillMaxSize()) { drawCoverArt(style, tones) }
        if (showText) CoverText(style, title, kicker, tones, compact, maxWidth, maxHeight)
    }
}

// ---- Artwork ------------------------------------------------------------------------------------

private fun DrawScope.drawCoverArt(style: CoverStyle, t: CoverTones) {
    when (style) {
        CoverStyle.FOLIO -> folioArt(t)
        CoverStyle.HORIZON -> horizonArt(t)
        CoverStyle.BLUEPRINT -> blueprintArt(t)
        CoverStyle.BAND -> bandArt(t)
        CoverStyle.ARCH -> archArt(t)
        CoverStyle.DOTTED -> dottedArt(t)
        CoverStyle.BLOCK -> Unit
        CoverStyle.RULED -> ruledArt(t)
    }
}

/** Cloth-bound book: stitched spine, an inset frame for the label plate and a bookmark ribbon. */
private fun DrawScope.folioArt(t: CoverTones) {
    val w = size.width; val h = size.height
    drawRect(t.spine, size = Size(w * .11f, h))
    drawLine(Color.White.copy(alpha = .3f), Offset(w * .115f, 0f), Offset(w * .115f, h), 1.5.dp.toPx())
    drawLine(t.ink.copy(alpha = .35f), Offset(w * .055f, h * .05f), Offset(w * .055f, h * .95f), 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 6.dp.toPx())))
    drawRoundRect(t.line, Offset(w * .15f, h * .045f), Size(w * .79f, h * .91f), CornerRadius(3.dp.toPx()), Stroke(1.dp.toPx()))
    val x = w * .86f; val rw = w * .07f; val bottom = h * .2f
    drawPath(Path().apply {
        moveTo(x, 0f); lineTo(x + rw, 0f); lineTo(x + rw, bottom); lineTo(x + rw / 2, bottom - rw * .6f); lineTo(x, bottom); close()
    }, t.deep)
}

/** Soft sun over layered hills. */
private fun DrawScope.horizonArt(t: CoverTones) {
    val w = size.width; val h = size.height
    drawCircle(t.lift, w * .17f, Offset(w * .7f, h * .5f))
    fun hill(y: Float, amp: Float, color: Color) = drawPath(Path().apply {
        moveTo(0f, y); cubicTo(w * .3f, y - amp, w * .6f, y + amp, w, y - amp * .35f)
        lineTo(w, h); lineTo(0f, h); close()
    }, color)
    hill(h * .6f, h * .07f, t.faint)
    hill(h * .72f, h * .06f, t.line)
    hill(h * .85f, h * .05f, t.deep.copy(alpha = .55f))
}

/** Drafting paper: a fine grid with heavier lines every fourth cell and a ruled border. */
private fun DrawScope.blueprintArt(t: CoverTones) {
    val w = size.width; val h = size.height
    val step = w / 8f
    var i = 0
    var x = 0f
    while (x <= w) { drawLine(if (i % 4 == 0) t.line else t.faint, Offset(x, 0f), Offset(x, h), .8.dp.toPx()); x += step; i++ }
    i = 0
    var y = 0f
    while (y <= h) { drawLine(if (i % 4 == 0) t.line else t.faint, Offset(0f, y), Offset(w, y), .8.dp.toPx()); y += step; i++ }
    drawRect(t.quiet, Offset(w * .045f, w * .045f), Size(w * .91f, h - w * .09f), style = Stroke(1.dp.toPx()))
}

/** A bold inverse band across the middle (the title sits in it) between two fine rules. */
private fun DrawScope.bandArt(t: CoverTones) {
    val w = size.width; val h = size.height
    drawLine(t.line, Offset(w * .08f, h * .09f), Offset(w * .92f, h * .09f), 1.dp.toPx())
    drawLine(t.line, Offset(w * .08f, h * .12f), Offset(w * .92f, h * .12f), 1.dp.toPx())
    drawLine(t.line, Offset(w * .08f, h * .91f), Offset(w * .92f, h * .91f), 1.dp.toPx())
}

/** A doorway: a lifted arch holding a sun, with a stepped inner arch. */
private fun DrawScope.archArt(t: CoverTones) {
    val w = size.width; val h = size.height
    drawRoundRect(t.lift, Offset(w * .17f, h * .42f), Size(w * .66f, h), CornerRadius(w * .33f))
    drawRoundRect(t.line, Offset(w * .25f, h * .5f), Size(w * .5f, h), CornerRadius(w * .25f), Stroke(1.2.dp.toPx()))
    drawCircle(t.deep.copy(alpha = .5f), w * .1f, Offset(w * .5f, h * .66f))
}

/** A dotted-paper field that fades out upward, so the title area stays clean. */
private fun DrawScope.dottedArt(t: CoverTones) {
    val w = size.width; val h = size.height
    val step = w / 9f
    val r = 1.4.dp.toPx()
    var y = h * .4f
    while (y <= h - step * .4f) {
        val fade = ((y - h * .4f) / (h * .6f)).coerceIn(0f, 1f)
        var x = step * .6f
        while (x <= w) { drawCircle(t.ink.copy(alpha = .08f + .22f * fade), r, Offset(x, y)); x += step }
        y += step
    }
}

/** Lined paper with a margin rule and three binder holes. */
private fun DrawScope.ruledArt(t: CoverTones) {
    val w = size.width; val h = size.height
    val step = h / 14f
    var y = step * 2.5f
    while (y < h) { drawLine(t.faint.copy(alpha = .16f), Offset(0f, y), Offset(w, y), 1.dp.toPx()); y += step }
    drawLine(Color(0xFFB3261E).copy(alpha = .3f), Offset(w * .2f, 0f), Offset(w * .2f, h), 1.dp.toPx())
    for (k in 1..3) drawCircle(t.deep.copy(alpha = .45f), w * .024f, Offset(w * .085f, h * k / 4f))
}

// ---- Type ---------------------------------------------------------------------------------------

@Composable private fun CoverTitle(title: String, kicker: String, color: Color, compact: Boolean, modifier: Modifier = Modifier, plate: Boolean = false) {
    val size = (if (compact) 18 else 23) - if (plate) 2 else 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
        Text(kicker, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, letterSpacing = 2.sp, color = color.copy(alpha = .7f))
        Text(title, fontFamily = FontFamily.Serif, fontSize = size.sp, lineHeight = (size + 5).sp, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis, color = color)
    }
}

@Composable private fun Monogram(color: Color, modifier: Modifier = Modifier) {
    Text("f.", modifier, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 23.sp, color = color.copy(alpha = .7f))
}

@Composable private fun CoverText(style: CoverStyle, title: String, kicker: String, t: CoverTones, compact: Boolean, w: Dp, h: Dp) {
    val pad = if (compact) 14.dp else 24.dp
    when (style) {
        CoverStyle.FOLIO -> Column(
            Modifier.fillMaxSize().padding(start = w * .19f, top = h * .07f, end = w * .16f, bottom = h * .07f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            CoverTitle(title, kicker, t.ink, compact, Modifier.fillMaxWidth().background(t.lift, RoundedCornerShape(2.dp)).padding(if (compact) 8.dp else 12.dp), plate = true)
            if (!compact) Monogram(t.ink, Modifier.padding(start = 4.dp))
        }
        CoverStyle.HORIZON, CoverStyle.ARCH, CoverStyle.DOTTED ->
            CoverTitle(title, kicker, t.ink, compact, Modifier.fillMaxWidth().padding(start = pad, top = pad, end = pad))
        CoverStyle.BLUEPRINT -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            CoverTitle(title, kicker, t.ink, compact, Modifier.fillMaxWidth().padding(start = w * .09f, end = w * .09f, bottom = w * .09f).background(t.base, RoundedCornerShape(2.dp)).padding(if (compact) 10.dp else 14.dp))
        }
        CoverStyle.BAND -> Column(Modifier.fillMaxSize().padding(vertical = h * .16f), verticalArrangement = Arrangement.SpaceBetween) {
            Text(kicker, Modifier.padding(horizontal = pad), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, letterSpacing = 2.sp, color = t.quiet)
            Text(
                title, Modifier.fillMaxWidth().background(t.ink).padding(horizontal = pad, vertical = 12.dp),
                fontFamily = FontFamily.Serif, fontSize = (if (compact) 18 else 23).sp, lineHeight = (if (compact) 22 else 28).sp,
                maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis, color = t.base,
            )
            if (!compact) Monogram(t.ink, Modifier.padding(horizontal = pad)) else Spacer(Modifier)
        }
        CoverStyle.BLOCK -> Box(Modifier.fillMaxSize()) {
            // The title's own initial, set huge and faint behind the title.
            val initial = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "f"
            Text(initial, Modifier.align(Alignment.BottomEnd).offset(x = w * .08f, y = w * .16f), fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                fontSize = (w.value * 1.15f).sp, lineHeight = (w.value * 1.15f).sp, color = t.ink.copy(alpha = .14f), softWrap = false, maxLines = 1)
            CoverTitle(title, kicker, t.ink, compact, Modifier.fillMaxWidth().padding(start = pad, top = pad, end = pad))
        }
        CoverStyle.RULED -> CoverTitle(title, kicker, t.ink, compact, Modifier.fillMaxWidth().padding(start = w * .26f, top = h * .09f, end = pad))
    }
}
