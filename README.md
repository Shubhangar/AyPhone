# AyPhone

AyPhone connects an Android phone to a Mac over local Wi-Fi or USB (or Bluetooth when the Wi-Fi keeps them apart), with no cloud and no account. It is modeled on LinkMyMac.

## Download

You need both apps:

| | Download | Needs |
|---|---|---|
| **Mac** | [AyPhone-0.1.3-mac.dmg](https://github.com/Shubhangar/AyPhone/releases/download/v0.1.3/AyPhone-0.1.3-mac.dmg) | macOS 14 or later, Apple silicon or Intel |
| **Android phone** | [AyPhone-0.1.3-android.apk](https://github.com/Shubhangar/AyPhone/releases/download/v0.1.3/AyPhone-0.1.3-android.apk) | Android 10 or later |

Then follow the **[installation guide](dist/INSTALL.md)**: drag the Mac app to Applications, install the APK on the phone, and pair by scanning the QR code on the Mac. All versions are on the [Releases page](https://github.com/Shubhangar/AyPhone/releases/latest).

## What it does


| Feature | How |
|---|---|
| Reconnects when the Mac wakes | The Mac re-announces itself over Bonjour on wake; the phone retries immediately on that or any network change |
| Notifications with reply | Native macOS notifications with Reply and each app's own buttons (Mark as read…); clearing on either device clears both. Set AyPhone to **Alerts** in System Settings → Notifications to keep them on screen until you act |
| Calls from any app | Phone, WhatsApp, Telegram… show a floating panel in the top-right corner (every Space, over full screen) with the call's real Answer/Decline/Hang up buttons; it rings and stays until the call is handled on either device |
| Messages | Browse, read and send SMS; contact names and New Message suggestions |
| Calls | Banner with Answer/Decline, then Speaker/Mute/End while on a call; caller names from your synced contacts (audio stays on the phone). Speaker works through Android's companion-device approval (the phone's "Call controls from Mac" setup step), which lets AyPhone steer phone and WhatsApp-style calls; End closes the panel at once even when the calling app is slow to confirm |
| Notification controls | Switch phone notifications and calls on or off for the Mac (window or menu bar), mute single apps, or skip apps that are also installed on the Mac (Teams, Slack, WhatsApp…) |
| Calls through the Mac (experimental) | With the phone paired over Bluetooth, the Mac acts as a hands-free device: Answer on Mac, Move to phone / Move to Mac, mute. Recent macOS versions refuse call audio to apps; then calls are answered/ended from the Mac with audio on the phone |
| Works on locked-down Wi-Fi | Finds the Mac without Bonjour (broadcast beacon, remembered addresses, a network search); on Wi-Fi that isolates devices, notifications, messages, calls and clipboard carry on over Bluetooth (mirroring and app windows need Wi-Fi or USB) |
| Contacts | Names and numbers sync to the Mac on every connect |
| Files | Drop on the window or Dock icon, Finder right-click → Send to Phone, phone share sheet; drag received files out to Finder |
| Clipboard | Mac→phone automatic; phone→Mac with ⌃⌥V on the Mac (Paste from phone, no accessibility service needed), or tile/notification/share; fully automatic with the optional accessibility service or a one-time USB setup |
| Screen mirroring | Standard (no debugging): Android screen capture → hardware H.264 → Mac window, with app sound; optional click/swipe/type control through an accessibility service. Android asks to allow sharing each time |
| Advanced features (off by default) | With USB debugging: scrcpy apps in Mac windows, phone camera, advanced mirroring (also over Wi-Fi after plugging in once), USB link, automatic clipboard. While off, the Mac app never runs adb |
| App control | Grid of the phone's apps; open on the phone, or in a Mac window |
| Media remote | Now playing with album art, play/pause/skip, volume |
| Battery, Find my phone, open links on the other device | ✅ |
| Connection | One-time QR pairing, AES-256-GCM, Bonjour discovery, USB forwarding turns on automatically when you plug in |

The wire protocol is documented in [PROTOCOL.md](PROTOCOL.md).

## Mac app (`mac/`)

SwiftUI app, macOS 14+. It runs as a window plus a menu bar icon and keeps running when the window is closed.

```bash
cd mac && ./scripts/build-app.sh && open ~/Applications/AyPhone.app
```

`swift run` also works for quick iteration, but macOS notifications only work from the bundled `.app`.

The views use `@ViewState` (defined in `Views/ViewState.swift`) instead of `@State`. On this SDK, `@State` is a macro that only full Xcode ships, so the wrapper lets the app build with just the Command Line Tools. With Xcode installed, you can switch back to `@State`.

## Installers

Ready-to-install builds are in `dist/`, with `dist/INSTALL.md` for whoever you share them with. To rebuild them after changes (bump the version in `mac/Resources/Info.plist` and `android/app/build.gradle.kts` first):

- **Mac:** `mac/scripts/make-dmg.sh` builds a universal (Apple silicon + Intel) app into `dist/AyPhone-<version>-mac.dmg`. It's ad-hoc signed, so other Macs need "Open Anyway" on first launch; notarizing needs an Apple Developer ID ($99/year).
- **Android:** `android/scripts/make-release.sh` builds a signed `dist/AyPhone-<version>-android.apk`. The signing key is `android/keystore/ayphone-release.jks`, with its password in `android/keystore.properties`. **Back both up privately.** Phones only accept updates signed with the same key, and if it's lost, everyone has to uninstall and reinstall.

## Logo and icons

The logo lives in `assets/logo.svg`. If you change it:

- **Mac:** run `mac/scripts/make-icon.sh` to rebuild `mac/Resources/AppIcon.icns` (every size, with macOS corner rounding and shadow), then rebuild the app.
- **Android:** run `python3 android/scripts/make-icons.py` to regenerate the launcher icon (`ic_launcher_*`), themed monochrome icon, status-bar icon (`ic_stat_ayphone`) and in-app logo (`ic_logo`) as vector drawables from the SVG's paths and gradients.
- **Menu bar:** the silhouette in `mac/Sources/AyPhone/LogoGlyph.swift` holds the SVG's paths; paste in the new ones.

The UI colours come from the logo: violet `#895AFF` and magenta `#F36BFF` accents over night versions of the logo's purple and blue (`#170B2E` → `#041E33`, from `#361A6A` → `#084B7B`) for readable text, with glass panels (`Theme.swift` → `GlassBackground` on the Mac, `GlassFill`/`GlassEdge` in the Android `MainActivity`).

The Mac app's ID is `com.ayphone.mac` (it was `com.droidlink.mac`; `SettingsMigration.swift` carries settings and pairing across). The other internal IDs (`com.droidlink.app`, `_droidlink._tcp`, `droidlink://`) still use the original working name. Changing them makes both systems treat AyPhone as a new app (re-pairing and re-granting every permission), so do it once, before a public release.

## Android app (`android/`)

Kotlin and Jetpack Compose, Android 10+ (minSdk 29).

1. Open the `android/` folder in Android Studio and let it sync. It downloads Gradle 8.9 and generates the wrapper.
2. Run it on your phone.
3. Tap **Pair with Mac** and scan the QR code on the Mac's **Pairing** screen.
4. Work through the setup checklist: notification access, SMS/phone permissions, and unrestricted battery.

### Building and installing without Android Studio

`android/scripts/setup-and-build.sh` installs the toolchain into `~/Library` and builds `app/build/outputs/apk/debug/app-debug.apk`. To install it on a plugged-in phone:

```bash
~/Library/Android/sdk/platform-tools/adb install -r --user 0 android/app/build/outputs/apk/debug/app-debug.apk
```

`--user 0` matters on Samsung phones: without it, adb also installs a copy into the Dual Messenger profile, and you end up with two AyPhone icons.

### USB mode

Turn on USB debugging on the phone and plug it in. The Mac app watches for phones (`adb track-devices`) and runs `adb reverse tcp:47800 tcp:47800` automatically, including after replugging or waking. The phone always tries USB first and falls back to Wi-Fi. USB also unlocks mirroring, apps in Mac windows, the phone camera, and the one-time automatic-clipboard setup.

## Known limits / ideas for next steps

- **Video calls with the Mac's camera**: not possible through the phone. Android doesn't let one app feed a different camera into another (WhatsApp, Teams…) without root. Answer video calls in the app's Mac version instead; "Skip apps that are also on this Mac" stops the phone from ringing there too.
- **Bluetooth calls**: built on macOS's IOBluetooth hands-free API; not yet verified on every phone. Audio quality is Bluetooth call quality.
- **System-wide virtual webcam** (phone as a camera in Zoom/FaceTime): needs a CoreMediaIO camera extension, which requires full Xcode and a paid Apple developer account. For now, the Phone camera window can be captured with OBS's virtual camera.
- **Automatic clipboard caveat**: it relies on Android logging its clipboard refusal (the KDE Connect approach). Android 13+ may ask once to allow log access, and the invisible reader briefly takes focus when you copy.
- **Photo gallery browser and Android file browsing**: add `listPhotos` / `listDir` requests to the protocol.
- **MMS / RCS**: only SMS is read. Google Messages RCS chats still show up as mirrored notifications with reply.
- **Security hardening**: store the Mac's pairing key in the Keychain (it's in UserDefaults now) and add replay protection (a frame counter in the nonce).
- **Play Store**: Google restricts SMS and call-log permissions to default SMS/dialer apps, so a store build would need to drop or justify them. Sideloading works as is.
- Only one phone is connected at a time; a new phone replaces the current one.
