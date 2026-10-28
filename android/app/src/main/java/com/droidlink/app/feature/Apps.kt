package com.droidlink.app.feature

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.droidlink.app.Notifications
import com.droidlink.app.link.msg
import org.json.JSONArray
import org.json.JSONObject

/** The phone's launchable apps, and opening them on request from the Mac. */
object Apps {
    fun listMessage(ctx: Context): JSONObject {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = JSONArray()
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map { it to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
            .forEach { (info, label) ->
                val icon = try { Icons.pngBase64(pm.getApplicationIcon(info), 96) } catch (_: Exception) { null }
                apps.put(JSONObject().put("pkg", info.packageName).put("name", label).put("icon", icon))
            }
        return msg("apps").put("apps", apps)
    }

    /**
     * Android blocks background activity starts unless the app may draw over other apps.
     * Without that permission, a tap-to-open notification is the fallback.
     */
    fun launch(ctx: Context, pkg: String) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w("AyPhone", "launch $pkg failed: $e")
            }
        }
        val label = try {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        Notifications.showLaunch(ctx, label, intent)
    }
}
