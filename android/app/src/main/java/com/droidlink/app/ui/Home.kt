package com.droidlink.app.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidlink.app.R
import com.droidlink.app.feature.ActivityLog
import com.droidlink.app.feature.MacControl
import com.droidlink.app.feature.NotificationPause
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import com.droidlink.app.service.MirrorService
import kotlinx.coroutines.delay

internal class HomeActions(
    val pair: () -> Unit,
    val sendClipboard: () -> Unit,
    val sendFiles: () -> Unit,
    val reconnect: () -> Unit,
    val unpair: () -> Unit,
)

/** The home screen once onboarding is done. */
@Composable
internal fun Home(paired: Boolean, steps: List<SetupStep>, actions: HomeActions) {
    val state by Link.state.collectAsStateWithLifecycle()
    val macName by Link.macName.collectAsStateWithLifecycle()
    val macModel by Link.macModel.collectAsStateWithLifecycle()
    val macBattery by Link.macBattery.collectAsStateWithLifecycle()
    val transport by Link.transport.collectAsStateWithLifecycle()
    val connected = state == Link.State.Connected

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(38.dp))
                Spacer(Modifier.width(10.dp))
                Text("AyPhone", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }

            Hero(state, paired, macName, macModel, macBattery, transport, actions.pair)

            if (paired) {
                SectionTitle("Quick actions")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionTile("Clipboard", "Push to Mac", Icons.Filled.ContentPaste, Modifier.weight(1f), connected, actions.sendClipboard)
                    ActionTile("Files", "Send to Mac · Browse", Icons.Filled.UploadFile, Modifier.weight(1f), connected, actions.sendFiles)
                }
            }

            SectionTitle("Setup")
            SetupSection(steps)

            val activity by ActivityLog.entries.collectAsStateWithLifecycle()
            if (paired && activity.isNotEmpty()) {
                SectionTitle("Activity")
                ActivityCard(activity)
            }

            AnimatedVisibility(paired && connected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("Your Mac")
                    MacRemoteCard()
                }
            }

            if (paired) {
                SectionTitle("Quick toggles")
                QuickToggles(connected)
            }

            AnimatedVisibility(paired) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = actions.reconnect) { Text("Reconnect") }
                    TextButton(onClick = actions.unpair) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
                }
            }
            Text(
                "Clipboard: copy here, then press ⌘V on your Mac to paste it (keep the phone unlocked). Copies on the Mac arrive here automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f)
            )
        }
    }
}

// MARK: Hero

private enum class HeroMode { Connected, Searching, Disconnected, Unpaired }

/**
 * The Mac, as the Mac app shows the phone: a big laptop in the middle with the Mac's battery
 * as a ring around it, then its name, model and status pills. A search while looking for it,
 * and the first step when this phone isn't paired yet.
 */
