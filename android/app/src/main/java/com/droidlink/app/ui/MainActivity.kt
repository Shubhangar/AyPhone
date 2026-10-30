package com.droidlink.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidlink.app.R
import com.droidlink.app.feature.CallCompanion
import com.droidlink.app.feature.ClipboardBridge
import com.droidlink.app.feature.FileSender
import com.droidlink.app.feature.FindPhone
import com.droidlink.app.link.Link
import com.droidlink.app.link.Pairing
import com.droidlink.app.service.LinkService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// From the logo: violet and magenta accents over a deep purple→blue backdrop.
private val Indigo = Color(0xFF895AFF)
private val Cyan = Color(0xFFF36BFF)
/** The soft violet glow behind the logo while connected. */
private val Glow = Color(0xFFC981FF)
private val DeepPurple = Color(0xFF361A6A)
private val DeepBlue = Color(0xFF084B7B)
private val AccentBrush = Brush.linearGradient(listOf(Indigo, Cyan))
/** Night versions of the logo's purple and blue behind the UI, so text stays readable. */
private val BackdropBrush = Brush.verticalGradient(listOf(Color(0xFF170B2E), Color(0xFF041E33)))
/** Glass: a faint white tint and a light edge over the backdrop. */
private val GlassFill = Color.White.copy(alpha = 0.06f)
private val GlassEdge = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f), Cyan.copy(alpha = 0.20f)))

