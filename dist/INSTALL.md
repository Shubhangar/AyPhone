# Installing AyPhone

AyPhone connects an Android phone to a Mac: notifications with reply, calls, texts, files, clipboard, and more. Everything travels directly between your devices over Wi-Fi, USB or Bluetooth, with no account and no cloud.

You need both apps:

| File | For |
|---|---|
| `AyPhone-0.1.6-mac.dmg` | Mac, macOS 14 Sonoma or later (Apple silicon or Intel) |
| `AyPhone-0.1.6-android.apk` | Android 10 or later |

## 1. Mac

1. Open `AyPhone-0.1.6-mac.dmg` and drag **AyPhone** onto **Applications**.
2. Open AyPhone from Applications. The first time, macOS says it can't verify the developer, because AyPhone isn't notarized by Apple yet:
   - Click **Done** (or **OK**).
   - Open **System Settings → Privacy & Security**, scroll down, and click **Open Anyway** next to AyPhone. Confirm with your password or Touch ID.
   - On macOS 14 you can instead right-click AyPhone in Applications and choose **Open**.
3. Allow **notifications** and **local network** access when asked.
4. Recommended: in **System Settings → Notifications → AyPhone**, choose **Alerts**, so phone notifications stay on screen until you act on them.

AyPhone lives in the menu bar and keeps running when you close its window.

## 2. Android phone

1. Get the APK onto your phone: Quick Share, Google Drive, email, or a USB cable.
2. Tap it to install. Android asks you to allow installs from the app you opened it with (Files, Chrome, Drive…). Allow it, then go back and tap **Install**.
   - If Play Protect warns that the app is unknown, tap **More details → Install anyway**.
3. Open **AyPhone** and tap **Pair with Mac**, then scan the QR code shown on the Mac's **Pairing** page. Both devices need to be on the same Wi-Fi.
4. Work through the **Setup** list in the app: notification access, messages/contacts/calls, display over other apps, call controls from Mac, and unrestricted battery. **Call controls from Mac** needs the phone paired with the Mac in Bluetooth settings; Android then asks to let AyPhone manage your Mac (it may call it a watch, the only companion type Android lets apps use). It lets the Mac switch a call to the phone's speaker, mute it and hang up, for phone and WhatsApp-style calls. On Samsung phones also check **Settings → Battery → Background usage limits** and make sure AyPhone isn't put to sleep.

That's it. From now on the phone reconnects automatically whenever both devices are on the same network.

## When the Wi-Fi blocks the connection

Some networks (offices, hotels, some home routers) don't let devices on the same Wi-Fi talk to each other. AyPhone first tries other ways to find the Mac on the network. If none work, it falls back to Bluetooth, so notifications, messages, calls and the clipboard keep working:

1. Pair the phone with the Mac in **System Settings → Bluetooth** (if you haven't already).
2. On the phone, allow **Nearby devices** when AyPhone asks (or in its Setup list).
3. On the Mac, allow **Bluetooth** access for AyPhone when asked.

The phone then shows **Connected … over Bluetooth**. It can take up to a minute to switch over. Screen mirroring and app windows need Wi-Fi or USB, and the phone moves back to Wi-Fi or USB as soon as either works again.

## Mirroring your phone's screen

Click **Mirror screen** in AyPhone on the Mac and tap **Start now** on the phone. No developer settings needed. Android asks each time before sharing the screen. To click, swipe and type on the phone from the Mac, turn on **Control from Mac** in the phone app's Setup list (if Android says it's restricted: App info → ⋮ → **Allow restricted settings**).

## Clipboard

Copying on the Mac puts the text on the phone automatically. For the other direction, copy on the phone and just press **⌘V** on the Mac: AyPhone fetches the phone's clipboard first, then pastes. This needs one Mac setting: in AyPhone's overview, click **Allow…** next to "⌘V pastes what you copied on the phone", and turn AyPhone on under **System Settings → Privacy & Security → Accessibility**. It's a Mac permission only and changes nothing on the phone. The phone has to be unlocked (Android only shares its clipboard then) and needs **Display over other apps** allowed. When the phone is locked, ⌘V pastes the Mac's clipboard as usual.

You can also press **⌃⌥V** (Control-Option-V) to fetch the phone's clipboard without pasting.

On the phone you can also tap **Send clipboard** in AyPhone's notification, or use **Share → Send to Mac**.

The phone's optional **Control from Mac** setting (an accessibility service) also sends copies to the Mac automatically and lets you click and type on the mirrored screen. Some banking apps refuse to open while any accessibility service like this is on, so leave it off if you use them. Everything else works without it.

## Optional: advanced features (developer options)

Not needed for everyday use. In AyPhone on the Mac, **Pairing → Advanced features** adds phone apps in their own Mac windows, the phone camera, advanced mirroring, a USB connection and fully automatic clipboard. These need **USB debugging** on the phone (Settings → About phone → Software information → tap **Build number** 7 times, then Developer options → **USB debugging**) and adb on the Mac (`brew install android-platform-tools`). Leave both off if you don't use these features: some banking and payment apps refuse to run while developer options are on.

## Optional: talk on phone calls through the Mac

Regular phone calls can be answered on the Mac and moved back to the phone mid-call. This works over Bluetooth (Android doesn't let apps carry call audio any other way) and is experimental:

1. On the Mac, open **System Settings → Bluetooth** and pair your phone.
2. In AyPhone on the Mac, open **Pairing** and turn on **Use this Mac for phone calls**. Allow Bluetooth and microphone access when asked.

Incoming calls then offer **Answer on Mac** and **On phone**, and during a call **Move to phone** / **Move to Mac**.

Some versions of macOS don't let apps take phone-call audio. On those Macs, AyPhone still answers, declines and ends calls from the Mac, but you talk on the phone; the calls card on the Pairing page says so once it notices. Click **Speaker** in the call panel to put the call on the phone's loudspeaker so you don't have to pick the phone up (needs **Call controls from Mac** in the phone's setup list).

## Checking the downloads

SHA-256 checksums for version 0.1.6:

```
2ff4c9cbd9b699af245d77f36f814194594a4425cf8dd6dee8adfdb39e697b9b  AyPhone-0.1.6-mac.dmg
d59e2394d07235997499b0a72add57679f63322504feed01d797b64c76f2037a  AyPhone-0.1.6-android.apk
```

On a Mac: `shasum -a 256 <file>`.