@Composable
private fun Hero(
    state: Link.State, paired: Boolean, macName: String?, macModel: String?, battery: Link.MacBattery?,
    transport: String?, onPair: () -> Unit
) {
    val mode = when {
        !paired || state == Link.State.Unpaired -> HeroMode.Unpaired
        state == Link.State.Connected -> HeroMode.Connected
        state == Link.State.Connecting -> HeroMode.Searching
        else -> HeroMode.Disconnected
    }
    // The laptop gives a little nod each time the Mac connects.
    val nod = remember { Animatable(0f) }
    LaunchedEffect(mode) {
        if (mode == HeroMode.Connected) nod.animateTo(0f, keyframes {
            durationMillis = 600
            -14f at 150
            9f at 320
        })
    }
    val motion = rememberInfiniteTransition(label = "hero")
    val ring by motion.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "ring"
    )
    // The magnifier circles while searching, like it's looking around.
    val orbit by motion.animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "orbit"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(listOf(Indigo.copy(alpha = 0.22f), DeepBlue.copy(alpha = 0.25f))))
            .background(GlassFill)
            .border(1.dp, GlassEdge, RoundedCornerShape(30.dp))
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            if (mode == HeroMode.Connected || mode == HeroMode.Searching) {
                Box(
                    Modifier
                        .size(136.dp)
                        .graphicsLayer {
                            val s = 0.75f + ring * 0.55f
                            scaleX = s
                            scaleY = s
                            alpha = (1f - ring) * 0.8f
                        }
                        .clip(CircleShape)
                        .background((if (mode == HeroMode.Connected) Glow else Cyan).copy(alpha = 0.3f))
                )
            }
            Box(Modifier.size(136.dp).clip(CircleShape).background(Glow.copy(alpha = 0.16f)))
            androidx.compose.animation.AnimatedVisibility(mode == HeroMode.Connected && battery != null, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut()) {
                battery?.let { BatteryArc(it, Modifier.fillMaxSize()) }
            }
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (scaleIn(spring(Spring.DampingRatioMediumBouncy), initialScale = 0.5f) + fadeIn()) togetherWith
                        (scaleOut(targetScale = 0.5f) + fadeOut())
                },
                label = "heroIcon"
            ) { shown ->
                when (shown) {
                    HeroMode.Connected -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.LaptopMac, null, tint = Color.White,
                            modifier = Modifier.size(64.dp).graphicsLayer { rotationZ = nod.value }
                        )
                        battery?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (it.charging) Icon(Icons.Filled.Bolt, null, tint = Green, modifier = Modifier.size(14.dp))
                                Text("${it.level}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f))
                            }
                        }
                    }
                    HeroMode.Searching -> Icon(
                        Icons.Filled.Search, null, tint = Color.White,
                        modifier = Modifier.size(56.dp).graphicsLayer {
                            translationX = kotlin.math.cos(orbit) * 9.dp.toPx()
                            translationY = kotlin.math.sin(orbit) * 9.dp.toPx()
                        }
                    )
                    HeroMode.Disconnected -> Icon(Icons.Filled.LaptopMac, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(64.dp))
                    HeroMode.Unpaired -> Icon(Icons.Filled.QrCode2, null, tint = Color.White, modifier = Modifier.size(56.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = when (mode) {
                HeroMode.Connected -> macName ?: "Your Mac"
                HeroMode.Searching -> "Looking for your Mac"
                HeroMode.Unpaired -> "Not paired yet"
                HeroMode.Disconnected -> "Disconnected"
            },
            transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
            label = "status"
        ) { title ->
            Text(
                title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (mode) {
                HeroMode.Connected -> macModel ?: "Mac"
                HeroMode.Searching -> "Open AyPhone on your Mac, same Wi-Fi or USB"
                HeroMode.Unpaired -> "Pair once with the QR code in AyPhone on your Mac. After that it connects on its own."
                HeroMode.Disconnected -> "Tap Reconnect to try again"
            },
            color = Secondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
        )
        AnimatedVisibility(mode == HeroMode.Connected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Connected", Icons.Filled.CheckCircle, ConnectedTint)
                Pill(
                    transport ?: "Wi-Fi",
                    when (transport) {
                        "USB" -> Icons.Filled.Usb
                        "Bluetooth" -> Icons.Filled.Bluetooth
                        else -> Icons.Filled.Wifi
                    },
                    Color.White.copy(alpha = 0.75f)
                )
            }
        }
        AnimatedVisibility(mode == HeroMode.Unpaired, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column {
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Pair with Mac", Icons.Filled.QrCodeScanner, onClick = onPair)
            }
        }
    }
}

/** The Mac's battery as a ring around the laptop: green while charging, red when low,
 *  violet→magenta otherwise (like the phone's battery on the Mac). */
@Composable
private fun BatteryArc(battery: Link.MacBattery, modifier: Modifier) {
    val level by animateFloatAsState(battery.level / 100f, tween(900), label = "battery")
    val colors = when {
        battery.charging -> listOf(Green, Color(0xFF40DBC7))
        battery.level <= 15 -> listOf(Color(0xFFFF4545), Color(0xFFFF9433))
        else -> listOf(Indigo, Cyan)
    }
    Canvas(modifier) {
        val stroke = 7.dp.toPx()
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(
            Brush.sweepGradient(colors + colors.first()), -90f, 360f * level, false, Offset(inset, inset), arc,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

// MARK: Quick actions

/** A quick action with a glowing violet edge; the arrow nudges forward when pressed. */
@Composable
private fun ActionTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
    val nudge by animateFloatAsState(if (pressed) 6f else 0f, spring(Spring.DampingRatioMediumBouncy), label = "nudge")
    val tilt by animateFloatAsState(if (pressed) -14f else 0f, spring(Spring.DampingRatioMediumBouncy), label = "tilt")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "enabled")
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .scale(scale)
            .graphicsLayer { this.alpha = alpha }
            .shadow(14.dp, shape, ambientColor = Indigo, spotColor = Indigo)
            .clip(shape)
            .background(Color(0xFF1C1736))
            .background(GlassFill)
            .border(1.5.dp, Brush.verticalGradient(listOf(Cyan.copy(alpha = 0.8f), Indigo, Color(0xFF5B6BFF))), shape)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Indigo.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = Glow, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = tilt }) }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp).graphicsLayer { translationX = nudge.dp.toPx() }
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: Setup

/** "Setup complete" with the list behind the gear once everything needed is done, or the
 *  list itself (with progress) while steps are left. */