class MainActivity : ComponentActivity() {
    private val runtimePermissions = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** Bumped in onResume so the setup checklist re-reads permission state. */
    private var refreshTick by mutableIntStateOf(0)

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onScanned)
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            FileSender.send(FileSender.prepare(this, uris))
            toast("Sending ${uris.size} file(s) to your Mac")
        }
    }
    private val companionApproval = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        refreshTick++
    }

    private fun approveCallControls() {
        CallCompanion.request(this, { companionApproval.launch(IntentSenderRequest.Builder(it).build()) }) { error ->
            refreshTick++
            if (error != null && !CallCompanion.approved(this)) toast(error)
        }
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshTick++
        if (Pairing.load(this) != null) LinkService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The glass look needs the dark, colourful backdrop, so the app is always dark.
            val colors = darkColorScheme(
                primary = Cyan, secondary = Indigo,
                background = Color.Transparent, surface = Color.Transparent, surfaceVariant = GlassFill,
                onBackground = Color.White, onSurface = Color.White, outline = Color.White.copy(alpha = 0.35f)
            )
            MaterialTheme(colorScheme = colors) {
                Box(Modifier.fillMaxSize().background(BackdropBrush)) {
                    // Soft glows behind the glass (unbounded, so the blur fades out instead of being cut off).
                    Box(Modifier.size(320.dp).offset((-90).dp, (-60).dp).blur(90.dp, BlurredEdgeTreatment.Unbounded).background(Cyan.copy(alpha = 0.14f), CircleShape))
                    Box(Modifier.size(360.dp).align(Alignment.BottomEnd).offset(80.dp, 60.dp).blur(110.dp, BlurredEdgeTreatment.Unbounded).background(Indigo.copy(alpha = 0.20f), CircleShape))
                    Screen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FindPhone.stop()
        refreshTick++
        // Foreground starts are always allowed, so this also recovers a service the system refused earlier.
        if (Pairing.load(this) != null) LinkService.start(this)
    }

    private fun onScanned(text: String) {
        val info = Pairing.parse(text)
        if (info == null) {
            toast("That isn't a AyPhone pairing code")
            return
        }
        Pairing.save(this, info)
        Link.restart()
        LinkService.start(this)
        refreshTick++
        toast("Paired with ${info.macName}")
    }

    private fun unpair() {
        Pairing.clear(this)
        LinkService.stop(this)
        Link.stop()
        refreshTick++
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Whether the user switched on AyPhone's control service in Accessibility settings. */
    private fun controlEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.contains("$packageName/") && enabled.contains("ControlService")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun appSettings(action: String) =
        startActivity(Intent(action, Uri.fromParts("package", packageName, null)))

    @Composable
    private fun Screen() {
        val state by Link.state.collectAsStateWithLifecycle()
        val macName by Link.macName.collectAsStateWithLifecycle()
        val transport by Link.transport.collectAsStateWithLifecycle()
        @Suppress("UNUSED_VARIABLE") val tick = refreshTick
        val paired = Pairing.load(this) != null
        val connected = state == Link.State.Connected

        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("AyPhone", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)

                Hero(state, macName, transport)

                AnimatedVisibility(!paired, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    PrimaryButton("Pair with Mac", Icons.Filled.QrCodeScanner) {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan the code shown in AyPhone on your Mac")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    }
                }
                AnimatedVisibility(paired, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionChip("Clipboard", Icons.Filled.ContentPaste, Modifier.weight(1f), enabled = connected) {
                            toast(if (ClipboardBridge.readAndSend(this@MainActivity)) "Clipboard sent" else "Clipboard is empty")
                        }
                        ActionChip("Files", Icons.Filled.UploadFile, Modifier.weight(1f), enabled = connected) {
                            filePicker.launch(arrayOf("*/*"))
                        }
                    }
                }

                SectionTitle("Setup")
                Card {
                    SetupRow(
                        "Notification access", "Mirror notifications, reply from your Mac, control media",
                        NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity).contains(packageName)
                    ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    SetupRow(
                        "Messages, contacts, calls, sound & Bluetooth", "Texts and caller names on your Mac, call controls, mirroring sound, Bluetooth fallback",
                        runtimePermissions.all(::granted)
                    ) { permissionRequest.launch(runtimePermissions) }
                    SetupRow(
                        "Display over other apps", "Lets your Mac open apps, and paste what you copy here with ⌘V",
                        Settings.canDrawOverlays(this@MainActivity)
                    ) { appSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
                    SetupRow(
                        "Call controls from Mac", "Speaker, mute and hang up from your Mac. Android asks to let AyPhone manage your Mac (it may call it a watch)",
                        CallCompanion.approved(this@MainActivity)
                    ) { approveCallControls() }
                    SetupRow(
                        "Unrestricted battery", "Keeps the connection alive. Tap Battery, then Unrestricted",
                        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                    ) { appSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
                    SetupRow(
                        "Optional: control from Mac",
                        "Click and type on the mirrored screen. Some banking apps refuse to open while this is on, and the clipboard doesn't need it. If it says restricted: App info → ⋮ → Allow restricted settings",
                        controlEnabled(),
                        last = true
                    ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }

                AnimatedVisibility(paired) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { Link.restart() }) { Text("Reconnect") }
                        TextButton(onClick = ::unpair) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
                    }
                }
                Text(
                    "Clipboard: copy here, then press ⌘V on your Mac to paste it (keep the phone unlocked). Copies on the Mac arrive here automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }

    @Composable
    private fun Hero(state: Link.State, macName: String?, transport: String?) {
        val connected = state == Link.State.Connected
        val searching = state == Link.State.Connecting
        val pulse = rememberInfiniteTransition(label = "pulse")
        val ring by pulse.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "ring"
        )
        val dot by animateColorAsState(
            when (state) {
                Link.State.Connected -> Glow
                Link.State.Connecting -> Cyan
                else -> Color.Gray
            }, label = "dot"
        )

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Indigo.copy(alpha = 0.22f), DeepBlue.copy(alpha = 0.25f))))
                .background(GlassFill)
                .border(1.dp, GlassEdge, RoundedCornerShape(28.dp))
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(76.dp).clip(CircleShape).background(Glow.copy(alpha = 0.18f)))
                    if (connected || searching) {
                        Box(
                            Modifier
                                .size(76.dp)
                                .graphicsLayer {
                                    scaleX = 0.6f + ring * 0.6f
                                    scaleY = 0.6f + ring * 0.6f
                                    alpha = 1f - ring
                                }
                                .clip(CircleShape)
                                .background(dot.copy(alpha = 0.35f))
                        )
                    }
                    Image(
                        painterResource(R.drawable.ic_logo), contentDescription = null,
                        modifier = Modifier.size(58.dp)
                    )
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    AnimatedContent(
                        targetState = when (state) {
                            Link.State.Connected -> "Connected"
                            Link.State.Connecting -> "Looking for your Mac"
                            Link.State.Unpaired -> "Not paired yet"
                            Link.State.Disconnected -> "Disconnected"
                        },
                        transitionSpec = {
                            (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                        },
                        label = "status"
                    ) { title ->
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when (state) {
                            Link.State.Connected -> "${macName ?: "Your Mac"} · ${transport ?: "Wi-Fi"}"
                            Link.State.Connecting -> "Open AyPhone on your Mac, same Wi-Fi or USB"
                            Link.State.Unpaired -> "Scan the QR code shown on your Mac"
                            Link.State.Disconnected -> "Tap Reconnect to try again"
                        },
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    @Composable
    private fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
        Row(
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(RoundedCornerShape(18.dp))
                .background(AccentBrush)
                .clickable(interaction, indication = null, onClick = onClick)
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun ActionChip(text: String, icon: ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
        val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "enabled")
        Column(
            modifier
                .scale(scale)
                .graphicsLayer { this.alpha = alpha }
                .clip(RoundedCornerShape(20.dp))
                .background(GlassFill)
                .border(1.dp, GlassEdge, RoundedCornerShape(20.dp))
                .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
                .padding(16.dp)
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Indigo),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.height(10.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Send to Mac", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(GlassFill)
                .border(1.dp, GlassEdge, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) { content() }
    }

    @Composable
    private fun SetupRow(title: String, subtitle: String, done: Boolean, last: Boolean = false, onClick: (() -> Unit)?) {
        val tint by animateColorAsState(if (done) Cyan else MaterialTheme.colorScheme.outline, label = "check")
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !done && onClick != null) { onClick?.invoke() }
                    .padding(vertical = 12.dp)
            ) {
                Icon(if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = tint)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                if (!done && onClick != null) Text("Allow", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            if (!last) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.07f))
                )
            }
        }
    }
}
