package com.droidlink.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidlink.app.R
import kotlinx.coroutines.delay

private sealed interface Page {
    data object Welcome : Page
    data object Pair : Page
    data class Step(val index: Int) : Page
    data object Done : Page
}

/**
 * First run: a welcome, pairing, then one setup step per page. Each page moves on by itself
 * once its step is done (coming back from Android's settings), and every step can be
 * skipped; skipped ones stay in the Setup list on the home screen.
 */
@Composable
internal fun Onboarding(paired: Boolean, macName: String?, steps: List<SetupStep>, onPair: () -> Unit, onFinish: () -> Unit) {
    val pages = listOf(Page.Welcome, Page.Pair) + steps.indices.map { Page.Step(it) } + Page.Done
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    var forward by rememberSaveable { mutableIntStateOf(1) }
    fun go(to: Int) {
        forward = if (to > index) 1 else -1
        index = to.coerceIn(0, pages.lastIndex)
    }

    BackHandler(enabled = index > 0) { go(index - 1) }

    // Move on once the page's step is done: straight away if it already was, after a beat
    // (to see the check) if it just happened.
    val pageDone = when (page) {
        Page.Pair -> paired
        is Page.Step -> steps[page.index].done
        else -> false
    }
    LaunchedEffect(index, pageDone) {
        if (pageDone) {
            delay(900)
            go(index + 1)
        }
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Progress through pairing and the steps.
            val stepCount = pages.size - 2
            val position = index.coerceIn(0, stepCount)
            val progress by animateFloatAsState(position / stepCount.toFloat(), tween(500), label = "progress")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (page == Page.Welcome) 0f else 0.08f))
            ) {
                if (page != Page.Welcome) Box(Modifier.fillMaxWidth(progress).height(5.dp).clip(CircleShape).background(AccentBrush))
            }

            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    (slideInHorizontally { it * forward / 3 } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally { -it * forward / 3 } + fadeOut(tween(200)))
                },
                modifier = Modifier.weight(1f),
                label = "page"
            ) { shown ->
                when (val p = pages[shown.coerceIn(0, pages.lastIndex)]) {
                    Page.Welcome -> PageLayout(
                        art = { Image(painterResource(R.drawable.ic_logo), null, Modifier.size(88.dp)) },
                        title = "Your phone, on your Mac",
                        body = "Notifications, calls, texts, your clipboard and files: all on your Mac, over your own Wi-Fi. No account, no cloud.",
                        extra = {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                                Feature(Icons.Filled.Notifications, "Notifications and calls, with reply and answer")
                                Feature(Icons.Filled.Sms, "Texts and contact names")
                                Feature(Icons.Filled.ContentPaste, "Clipboard and files, both ways")
                            }
                        },
                        primary = "Get started" to { go(shown + 1) },
                    )
                    Page.Pair -> PageLayout(
                        art = { StepIcon(Icons.Filled.QrCodeScanner, paired) },
                        title = if (paired) "Paired with ${macName ?: "your Mac"}" else "Pair with your Mac",
                        body = "On your Mac, open AyPhone and go to Pairing. Then scan the QR code shown there. You only do this once.",
                        primary = if (paired) null else ("Scan QR code" to onPair),
                    )
                    is Page.Step -> {
                        val step = steps[p.index]
                        PageLayout(
                            art = { StepIcon(step.icon, step.done) },
                            title = step.title,
                            body = step.subtitle,
                            caption = if (step.optional) "Optional" else "Step ${p.index + 1} of ${steps.size}",
                            primary = if (step.done) null else ("Allow" to step.action),
                            secondary = if (step.done) null else ((if (step.optional) "Skip" else "Skip for now") to { go(shown + 1) }),
                        )
                    }
                    Page.Done -> PageLayout(
                        art = { StepIcon(Icons.Filled.Check, true) },
                        title = "You're all set",
                        body = "AyPhone keeps your phone and Mac connected in the background. Anything you skipped is under Setup on the home screen.",
                        primary = "Open AyPhone" to onFinish,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageLayout(
    art: @Composable () -> Unit,
    title: String,
    body: String,
    caption: String? = null,
    extra: (@Composable () -> Unit)? = null,
    primary: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(150.dp).clip(CircleShape).background(Glow.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { art() }
            Spacer(Modifier.height(28.dp))
            caption?.let {
                Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = Glow, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Secondary, textAlign = TextAlign.Center)
            extra?.invoke()
        }
        primary?.let { (text, action) -> PrimaryButton(text, null, onClick = action) }
        secondary?.let { (text, action) ->
            TextButton(onClick = action, modifier = Modifier.padding(top = 4.dp)) { Text(text, color = Secondary) }
        } ?: Spacer(Modifier.height(if (primary == null) 0.dp else 52.dp))
    }
}

/** The page's icon, which pops into a check once its step is done. */
@Composable
private fun StepIcon(icon: ImageVector, done: Boolean) {
    AnimatedContent(
        targetState = done,
        transitionSpec = {
            (scaleIn(spring(Spring.DampingRatioMediumBouncy), initialScale = 0.3f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut())
        },
        label = "stepIcon"
    ) { isDone ->
        if (isDone) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color(0xFF0B2A17), modifier = Modifier.size(50.dp))
            }
        } else {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Indigo.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Glow, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
