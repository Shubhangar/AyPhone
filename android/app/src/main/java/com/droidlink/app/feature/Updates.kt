package com.droidlink.app.feature

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from GitHub: checks the latest release now and then and (with "Download updates
 * automatically" on, and on Wi-Fi) downloads its APK in the background, then says it's ready.
 * Install now makes sure it's signed with the same key as this copy (so only our own builds get
 * installed) and hands it to Android's installer. Android asks to confirm, except on 12+ once
 * AyPhone installed the copy it's replacing. It never installs without a tap: Android or Play
 * Protect may want an answer, so the person is there for it.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/Shubhangar/AyPhone/releases/latest"
    private const val TAG = "AyPhone"

    data class Release(val version: String, val apk: String)

    sealed interface Phase {
        data object Idle : Phase
        data object Downloading : Phase
        data object Installing : Phase
        data class Failed(val reason: String) : Phase
    }

    /** A newer release than this copy, once found. */
    val available = MutableStateFlow<Release?>(null)
    /** The version whose APK is downloaded and waiting for Install now. */
    val ready = MutableStateFlow<String?>(null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "Download updates automatically" (on unless switched off). */
    fun autoDownload(ctx: Context) = prefs(ctx).getBoolean("autoUpdate", true)
    fun setAutoDownload(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoUpdate", on).apply()

    private fun apkFile(ctx: Context, version: String) = File(File(ctx.cacheDir, "updates"), "AyPhone-$version.apk")
    val phase = MutableStateFlow<Phase>(Phase.Idle)
    private var lastCheck = 0L

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** At most every few hours, unless [force]d. */
    suspend fun check(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 3 * 3600_000L) return@withContext
        lastCheck = now
        try {
            val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it).getString("browser_download_url") }
                .firstOrNull { it.endsWith("-android.apk") } ?: return@withContext
            val newer = isNewer(version, currentVersion(ctx))
            val release = if (newer) Release(version, apk) else null
            available.value = release
            if (release == null) {
                ready.value = null
                File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() }
                return@withContext
            }
            if (apkFile(ctx, version).length() > 0) ready.value = version
            // Downloaded in the background first where it can be (Wi-Fi), so Install now is quick.
            if (autoDownload(ctx) && ready.value != version && !ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) {
                try {
                    download(ctx, release)
                    ready.value = version
                    Log.i(TAG, "update $version downloaded; ready to install")
                } catch (e: Exception) {
                    Log.i(TAG, "background download failed: $e")
                }
            }
            notifyOnce(ctx, version)
        } catch (e: Exception) {
            Log.i(TAG, "update check failed: $e")
        }
    }

    /** Once per version: "available" (Update), or "ready to install" (Install now). */
    private fun notifyOnce(ctx: Context, version: String) {
        if (prefs(ctx).getString("notifiedUpdate", null) == version) return
        prefs(ctx).edit().putString("notifiedUpdate", version).apply()
        com.droidlink.app.Notifications.updateAvailable(ctx, version, ready = ready.value == version)
    }

    /** "0.1.10" is newer than "0.1.9". */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** Whether Android lets AyPhone install; if not, opens the setting (returns false). */
    fun allowedToInstall(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return false
    }

    suspend fun install(ctx: Context) {
        val release = available.value ?: return
        if (phase.value == Phase.Downloading || phase.value == Phase.Installing) return
        phase.value = Phase.Downloading
        try {
            val waiting = apkFile(ctx, release.version).takeIf { ready.value == release.version && it.length() > 0 }
            val apk = waiting ?: withContext(Dispatchers.IO) { download(ctx, release) }
            if (!signedLikeUs(ctx, apk)) {
                apk.delete()
                phase.value = Phase.Failed("This copy can't update itself (it's a development build). Download AyPhone from GitHub instead.")
                return
            }
            phase.value = Phase.Installing
            withContext(Dispatchers.IO) { commit(ctx, apk) }
        } catch (e: Exception) {
            Log.w(TAG, "update failed: $e")
            phase.value = Phase.Failed("Couldn't download the update. Try again.")
        }
    }

    private fun download(ctx: Context, release: Release): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = apkFile(ctx, release.version)
        val part = File(dir, file.name + ".part")
        val conn = (URL(release.apk).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        // Whole files only: an interrupted download never counts as ready.
        if (!part.renameTo(file)) throw java.io.IOException("couldn't save the update")
        return file
    }

    /** The APK is signed with the same key as the installed app. */
    @Suppress("DEPRECATION")
    private fun signedLikeUs(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (theirs.packageName != ctx.packageName) return false
        val ours = pm.getPackageInfo(ctx.packageName, flags)
        fun certs(info: android.content.pm.PackageInfo) =
            if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            else info.signatures?.map { it.toCharsString() }?.toSet()
        val a = certs(theirs)
        return !a.isNullOrEmpty() && a == certs(ours)
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("AyPhone.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) } }
            val status = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallStatusReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(status.intentSender)
        }
    }

    /** Android's answer: ask the user to confirm, or it failed (the app restarts if it worked). */
    class InstallStatusReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> phase.value = Phase.Idle
                else -> {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "update install failed: $status $message")
                    phase.value = Phase.Failed(
                        when {
                            // Play Protect said no (its warning was closed, or Don't install).
                            message?.contains("VERIFICATION", ignoreCase = true) == true || status == PackageInstaller.STATUS_FAILURE_BLOCKED ->
                                "Google Play Protect stopped the update. Tap Install now again; when it warns, tap More details, then Install anyway."
                            status == PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled. Tap Install now to try again."
                            else -> "Android didn't install the update. Tap Install now to try again."
                        }
                    )
                }
            }
        }
    }
}
