package com.folio.notes.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.sin

/**
 * The cold-start splash: a handful of soap bubbles pop out around the Folio tile, the tile
 * boings in and the name bounces in letter by letter, then the whole thing fades into the app.
 *
 * It is drawn *inside* [FolioTheme], so it wears the user's mode, palette, accent and AMOLED
 * choice exactly as the app will (the system splash before it can only know the light/dark
 * setting of the device, which is why that one is left blank). Roughly a second end to end; a tap
 * skips the wait, and with system animations turned off it never appears at all.
 */
@Composable internal fun FolioSplash(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val finished by rememberUpdatedState(onFinished)
    if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
        LaunchedEffect(Unit) { finished() }
        return
    }
    val colors = MaterialTheme.colorScheme
    val tones = remember(colors) {
        listOf(colors.primary, colors.secondary, colors.tertiary, colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer)
    }
    val glyph = remember { SplashGlyph.paths() }

    // Squash and stretch come from two springs with different stiffness driving the two axes.
    val tileX = remember { Animatable(0f) }
    val tileY = remember { Animatable(0f) }
    val tileTilt = remember { Animatable(-14f) }
    val pops = remember { SplashBubble.all.map { Animatable(0f) } }
    val drift = remember { Animatable(0f) }
    val letters = remember { SplashGlyph.WORD.map { Animatable(0f) } }
    val exit = remember { Animatable(0f) }
    val skip = remember { CompletableDeferred<Unit>() }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { tileX.animateTo(1f, spring(dampingRatio = .36f, stiffness = 360f)) }
            launch { delay(40); tileY.animateTo(1f, spring(dampingRatio = .3f, stiffness = 300f)) }
            launch { delay(40); tileTilt.animateTo(0f, spring(dampingRatio = .35f, stiffness = 240f)) }
            launch { drift.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing)) }
            pops.forEachIndexed { index, pop ->
                launch { delay(70L + index * 32L); pop.animateTo(1f, spring(dampingRatio = .42f, stiffness = 260f)) }
            }
            letters.forEachIndexed { index, letter ->
                launch { delay(300L + index * 55L); letter.animateTo(1f, spring(dampingRatio = .4f, stiffness = 420f)) }
            }
            launch {
                withTimeoutOrNull(HOLD_MS) { skip.await() }
                exit.animateTo(1f, tween(EXIT_MS, easing = FastOutLinearInEasing))
                finished()
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // Everything fades together so the app underneath is revealed, not cut to.
            .graphicsLayer { alpha = 1f - exit.value }
            .background(colors.background)
            .pointerInput(Unit) { detectTapGestures { skip.complete(Unit) } }
            .semantics { contentDescription = "Folio" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.graphicsLayer { scaleX = 1f + .1f * exit.value; scaleY = 1f + .1f * exit.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(TILE_DP.dp)
                .drawBehind {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    SplashBubble.all.forEachIndexed { index, bubble ->
                        val pop = pops[index].value
                        if (pop <= 0f) return@forEachIndexed
                        val angle = Math.toRadians(bubble.angle.toDouble())
                        // Past the rest ring while overshooting, then a lazy float outwards and up.
                        val reach = (bubble.distance * (.45f + .55f * pop) + bubble.float * drift.value).dp.toPx()
                        val rise = (RISE_DP * drift.value * bubble.lift).dp.toPx()
                        val at = Offset(centre.x + cos(angle).toFloat() * reach, centre.y + sin(angle).toFloat() * reach - rise)
                        val radius = bubble.radius.dp.toPx() * pop
                        val tone = tones[bubble.tone % tones.size]
                        val visible = pop.coerceIn(0f, 1f)
                        drawCircle(tone.copy(alpha = .26f * visible), radius, at)
                        drawCircle(tone.copy(alpha = .9f * visible), radius, at, style = Stroke(width = 1.6.dp.toPx()))
                        // The little shine that makes it a bubble rather than a dot.
                        drawCircle(Color.White.copy(alpha = .6f * visible), radius * .17f,
                            Offset(at.x - radius * .38f, at.y - radius * .4f))
                    }
                }
                .graphicsLayer {
                    scaleX = tileX.value; scaleY = tileY.value; rotationZ = tileTilt.value
                }
                .drawBehind {
                    val k = size.minDimension / SplashGlyph.VIEWPORT
                    withTransform({ scale(k, k, Offset.Zero) }) {
                        drawRoundRect(colors.primary, size = Size(SplashGlyph.VIEWPORT, SplashGlyph.VIEWPORT),
                            cornerRadius = CornerRadius(SplashGlyph.CORNER, SplashGlyph.CORNER))
                        drawPath(glyph.letter, colors.onPrimary)
                        drawPath(glyph.dot, colors.primaryContainer)
                    }
                })
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                SplashGlyph.WORD.forEachIndexed { index, char ->
                    Text(char.toString(),
                        Modifier.graphicsLayer {
                            val v = letters[index].value
                            alpha = v.coerceIn(0f, 1f)
                            translationY = (1f - v) * 26.dp.toPx()
                            // Letters land a touch squashed, like they hit the ground.
                            scaleY = 1f - .18f * (1f - v).coerceIn(0f, 1f)
                        },
                        color = colors.onBackground, fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold, fontSize = 38.sp)
                }
            }
        }
    }
}

private const val TILE_DP = 124
private const val RISE_DP = 34f
private const val HOLD_MS = 760L
private const val EXIT_MS = 240

/** One bubble: where it sits around the tile (degrees, dp from centre), its size and which theme tone it wears. */
private class SplashBubble(val angle: Float, val distance: Float, val radius: Float, val tone: Int, val float: Float, val lift: Float) {
    companion object {
        val all = listOf(
            SplashBubble(-152f, 92f, 14f, 0, 20f, 1.0f), SplashBubble(-112f, 100f, 8f, 1, 26f, 1.3f),
            SplashBubble(-64f, 94f, 19f, 2, 18f, 1.1f), SplashBubble(-22f, 106f, 9f, 3, 24f, 0.8f),
            SplashBubble(22f, 98f, 15f, 4, 22f, 1.2f), SplashBubble(66f, 90f, 8f, 5, 28f, 0.9f),
            SplashBubble(114f, 104f, 18f, 0, 16f, 1.0f), SplashBubble(160f, 94f, 10f, 2, 24f, 1.4f),
            SplashBubble(-88f, 132f, 6f, 4, 30f, 1.5f), SplashBubble(4f, 138f, 7f, 1, 26f, 1.0f),
            SplashBubble(138f, 136f, 5f, 3, 30f, 0.7f),
        )
    }
}

/** The launcher glyph (`ic_folio`) in its own 108-unit space, so the splash tile is the app's icon. */
private object SplashGlyph {
    const val VIEWPORT = 108f
    const val CORNER = 24f
    const val WORD = "Folio"
    class Paths(val letter: androidx.compose.ui.graphics.Path, val dot: androidx.compose.ui.graphics.Path)
    fun paths() = Paths(
        PathParser().parsePathString("M32,26 L78,26 L78,38 L46,38 L46,49 L71,49 L71,61 L46,61 L46,82 L32,82 Z").toPath(),
        PathParser().parsePathString("M67,69 L79,69 L79,81 L67,81 Z").toPath(),
    )
}
