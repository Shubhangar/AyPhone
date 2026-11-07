# AyPhone wire protocol (v1)

The Mac app is the **server**, the Android app is the **client**.

## Transport

- TCP, port `47800`.
- Wi-Fi: the Mac advertises the Bonjour service `_droidlink._tcp`; the phone also remembers the IPs from the pairing QR code.
- USB: the Mac runs `adb reverse tcp:47800 tcp:47800`, and the phone always tries `127.0.0.1:47800` first.
- Finding the Mac on networks that block Bonjour: every 3 s while disconnected, the Mac broadcasts `AYPHONE1 47800 <Mac name>` on UDP port `47801`; the phone also retries addresses it connected to before, then probes its /24 subnet.
- Bluetooth fallback (Wi-Fi with client isolation, no cable): an RFCOMM service with UUID `6f3c1a52-8b7e-4d2a-9c1e-5a7f0b3d2e91` (the phone finds the channel by SDP lookup). It is published by the `AyPhoneBluetooth` helper inside the app bundle, which relays bytes to the app's localhost-only listener on `127.0.0.1:47802`; the helper has to be a separate process because inside the app macOS hands incoming channels from the phone to the hands-free (calls) connection. Framing, encryption and the handshake are the same as over TCP. The phone tries Bluetooth last, at most once a minute (sooner after a link that drops right away), and leaves it as soon as USB or Wi-Fi works again.

## Reconnecting

Each attempt can wake a sleeping Mac (over Bluetooth especially), so the phone doesn't poll one. After `macSleep`, or after three tries in a row that don't finish the handshake, it stops trying and waits for a sign the Mac is back:

- the Mac's UDP beacon (`AYPHONE1 <port> <name>` to port 47801 every 3 seconds, broadcast and also sent directly to the last three addresses the phone was reached at over Wi-Fi, since a dozing phone drops broadcasts) or its Bonjour announcement, both only while the Mac is awake and has no phone,
- the Mac's Bluetooth connecting to the phone (it reconnects the hands-free link as it wakes; the phone ignores connections its own attempts cause),
- the phone's screen coming on, the phone being unlocked or AyPhone opened,
- the phone joining a network.

The phone listens for the beacon even while connected. Hearing it then means the Mac has lost the link (it restarted, or slept while the phone dozed and missed the connection closing), so the phone sends a `ping`: on a dead connection the Mac answers with a TCP reset and the phone reconnects at once.

## Pairing

The Mac generates a random 32-byte key and shows it as a QR code:

```
droidlink://pair?k=<base64url key>&h=<ip1>,<ip2>&p=47800&n=<Mac name>
```

Whoever holds the key is trusted. "Reset pairing" on the Mac rotates the key and unpairs every phone.

## Framing and encryption

Each frame is:

```
[u32 big-endian length][12-byte nonce][AES-256-GCM ciphertext][16-byte tag]
```

`length` counts everything after the length field. The plaintext is one UTF-8 JSON object with a `type` field. If a frame does not decrypt, the connection is closed.

### Session handshake

The pairing key only opens a session, so frames recorded from one connection can't be replayed into another:

1. The phone sends `auth` with `nonce` (16 random bytes, base64), sealed with the pairing key and a random GCM nonce.
2. The Mac answers `authOk` with its own 16-byte `nonce`, sealed the same way.
3. Both derive the session key: HKDF-SHA256 with the pairing key as input, `phoneNonce ‖ macNonce` as salt, info `AyPhone session`, 32 bytes.
4. Every later frame uses the session key, with a counter for its GCM nonce instead of a random one: byte 0 is the direction (`0x50` phone→Mac, `0x4D` Mac→phone), bytes 1-3 are zero, bytes 4-11 are a big-endian counter starting at 0 for each direction. A frame whose nonce isn't the next expected one is refused and the connection closed.
5. The phone's first session frame is `hello`. Only then does the Mac drop any previous phone connection and switch to this one, so a replayed `auth` can't push the real phone off.

## Messages

