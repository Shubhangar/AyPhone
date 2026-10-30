# AyPhone wire protocol (v1)

The Mac app is the **server**, the Android app is the **client**.

## Transport

- TCP, port `47800`.
- Wi-Fi: the Mac advertises the Bonjour service `_droidlink._tcp`; the phone also remembers the IPs from the pairing QR code.
- USB: the Mac runs `adb reverse tcp:47800 tcp:47800`, and the phone always tries `127.0.0.1:47800` first.
- Finding the Mac on networks that block Bonjour: every 3 s while disconnected, the Mac broadcasts `AYPHONE1 47800 <Mac name>` on UDP port `47801`; the phone also retries addresses it connected to before, then probes its /24 subnet.
- Bluetooth fallback (Wi-Fi with client isolation, no cable): an RFCOMM service with UUID `6f3c1a52-8b7e-4d2a-9c1e-5a7f0b3d2e91` (the phone finds the channel by SDP lookup). It is published by the `AyPhoneBluetooth` helper inside the app bundle, which relays bytes to the app's localhost-only listener on `127.0.0.1:47802`; the helper has to be a separate process because inside the app macOS hands incoming channels from the phone to the hands-free (calls) connection. Framing, encryption and the hello are the same as over TCP. The phone tries Bluetooth last, at most once a minute (sooner after a link that drops right away), and leaves it as soon as USB or Wi-Fi works again.

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

`length` counts everything after the length field. The plaintext is one UTF-8 JSON object with a `type` field. If a frame does not decrypt, the connection is closed. The first frame from the phone must be `hello`.

## Messages

| type | direction | fields |
|---|---|---|
| `hello` | A→M | `name`, `model`, `id` |
| `helloOk` | M→A | `name`, `model` ("MacBook Pro"…) |
| `ping` / `pong` | both | |
| `battery` | A→M | `level` (0-100), `charging` |
| `macBattery` | M→A | `level` (0-100), `charging`: the Mac's battery, on connect and when it changes (not sent by Macs without one) |
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
| `notification` | A→M | `key`, `pkg`, `app`, `title`, `text`, `time` (ms), `canReply`, `actions` [String], `icon` (base64 PNG), `silent` (true when re-sent on connect), `alert` (true when this post or update carries a new incoming message) |
| `notificationRemoved` | A→M | `key` |
| `notificationReply` | M→A | `key`, `text` |
| `notificationDismiss` | M→A | `key` |
| `notificationAction` | M→A | `key`, `index` |
| `clipboard` | both | `text` |
| `url` | both | `url` |
| `fileOffer` | both | `id`, `name`, `size`, `mime`, `glow` (sent from the Files option: the other side plays the transfer glow) |
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

