package com.droidlink.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.droidlink.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The logo's entrance, picking up where Android's launch screen (the same logo on the same
 * backdrop) leaves off: it settles with a little nod, sends out a violet ring, then lifts
 * away to reveal the app.
 */
@Composable
fun LaunchSplash() {
    // Every time the app is opened, but saved across rotation so turning the phone doesn't replay it.
    var visible by rememberSaveable { mutableStateOf(true) }
    if (!visible) return
    // 1.25 matches the logo's size on Android's launch screen.
    val logo = remember { Animatable(1.25f) }
    val nod = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch { logo.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
        delay(250)
        launch {
            nod.animateTo(0f, keyframes {
                durationMillis = 600
                -14f at 150
                9f at 320
            })
        }
        launch { ring.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        delay(800)
        launch { logo.animateTo(1.3f, tween(350)) }
        fade.animateTo(0f, tween(350))
        visible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Brush.verticalGradient(listOf(Color(0xFF170B2E), Color(0xFF041E33))))
            // Swallow taps until the app shows.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(110.dp)
                .graphicsLayer {
                    val scale = 0.8f + ring.value * 1.6f
                    scaleX = scale
                    scaleY = scale
                    alpha = if (ring.value == 0f) 0f else (1f - ring.value) * 0.9f
                }
                .border(2.dp, Color(0xFF895AFF), CircleShape)
        )
        Image(
            painterResource(R.drawable.ic_logo), contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = logo.value
                    scaleY = logo.value
                    rotationZ = nod.value
                }
        )
    }
}