| type | direction | fields |
|---|---|---|
| `auth` | A→M | `nonce`: see Session handshake (pairing key) |
| `authOk` | M→A | `nonce`: see Session handshake (pairing key) |
| `hello` | A→M | `name`, `model`, `id` |
| `helloOk` | M→A | `name`, `model` ("MacBook Pro"…) |
| `ping` / `pong` | both | |
| `battery` | A→M | `level` (0-100), `charging` |
| `macBattery` | M→A | `level` (0-100), `charging`: the Mac's battery, on connect and when it changes (not sent by Macs without one) |
| `macSleep` | M→A | The Mac is going to sleep (also the answer to `hello` while it's only briefly awake in the dark): the phone stops trying until it's back, see Reconnecting |
| `deck` | M→A | The Deck for the app in front on the Mac: `name`, `pkg` (bundle ID), `on` (it has its own layout), `icon` (96 px PNG, base64), `color` (its main colour, `#RRGGBB`), `deck`: buttons `{id, label, icon, action, section?, style?, hint?}` (`style` is `primary` or `danger`; `hint` is the shortcut, like `⌥⌘K`). Sent on connect and whenever another app comes to the front |
| `scanRequest` | M→A | `action`: `document`, `text` or `photo`; the phone opens its camera in that mode (Scan with phone) |
| `macFeatures` | M→A | `on`: the Mac can make "While you were away" summaries (Apple Intelligence); `alert`: they're switched on. On connect and when the switch changes |
| `awaySummary` | A→M | `on`: switch "While you were away" on or off from the phone |
| `macScreen` | M→A | `width`, `height`: the Mac's main screen in points, for the Pad's drawing area (on connect) |
| `padPointer` | A→M | `state` (`down`, `move`, `up`), `points`: x, y, pressure triples in 0…1 of the Mac's screen; the Mac clicks and drags there (Pad → Draw) |
| `padKeys` | A→M | `action`: `cmd+z` or `cmd+shift+z`, Undo and Redo in the app being drawn in |
| `ink` | A→M | Pad → Mark up, ink over the Mac's screen. `state`: `start` (`id`, `index` colour as 0xRRGGBB, `width`, `on` highlighter, first `points`), `add` (`points`: x, y pairs in 0…1), `remove` (stroke ids in `text`, comma-separated: undo and the eraser), `clear`, `save` (a screenshot with the ink on the Desktop), `done` |
| `insertText` | A→M | `text`: read from a photo on the phone; the Mac puts it on the clipboard and pastes it into the app in front |
| `insertImage` | A→M | `data` (base64), `mime`: a photo from the phone, pasted into the app in front the same way |
| `deckPress` | A→M | `id`: a Deck button was tapped; the Mac presses its shortcut (or media/lock action) in that app |
| `deckDone` | M→A | `id`, `on`: whether the press went through (shortcuts need Accessibility on the Mac) |
| `macNowPlaying` | M→A | `playing`, `title`, `artist`: what the Mac is playing, on connect and when it changes (read through `/usr/bin/perl`, which macOS lets see it; see `mac/NowPlaying`) |
| `macMuted` | M→A | `on`: whether the Mac's sound is muted, on connect and after `macMute` |
| `macMute` | A→M | `on`: mute or unmute the Mac's sound |
| `mirrorRequest` | A→M | `on`: start (`true`) or stop screen mirroring from the phone |
| `macLock` | A→M | lock the Mac's screen |
| `findMac` | both | `action`: `start`/`stop` (A→M, ring the Mac loudly); `stopped` (M→A, stopped on the Mac) |
| `macMedia` | A→M | `action` (`playPause`/`next`/`previous`/`volumeUp`/`volumeDown`) for whatever plays on the Mac |
| `pointer` / `scroll` | A→M | `x`, `y`: pointer movement or scroll amount in points (the phone as a trackpad) |
| `click` | A→M | `action` (`left`/`right`/`double`, or `down`/`up` around a drag) |
| `type` / `key` | A→M | `text` to type, or `action` (`return`/`delete`/`tab`/`escape`/`space`/arrows) |
| `dial` | M→A | `number`: the phone calls it; `dialFailed` (A→M, `action: permission`) if it may not |
| `clipboardImage` | both | `data` (base64), `mime` (`image/png`/`image/jpeg`): an image copied on either side (at most 3000 px) |
| `listPhotos` | M→A | ask for the latest photos |
| `photos` | A→M | `photos` [{`id`,`name`,`date` (ms),`thumb` (base64 JPEG, 240 px),`screenshot`}], `on` (screenshots sent automatically), `action: permission` when not allowed |
| `getPhoto` | M→A | `id`: the phone sends that photo like any file |
| `screenshotSync` | M→A | `on`: send each new screenshot to the Mac (default on) |
| `notification` | A→M | `key`, `pkg`, `app`, `title`, `text`, `time` (ms), `canReply`, `actions` [String], `icon` (base64 PNG), `silent` (true when re-sent on connect), `alert` (true when this post or update carries a new incoming message), `category` (the Android notification category, e.g. `msg`, `email`, `missed_call`, `event`, `reminder`, `promo`; absent when the app sets none) |
| `notificationRemoved` | A→M | `key` |
| `notificationReply` | M→A | `key`, `text` |
| `notificationDismiss` | M→A | `key` |
| `notificationAction` | M→A | `key`, `index` |
| `clipboard` | both | `text` |
| `url` | both | `url` |
| `fileOffer` | both | `id`, `name`, `size`, `mime`, `glow` (sent from the Files option: the other side plays the transfer glow), `action` (`open`: open it once it's in, for a scanned document) |
| `fileChunk` | both | `id`, `seq`, `data` (base64) |
| `fileEnd` | both | `id` |
| `getSmsThreads` | M→A | |
| `smsThreads` | A→M | `threads` [{`id`,`address`,`name`,`snippet`,`date`,`unread`}] |
| `getSmsMessages` | M→A | `threadId` |
| `smsMessages` | A→M | `threadId`, `messages` [{`id`,`threadId`,`address`,`body`,`date`,`outgoing`}] |
| `smsSend` | M→A | `address`, `text` |
| `smsChanged` | A→M | |
| `call` | A→M | `state` (`ringing`/`offhook`/`idle`), `number`, `name`; from the call companion also `telecom: true` and, while a second call rings during this one, `waitingNumber`/`waitingName` |
| `callAction` | M→A | `action` (`answer`/`decline`/`end`/`mute`/`speaker`, which toggles the loudspeaker) |
| `callSpeaker` | A→M | `on`: whether the call is on the phone's loudspeaker (after each `speaker` action) |
| `callMute` | A→M | `on`: whether the phone's microphone is muted for the call |
| `callerInfo` | A→M | `name` (title of the dialer's call notification: a name, or the number if unsaved) |
| `media` | A→M | `app`, `title`, `artist`, `playing`, `art` (base64 JPEG); no `title` means nothing is playing |
| `mediaAction` | M→A | `action` (`play`/`pause`/`next`/`previous`/`volumeUp`/`volumeDown`) |
| `getContacts` | M→A | |
| `contacts` | A→M | `contacts` [{`name`,`numbers` [String]}] |
| `getApps` | M→A | |
| `apps` | A→M | `apps` [{`pkg`,`name`,`icon` (base64 PNG)}] |
| `launchApp` | M→A | `pkg` |
| `findPhone` | M→A | `action` (`start`/`stop`) |
| `mirrorStart` / `mirrorStop` | M→A | `maxSize` (long side, px) |
| `mirrorState` | A→M | `state` (`requesting`/`running`/`denied`/`stopped`/`error`), `control` |
| `mirrorConfig` | A→M | `width`, `height`, `data` (H.264 SPS+PPS, Annex B, base64) |
| `mirrorFrame` | A→M | `data` (H.264 access unit, Annex B, base64), `keyFrame` |
| `mirrorKeyframe` | M→A | request a fresh key frame |
| `mirrorAudio` | A→M | `data` (48 kHz 16-bit stereo PCM, base64) |
| `mirrorTap` | M→A | `x`, `y` (0–1), `action` (`tap`/`long`) |
| `mirrorSwipe` | M→A | `x`, `y`, `x2`, `y2` (0–1), `duration` (ms) |
| `mirrorKey` | M→A | `action` (`back`/`home`/`recents`/`notifications`) |
| `mirrorText` | M→A | `text` (`\b` deletes, `\n` presses Enter) |

### Clipboard on request

- Mac → phone `clipboardRequest`: the phone briefly opens an invisible activity (allowed by "Display over other apps"), reads the clipboard while it has focus, and replies with `clipboard`.
- Phone → Mac `clipboardUnavailable` with `action` = `locked` (phone locked), `overlay` (permission missing), `empty`, or `failed`.

