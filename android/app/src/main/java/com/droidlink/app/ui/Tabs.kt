package com.droidlink.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.Size
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidlink.app.feature.ActivityLog
import com.droidlink.app.feature.FileReceiver
import com.droidlink.app.feature.FileSender
import com.droidlink.app.feature.LastReceived
import com.droidlink.app.feature.MacControl
import com.droidlink.app.feature.Updates
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: Tab bar

internal enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Control("Control", Icons.Filled.Tune),
    Send("Send", Icons.Filled.Send),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
internal fun TabBar(selected: Tab, select: (Tab) -> Unit) {
    Row(
        Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)).background(Color(0xFF1C1834)).border(1.dp, GlassEdge, RoundedCornerShape(24.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        Tab.entries.forEach { tab ->
            val on = tab == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { select(tab) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Indigo.copy(alpha = 0.7f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) { Icon(tab.icon, null, tint = if (on) Color.White else Color.White.copy(alpha = 0.55f), modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label, style = MaterialTheme.typography.labelSmall,
                    color = if (on) Color.White else Color.White.copy(alpha = 0.55f), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

// MARK: Home below the hero

/** What a shortcut on the home screen can be (long-press one to swap it). */
internal enum class Shortcut(val label: String, val icon: ImageVector) {
    Send("Send", Icons.Filled.UploadFile),
    Scan("Scan", Icons.Filled.DocumentScanner),
    Trackpad("Trackpad", Icons.Filled.Mouse),
    Lock("Lock", Icons.Filled.Lock),
    Deck("Deck", Icons.Filled.Apps),
    Pad("Pad", Icons.Filled.Draw),
    Find("Find Mac", Icons.Filled.NotificationsActive),
    Clipboard("Clipboard", Icons.Filled.ContentPaste),
    Share("Share screen", Icons.Filled.ScreenShare);

    companion object {
        val defaults = listOf(Send, Scan, Trackpad, Lock)

        fun load(ctx: Context): List<Shortcut> =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("shortcuts", null)
                ?.split(',')?.mapNotNull { name -> entries.firstOrNull { it.name == name } }
                ?.takeIf { it.size == 4 } ?: defaults

        fun save(ctx: Context, list: List<Shortcut>) =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("shortcuts", list.joinToString(",") { it.name }).apply()
    }
}

internal fun run(ctx: Context, shortcut: Shortcut, actions: HomeActions) {
    when (shortcut) {
        Shortcut.Send -> actions.sendFiles()
        Shortcut.Scan -> ScanActivity.open(ctx, null)
        Shortcut.Trackpad -> ctx.startActivity(Intent(ctx, TrackpadActivity::class.java))
        Shortcut.Lock -> { MacControl.lock(); Toast.makeText(ctx, "Locking your Mac", Toast.LENGTH_SHORT).show() }
        Shortcut.Deck -> ctx.startActivity(Intent(ctx, DeckActivity::class.java))
        Shortcut.Pad -> ctx.startActivity(Intent(ctx, PadActivity::class.java))
        Shortcut.Find -> MacControl.find(!MacControl.finding.value)
        Shortcut.Clipboard -> actions.sendClipboard()
        Shortcut.Share -> Link.send(msg("mirrorRequest").put("on", true))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Shortcuts(actions: HomeActions, enabled: Boolean) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Shortcut.load(ctx)) }
    var swapping by remember { mutableStateOf<Int?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEachIndexed { i, s ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(GlassFill)
                    .border(1.dp, GlassEdge, RoundedCornerShape(20.dp))
                    .combinedClickable(enabled = enabled, onLongClick = { swapping = i }) { run(ctx, s, actions) }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(s.icon, null, tint = if (enabled) Glow else Glow.copy(alpha = 0.4f), modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(6.dp))
                Text(s.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    swapping?.let { slot ->
        AlertDialog(
            onDismissRequest = { swapping = null },
            title = { Text("Choose a shortcut") },
            text = {
                Column {
                    Shortcut.entries.filter { it !in list || it == list[slot] }.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                list = list.toMutableList().also { it[slot] = option }
                                Shortcut.save(ctx, list)
                                swapping = null
                            }.padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(option.icon, null, tint = Glow, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(option.label, fontWeight = if (option == list[slot]) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { swapping = null }) { Text("Cancel") } },
            containerColor = Color(0xFF221D3D)
        )
    }
}

/**
 * The one thing worth knowing right now, or nothing: a file arriving, the Mac's battery running
 * low, setup left to finish, an update, or the Deck for the app in front on the Mac.
 */
@Composable
internal fun SmartCard(steps: List<SetupStep>, goTo: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val arriving by FileReceiver.arriving.collectAsStateWithLifecycle()
    val battery by Link.macBattery.collectAsStateWithLifecycle()
    val release by Updates.available.collectAsStateWithLifecycle()
    val deck by Link.macDeck.collectAsStateWithLifecycle()
    val state by Link.state.collectAsStateWithLifecycle()
    val connected = state == Link.State.Connected
    val missing = steps.count { !it.optional && !it.done }
    val file = arriving
    val bat = battery
    when {
        file != null -> ContextCard(Icons.Filled.Download, "Receiving ${file.first}", null, progress = file.second)
        connected && bat != null && !bat.charging && bat.level <= 15 ->
            ContextCard(Icons.Filled.BatteryAlert, "Your Mac is at ${bat.level}%", "Plug it in soon.")
        missing > 0 -> ContextCard(Icons.Filled.SettingsSuggest, "Finish setting up", "$missing step${if (missing == 1) "" else "s"} left, in Settings") { goTo(Tab.Settings) }
        release != null -> UpdateCard()
        connected && deck.hasLayout -> ContextCard(Icons.Filled.Apps, "${deck.name} is open on your Mac", "Open its Deck") {
            ctx.startActivity(Intent(ctx, DeckActivity::class.java))
        }
    }
}

@Composable
private fun ContextCard(icon: ImageVector, title: String, detail: String?, progress: Float? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Indigo.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(22.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                val p by animateFloatAsState(progress, tween(300), label = "progress")
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(p).height(5.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White.copy(alpha = 0.8f))
    }
}

/** What the Mac is playing, with its controls; only while there's something. */
@Composable
internal fun NowPlayingCard() {
    val np by Link.macNowPlaying.collectAsStateWithLifecycle()
    AnimatedVisibility(np.title.isNotEmpty() || np.playing, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        GlassCard(padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(np.title.ifEmpty { "Playing on your Mac" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(np.artist, "on Mac").filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Secondary, maxLines = 1)
                }
                MediaKey(Icons.Filled.SkipPrevious, "Previous") { MacControl.media("previous") }
                Spacer(Modifier.width(6.dp))
                MediaKey(if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (np.playing) "Pause" else "Play", big = true) { MacControl.media("playPause") }
                Spacer(Modifier.width(6.dp))
                MediaKey(Icons.Filled.SkipNext, "Next") { MacControl.media("next") }
            }
        }
    }
}

/** The latest file the Mac sent, ready to open. */
@Composable
internal fun LastFromMac() {
    val ctx = LocalContext.current
    val last by LastReceived.latest.collectAsStateWithLifecycle()
    val file = last ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    GlassCard(padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Download, null, tint = Glow, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "From your Mac · " + DateUtils.getRelativeTimeSpanString(file.at, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE),
                    style = MaterialTheme.typography.bodySmall, color = Secondary
                )
            }
            TextButton(onClick = {
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                    Toast.makeText(ctx, "No app here opens that file", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Open", color = Cyan, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// MARK: Control

@Composable
internal fun ControlTab() {
    val ctx = LocalContext.current
    val state by Link.state.collectAsStateWithLifecycle()
    val deck by Link.macDeck.collectAsStateWithLifecycle()
    val finding by MacControl.finding.collectAsStateWithLifecycle()
    val connected = state == Link.State.Connected
    TabTitle("Control")
    // The Deck first: the one used most.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Indigo.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
            .clickable(enabled = connected) { ctx.startActivity(Intent(ctx, DeckActivity::class.java)) }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Apps, null, tint = Color.White, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Deck", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (connected) "Buttons for ${deck.name}" else "Connect to your Mac first", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White.copy(alpha = 0.8f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteButton("Trackpad", Icons.Filled.Mouse, false, Modifier.weight(1f)) { ctx.startActivity(Intent(ctx, TrackpadActivity::class.java)) }
        RemoteButton("Pad", Icons.Filled.Draw, false, Modifier.weight(1f)) { ctx.startActivity(Intent(ctx, PadActivity::class.java)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteButton("Lock Mac", Icons.Filled.Lock, false, Modifier.weight(1f)) { MacControl.lock() }
        RemoteButton(if (finding) "Stop" else "Find Mac", Icons.Filled.NotificationsActive, finding, Modifier.weight(1f)) { MacControl.find(!finding) }
    }
    SectionTitle("Media on your Mac")
    MediaCard()
}

@Composable
private fun MediaCard() {
    val np by Link.macNowPlaying.collectAsStateWithLifecycle()
    GlassCard(padding = 14) {
        Text(
            if (np.title.isNotEmpty()) listOf(np.title, np.artist).filter { it.isNotEmpty() }.joinToString(" · ") else "Nothing playing",
            style = MaterialTheme.typography.bodySmall, color = Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MediaKey(Icons.Filled.SkipPrevious, "Previous") { MacControl.media("previous") }
            MediaKey(Icons.AutoMirrored.Filled.VolumeDown, "Volume down") { MacControl.media("volumeDown") }
            MediaKey(if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (np.playing) "Pause" else "Play", big = true) { MacControl.media("playPause") }
            MediaKey(Icons.AutoMirrored.Filled.VolumeUp, "Volume up") { MacControl.media("volumeUp") }
            MediaKey(Icons.Filled.SkipNext, "Next") { MacControl.media("next") }
        }
    }
}

// MARK: Send

@Composable
internal fun SendTab(actions: HomeActions) {
    val ctx = LocalContext.current
    val state by Link.state.collectAsStateWithLifecycle()
    val connected = state == Link.State.Connected
    TabTitle("Send")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionTile("Files", "Send to Mac · Browse", Icons.Filled.UploadFile, Modifier.weight(1f), connected, actions.sendFiles)
        ActionTile("Clipboard", "Push to Mac", Icons.Filled.ContentPaste, Modifier.weight(1f), connected, actions.sendClipboard)
    }
    SectionTitle("Scan to Mac")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteButton("Document", Icons.Filled.Description, false, Modifier.weight(1f)) { ScanActivity.open(ctx, "document") }
        RemoteButton("Text", Icons.Filled.TextFields, false, Modifier.weight(1f)) { ScanActivity.open(ctx, "text") }
        RemoteButton("Photo", Icons.Filled.PhotoCamera, false, Modifier.weight(1f)) { ScanActivity.open(ctx, "photo") }
    }
    SectionTitle("Recent photos")
    RecentPhotos(connected)
}

/** The newest photos; a tap sends one to the Mac. */
@Composable
private fun RecentPhotos(connected: Boolean) {
    val ctx = LocalContext.current
    var photos by remember { mutableStateOf<List<Pair<Uri, ImageBitmap>>?>(null) }
    LaunchedEffect(Unit) { photos = withContext(Dispatchers.IO) { recentPhotos(ctx, 8) } }
    val list = photos ?: return
    if (list.isEmpty()) {
        Text("Allow photos under Settings → Setup to see your latest ones here.", style = MaterialTheme.typography.bodySmall, color = Secondary)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (uri, bitmap) ->
                    Image(
                        bitmap, "Photo", contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = connected) {
                                FileSender.send(FileSender.prepare(ctx, listOf(uri)), glow = true)
                                Toast.makeText(ctx, "Sending the photo to your Mac", Toast.LENGTH_SHORT).show()
                            }
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun recentPhotos(ctx: Context, count: Int): List<Pair<Uri, ImageBitmap>> {
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val limited = Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    if (ContextCompat.checkSelfPermission(ctx, permission) != PackageManager.PERMISSION_GRANTED && !limited) return emptyList()
    val out = mutableListOf<Pair<Uri, ImageBitmap>>()
    ctx.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { c ->
        while (c.moveToNext() && out.size < count) {
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            try {
                out += uri to ctx.contentResolver.loadThumbnail(uri, Size(320, 320), null).asImageBitmap()
            } catch (_: Exception) {
            }
        }
    }
    return out
}

// MARK: Settings

@Composable
internal fun SettingsTab(paired: Boolean, steps: List<SetupStep>, actions: HomeActions) {
    val ctx = LocalContext.current
    val state by Link.state.collectAsStateWithLifecycle()
    val macName by Link.macName.collectAsStateWithLifecycle()
    val awayAvailable by Link.macAwayAvailable.collectAsStateWithLifecycle()
    val awayOn by Link.macAwayEnabled.collectAsStateWithLifecycle()
    val connected = state == Link.State.Connected
    val scope = rememberCoroutineScope()
    TabTitle("Settings")
    if (paired) {
        SectionTitle("Quick toggles")
        QuickToggles(connected)
    }
    if (connected && awayAvailable) {
        GlassCard(padding = 16) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("While you were away", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "When you unlock your Mac, a short summary of what came in here. Made by Apple Intelligence on the Mac; nothing leaves it.",
                        style = MaterialTheme.typography.bodySmall, color = Secondary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = awayOn,
                    onCheckedChange = { on -> Link.macAwayEnabled.value = on; Link.send(msg("awaySummary").put("on", on)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.12f))
                )
            }
        }
    }
    SectionTitle("Setup")
    SetupSection(steps)
    SectionTitle("Your Mac")
    GlassCard(padding = 16) {
        Text(if (paired) macName ?: "Paired Mac" else "Not paired", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (!paired) "Scan the code in AyPhone on your Mac." else if (connected) "Connected" else "Not connected right now",
            style = MaterialTheme.typography.bodySmall, color = Secondary
        )
        Spacer(Modifier.height(8.dp))
        Row {
            if (paired) {
                TextButton(onClick = actions.reconnect) { Text("Reconnect", color = Cyan) }
                TextButton(onClick = actions.unpair) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = actions.pair) { Text("Pair with your Mac", color = Cyan) }
            }
        }
    }
    val activity by ActivityLog.entries.collectAsStateWithLifecycle()
    if (paired && activity.isNotEmpty()) {
        SectionTitle("Activity")
        ActivityCard(activity)
    }
    SectionTitle("About")
    GlassCard(padding = 16) {
        Text("AyPhone ${Updates.currentVersion(ctx)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Clipboard: copy here, then press ⌘V on your Mac to paste it (keep the phone unlocked). Copies on the Mac arrive here automatically.",
            style = MaterialTheme.typography.bodySmall, color = Secondary
        )
        TextButton(onClick = {
            scope.launch {
                Updates.check(ctx, force = true)
                if (Updates.available.value == null) Toast.makeText(ctx, "You have the latest version", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Check for updates", color = Cyan) }
    }
    UpdateCard()
}

@Composable
private fun TabTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}
