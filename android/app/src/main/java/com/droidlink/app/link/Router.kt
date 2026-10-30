package com.droidlink.app.link


import android.os.Handler
import android.os.Looper
import com.droidlink.app.Notifications
import com.droidlink.app.feature.Apps
import com.droidlink.app.feature.Battery
import com.droidlink.app.feature.Calls
import com.droidlink.app.feature.Contacts
import com.droidlink.app.feature.ClipboardBridge
import com.droidlink.app.feature.FileReceiver
import com.droidlink.app.feature.FindPhone
import com.droidlink.app.feature.MacControl
import com.droidlink.app.feature.Photos
import com.droidlink.app.feature.Media
import com.droidlink.app.feature.PhoneScreen
import com.droidlink.app.feature.Sms
import com.droidlink.app.service.ControlService
import com.droidlink.app.service.MirrorService
import com.droidlink.app.service.PhoneNotificationListener
import com.droidlink.app.ui.MirrorActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Dispatches messages from the Mac. Runs on the socket reader thread, so file chunks stay in order. */
object Router {
    private val main = Handler(Looper.getMainLooper())

    fun onConnected() {
        val ctx = Link.context
        PhoneScreen.send(ctx)
        Battery.sendCurrent(ctx)
        PhoneNotificationListener.instance?.sendAll()
        Media.sendCurrent(force = true)
    }

    fun handle(m: JSONObject) {
        val ctx = Link.context
        val listener = PhoneNotificationListener.instance
        when (m.optString("type")) {
            "ping" -> Link.send(msg("pong"))
            "clipboard" -> main.post { ClipboardBridge.setFromMac(ctx, m.optString("text")) }
            "clipboardRequest" -> main.post { ClipboardBridge.sendOnRequest(ctx) }
            "clipboardImage" -> main.post { ClipboardBridge.setImageFromMac(ctx, m.optString("data"), m.optString("mime", "image/png")) }
            "url" -> Notifications.showLink(ctx, m.optString("url"))
            "notificationReply" -> listener?.reply(m.optString("key"), m.optString("text"))
            "notificationDismiss" -> listener?.dismiss(m.optString("key"))
            "notificationAction" -> listener?.runAction(m.optString("key"), m.optInt("index", -1))
            "getSmsThreads" -> Link.scope.launch { Link.send(Sms.threadsMessage(ctx)) }
            "getSmsMessages" -> {
                val id = m.optLong("threadId")
                Link.scope.launch { Link.send(Sms.messagesMessage(ctx, id)) }
            }
            "smsSend" -> Sms.send(ctx, m.optString("address"), m.optString("text"))
            "callAction" -> Calls.perform(ctx, m.optString("action"))
            "mediaAction" -> main.post { Media.perform(m.optString("action")) }
            "findPhone" -> main.post {
                if (m.optString("action") == "stop") FindPhone.stop() else FindPhone.start(ctx)
            }
            "fileOffer", "fileChunk", "fileEnd" -> FileReceiver.handle(ctx, m)
            "mirrorStart" -> main.post { MirrorActivity.request(ctx, m.optInt("maxSize", 1280)) }
            "mirrorStop" -> main.post { MirrorService.stop(ctx) }
            "mirrorKeyframe" -> MirrorService.instance?.requestKeyFrame()
            "mirrorTap" -> ControlService.instance?.tap(m.optDouble("x"), m.optDouble("y"), m.optString("action") == "long")
            "mirrorSwipe" -> ControlService.instance?.swipe(
                m.optDouble("x"), m.optDouble("y"), m.optDouble("x2"), m.optDouble("y2"), m.optLong("duration", 300)
            )
            "mirrorKey" -> main.post { ControlService.instance?.key(m.optString("action")) }
            "mirrorText" -> main.post { ControlService.instance?.type(m.optString("text")) }
            "getContacts" -> Link.scope.launch { Link.send(Contacts.listMessage(ctx)) }
            "getApps" -> Link.scope.launch { Link.send(Apps.listMessage(ctx)) }
            "launchApp" -> main.post { Apps.launch(ctx, m.optString("pkg")) }
            "dial" -> main.post { Calls.dial(ctx, m.optString("number")) }
            "listPhotos" -> Link.scope.launch { Link.send(Photos.listMessage(ctx)) }
            "getPhoto" -> Photos.send(ctx, m.optString("id"))
            "screenshotSync" -> Photos.setScreenshotSync(ctx, m.optBoolean("on"))
            "findMac" -> if (m.optString("action") == "stopped") MacControl.finding.value = false
        }
    }
}