@Composable
private fun SetupSection(steps: List<SetupStep>) {
    val required = steps.filter { !it.optional }
    val done = required.count { it.done }
    val complete = done == required.size
    var expanded by rememberSaveable { mutableStateOf(false) }
    val gear by animateFloatAsState(if (expanded) 90f else 0f, spring(Spring.DampingRatioMediumBouncy), label = "gear")

    GlassCard(padding = 0) {
        if (complete) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Color(0xFF0B2A17), modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Setup complete", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("All features active · Manage settings", style = MaterialTheme.typography.bodySmall, color = Secondary)
                }
                Icon(Icons.Filled.Settings, "Manage settings", tint = Glow, modifier = Modifier.size(28.dp).rotate(gear))
            }
        } else {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp)) {
                Text("$done of ${required.size} done", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val progress by animateFloatAsState(done / required.size.toFloat(), tween(600), label = "progress")
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                    Box(Modifier.fillMaxWidth(progress).height(6.dp).clip(CircleShape).background(AccentBrush))
                }
            }
        }
        AnimatedVisibility(!complete || expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                if (complete) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                steps.forEachIndexed { i, step -> SetupRow(step, last = i == steps.lastIndex) }
            }
        }
    }
}

// MARK: Activity

@Composable
private fun ActivityCard(entries: List<ActivityLog.Entry>) {
    // Re-read the clock now and then so "2 min ago" stays true.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    GlassCard(padding = 18) {
        Text("Recent syncs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        entries.forEach { e ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (e.text.startsWith("Clipboard")) Icons.Filled.ContentPaste else Icons.Filled.UploadFile, null,
                    tint = Glow, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    e.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    DateUtils.getRelativeTimeSpanString(e.at, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                    style = MaterialTheme.typography.bodySmall, color = Secondary
                )
            }
        }
    }
}

// MARK: Your Mac

/** The phone as a remote for the Mac: lock it, find it, use it as a trackpad, control music. */
@Composable
private fun MacRemoteCard() {
    val ctx = LocalContext.current
    val finding by MacControl.finding.collectAsStateWithLifecycle()
    GlassCard(padding = 14) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteButton("Lock", Icons.Filled.Lock, false, Modifier.weight(1f)) { MacControl.lock() }
            RemoteButton(if (finding) "Stop" else "Find", Icons.Filled.NotificationsActive, finding, Modifier.weight(1f)) {
                MacControl.find(!finding)
            }
            RemoteButton("Trackpad", Icons.Filled.Mouse, false, Modifier.weight(1f)) {
                ctx.startActivity(android.content.Intent(ctx, TrackpadActivity::class.java))
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MediaKey(Icons.Filled.SkipPrevious, "Previous") { MacControl.media("previous") }
            MediaKey(Icons.AutoMirrored.Filled.VolumeDown, "Volume down") { MacControl.media("volumeDown") }
            MediaKey(Icons.Filled.PlayArrow, "Play or pause", big = true) { MacControl.media("playPause") }
            MediaKey(Icons.AutoMirrored.Filled.VolumeUp, "Volume up") { MacControl.media("volumeUp") }
            MediaKey(Icons.Filled.SkipNext, "Next") { MacControl.media("next") }
        }
    }
}

@Composable
private fun RemoteButton(label: String, icon: ImageVector, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
    val motion = rememberInfiniteTransition(label = "ring")
    val wiggle by motion.animateFloat(-12f, 12f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "wiggle")
    Column(
        modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Cyan.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (active) Cyan else Glow, modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = if (active) wiggle else 0f })
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MediaKey(icon: ImageVector, description: String, big: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.82f else 1f, spring(Spring.DampingRatioHighBouncy), label = "press")
    Box(
        Modifier
            .size(if (big) 54.dp else 44.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (big) AccentBrush else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.07f))))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(if (big) 28.dp else 22.dp)) }
}

// MARK: Quick toggles

@Composable
private fun QuickToggles(connected: Boolean) {
    val ctx = LocalContext.current
    val mirroring by MirrorService.active.collectAsStateWithLifecycle()
    val paused by NotificationPause.paused.collectAsStateWithLifecycle()
    val macMuted by Link.macMuted.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleTile("Screen share", Icons.Filled.ScreenShare, mirroring, connected, Modifier.weight(1f)) { on ->
            if (on) Link.send(msg("mirrorRequest").put("on", true)) else MirrorService.stop(ctx)
        }
        ToggleTile("Pause alerts", Icons.Filled.NotificationsPaused, paused, true, Modifier.weight(1f)) { NotificationPause.set(it) }
        ToggleTile("Mute Mac", Icons.Filled.VolumeOff, macMuted == true, connected && macMuted != null, Modifier.weight(1f)) { on ->
            Link.macMuted.value = on
            Link.send(msg("macMute").put("on", on))
        }
    }
}

@Composable
private fun ToggleTile(label: String, icon: ImageVector, checked: Boolean, enabled: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val iconScale by animateFloatAsState(if (checked) 1.12f else 1f, spring(Spring.DampingRatioHighBouncy), label = "icon")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "enabled")
    GlassCard(modifier.graphicsLayer { this.alpha = alpha }, radius = 20, padding = 12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (checked) Cyan else Glow, modifier = Modifier.size(24.dp).scale(iconScale))
            Spacer(Modifier.weight(1f))
            Switch(
                checked = checked, onCheckedChange = onChange, enabled = enabled,
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.12f))
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
