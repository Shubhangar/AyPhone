package com.droidlink.app

import android.app.Application
import com.droidlink.app.link.Link
import com.droidlink.app.link.Pairing
import com.droidlink.app.service.LinkService

class AyPhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Link.init(this)
        if (Pairing.load(this) != null) LinkService.start(this)
    }
}
