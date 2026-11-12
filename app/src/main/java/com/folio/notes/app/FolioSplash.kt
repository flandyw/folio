package com.folio.notes.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.notes.ui.FolioLogo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The cold-start splash: the logo assembles itself. Its tile settles in as one page, the two
 * sheets behind fan out, the three lines are written across the front page and the name rises in
 * underneath; then the whole thing fades into the app.
 *
 * It is drawn *inside* [FolioTheme], so the background and the name wear the user's mode, palette
 * and AMOLED choice exactly as the app will (the system splash before it can only know the
 * light/dark setting of the device, which is why that one is left blank); only the logo keeps its
 * brand colours. About a second end to end; a tap skips the wait, and with system animations
 * turned off it never appears at all.
 */
@Composable internal fun FolioSplash(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val finished by rememberUpdatedState(onFinished)
    if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
        LaunchedEffect(Unit) { finished() }
        return
    }
    val colors = MaterialTheme.colorScheme
    val tile = remember { Animatable(0f) }
    val fan = remember { Animatable(0f) }
    val write = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val skip = remember { CompletableDeferred<Unit>() }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { tile.animateTo(1f, tween(260, easing = LinearOutSlowInEasing)) }
            // A little overshoot, so the sheets swing out and settle like real paper.
            launch { delay(140); fan.animateTo(1f, spring(dampingRatio = .55f, stiffness = 170f)) }
            launch { delay(360); write.animateTo(1f, tween(460, easing = FastOutSlowInEasing)) }
            launch { delay(420); word.animateTo(1f, tween(380, easing = LinearOutSlowInEasing)) }
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
            Modifier.graphicsLayer { scaleX = 1f + .06f * exit.value; scaleY = 1f + .06f * exit.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FolioLogo(
                Modifier.size(TILE_DP.dp).graphicsLayer {
                    alpha = tile.value
                    scaleX = .88f + .12f * tile.value; scaleY = scaleX
                },
                fan = { fan.value }, write = { write.value },
            )
            Spacer(Modifier.height(24.dp))
            Text("Folio",
                Modifier.graphicsLayer { alpha = word.value; translationY = (1f - word.value) * 10.dp.toPx() },
                color = colors.onBackground, fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = .5.sp)
        }
    }
}

private const val TILE_DP = 132
private const val HOLD_MS = 1040L
private const val EXIT_MS = 240
