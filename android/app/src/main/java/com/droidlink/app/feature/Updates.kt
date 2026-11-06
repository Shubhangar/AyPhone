package com.droidlink.app.feature

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
 * Updates from GitHub: checks the latest release now and then, and on the user's tap downloads
 * its APK, makes sure it's signed with the same key as this copy (so only our own builds get
 * installed), and hands it to Android's installer. Android asks to confirm, except on 12+ once
 * AyPhone installed the copy it's replacing.
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
            available.value = if (newer) Release(version, apk) else null
            // Once per version: a notification with an Update button.
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (newer && prefs.getString("notifiedUpdate", null) != version) {
                prefs.edit().putString("notifiedUpdate", version).apply()
                com.droidlink.app.Notifications.updateAvailable(ctx, version)
            }
        } catch (e: Exception) {
            Log.i(TAG, "update check failed: $e")
        }
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
            val apk = withContext(Dispatchers.IO) { download(ctx, release) }
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
        val file = File(dir, "AyPhone-${release.version}.apk")
        val conn = (URL(release.apk).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
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
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "update install failed: $message")
                    phase.value = Phase.Failed("Android didn't install the update.")
                }
            }
        }
    }
}
