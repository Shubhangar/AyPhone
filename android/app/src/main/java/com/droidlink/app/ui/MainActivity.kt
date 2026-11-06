package com.droidlink.app.ui

import android.Manifest
import android.content.Context
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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.droidlink.app.feature.CallCompanion
import com.droidlink.app.feature.ClipboardBridge
import com.droidlink.app.feature.FileSender
import com.droidlink.app.feature.FindPhone
import com.droidlink.app.link.Link
import com.droidlink.app.link.Pairing
import com.droidlink.app.service.LinkService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    companion object {
        /** From the update notification's Update button: start installing right away. */
        const val ACTION_UPDATE = "com.droidlink.app.UPDATE"
    }

    /** What the setup step needs: texts, caller names, calls, and AyPhone's own notifications. */
    private val essentialPermissions = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Asked for in the same go, but they don't hold the step up: each only switches off its own
     *  feature (calling from the Mac, photos, mirroring sound, the Bluetooth fallback). */
    private val extraPermissions = buildList {
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_IMAGES) else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private val runtimePermissions = (essentialPermissions + extraPermissions).toTypedArray()

    /** Whether Android has been asked already, so a tap that it would ignore (asked twice and
     *  refused) opens AyPhone's settings instead. */
    private var askedForPermissions = false

    private fun askForPermissions() {
        val missing = essentialPermissions.filterNot(::granted)
        // Android stops showing the request once a permission has been refused twice.
        if (askedForPermissions && missing.isNotEmpty() && missing.none(::shouldShowRequestPermissionRationale)) {
            toast("Allow ${missingNames(missing)} under Permissions")
            appSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            return
        }
        askedForPermissions = true
        permissionRequest.launch(runtimePermissions)
    }

    private fun missingNames(missing: List<String>) = missing.map {
        when (it) {
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS -> "SMS"
            Manifest.permission.READ_CONTACTS -> "Contacts"
            Manifest.permission.READ_CALL_LOG -> "Call logs"
            Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
            else -> "Phone"
        }
    }.distinct().joinToString(", ")

    /** Bumped in onResume so the setup steps re-read permission state. */
    private var refreshTick by mutableIntStateOf(0)
    /** Whether the first-run onboarding has been finished (or wasn't needed). */
    private var onboarded by mutableStateOf(false)
    /** The "What's new" tour, once after an update (or opened from Settings). */
    private var showWhatsNew by mutableStateOf(false)

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onScanned)
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            FileSender.send(FileSender.prepare(this, uris), glow = true)
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

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // People who set AyPhone up before onboarding existed go straight to the home screen.
        onboarded = prefs.getBoolean("onboarded", false) ||
            (Pairing.load(this) != null && setupSteps().filter { !it.optional }.all { it.done })
        if (onboarded) prefs.edit().putBoolean("onboarded", true).apply()
        com.droidlink.app.feature.LastReceived.load(this)
        showWhatsNew = onboarded && WhatsNew.due(this)
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
                    LaunchSplash()
                }
            }
        }
        pickFilesIfAsked()
        updateIfAsked()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pickFilesIfAsked()
        updateIfAsked()
    }

    /** The widget's Files button opens the app straight into the file picker. */
    private fun updateIfAsked() {
        if (intent?.action != ACTION_UPDATE) return
        intent.action = null
        getSystemService(android.app.NotificationManager::class.java).cancel(com.droidlink.app.Notifications.UPDATE_ID)
        lifecycleScope.launch {
            com.droidlink.app.feature.Updates.check(this@MainActivity, force = true)
            if (com.droidlink.app.feature.Updates.allowedToInstall(this@MainActivity)) com.droidlink.app.feature.Updates.install(this@MainActivity)
        }
    }

    private fun pickFilesIfAsked() {
        if (intent?.action != MacWidget.ACTION_PICK_FILES) return
        intent.action = null
        filePicker.launch(arrayOf("*/*"))
    }

    override fun onResume() {
        super.onResume()
        FindPhone.stop()
        refreshTick++
        // Foreground starts are always allowed, so this also recovers a service the system refused earlier.
        if (Pairing.load(this) != null) LinkService.start(this)
        Link.macNearby()
        lifecycleScope.launch { com.droidlink.app.feature.Updates.check(this@MainActivity) }
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

    private fun startPairing() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the code shown in AyPhone on your Mac")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
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

    /** Everything AyPhone needs from Android, in the order onboarding asks for it. */
    private fun setupSteps() = listOf(
        SetupStep(
            "Notification access", "Mirror notifications, reply from your Mac, control media", Icons.Filled.Notifications,
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        essentialPermissions.filterNot(::granted).let { missing ->
            SetupStep(
                "Messages, contacts, calls, photos & sound",
                if (missing.isEmpty()) "Texts, caller names and calling from your Mac, recent photos, mirroring sound, Bluetooth fallback"
                else "Still needed: ${missingNames(missing)}",
                Icons.Filled.Sms, missing.isEmpty()
            ) { askForPermissions() }
        },
        SetupStep(
            "Display over other apps", "Lets your Mac open apps, and paste what you copy here with ⌘V", Icons.Filled.Layers,
            Settings.canDrawOverlays(this)
        ) { appSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
        SetupStep(
            "Call controls from Mac", "Speaker, mute and hang up from your Mac. Android asks to let AyPhone manage your Mac (it may call it a watch)",
            Icons.Filled.Call, CallCompanion.approved(this)
        ) { approveCallControls() },
        SetupStep(
            "Unrestricted battery", "Keeps the connection alive. Tap Battery, then Unrestricted", Icons.Filled.BatteryChargingFull,
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        ) { appSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
        SetupStep(
            "Optional: control from Mac",
            "Click and type on the mirrored screen. Some banking apps refuse to open while this is on, and the clipboard doesn't need it. If it says restricted: App info → ⋮ → Allow restricted settings",
            Icons.Filled.TouchApp, controlEnabled(), optional = true
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    )

    @Composable
    private fun Screen() {
        @Suppress("UNUSED_VARIABLE") val tick = refreshTick
        val paired = Pairing.load(this) != null
        val steps = setupSteps()
        val macName by Link.macName.collectAsStateWithLifecycle()

        AnimatedContent(
            targetState = if (showWhatsNew) null else onboarded,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
            label = "onboarding"
        ) { done ->
            if (done == null) {
                WhatsNewTour {
                    WhatsNew.markSeen(this@MainActivity)
                    showWhatsNew = false
                }
            } else if (done) {
                Home(
                    paired, steps,
                    HomeActions(
                        pair = ::startPairing,
                        sendClipboard = { toast(if (ClipboardBridge.readAndSend(this@MainActivity)) "Clipboard sent" else "Clipboard is empty") },
                        sendFiles = { filePicker.launch(arrayOf("*/*")) },
                        reconnect = { Link.restart() },
                        unpair = ::unpair,
                        whatsNew = { showWhatsNew = true },
                    )
                )
            } else {
                Onboarding(paired, macName, steps, onPair = ::startPairing) {
                    prefs.edit().putBoolean("onboarded", true).apply()
                    // Onboarding covers what's new for a fresh install.
                    WhatsNew.markSeen(this@MainActivity)
                    onboarded = true
                }
            }
        }
    }
}
