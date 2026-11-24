# Shuttle

A private, on-device assistant for Android, built for GrapheneOS and other phones without
Google services. Set it as your default digital assistant and it opens over any app with the
assist gesture or a long-press of the power button. Built with Jetpack Compose and
**Material 3 Expressive**, licensed Apache 2.0.

New here? [How Shuttle works](docs/HOW-IT-WORKS.md) is a plain-language tour of what the app
does and where each part lives in the code. Working on the code? See
[CONTRIBUTING.md](CONTRIBUTING.md).

## What it does today

Shuttle understands typed commands for the phone itself. Everything runs on the device; nothing
is sent anywhere.

| Area | Examples |
| --- | --- |
| Flashlight | "flashlight on", "toggle the torch" |
| Radios | "turn off Wi-Fi", "Bluetooth on", "mobile data off", "NFC on" |
| Other switches | "airplane mode on", "location off", "hotspot on", "dark mode on" |
| Do Not Disturb and ringer | "turn on do not disturb", "silence my phone", "vibrate mode" |
| Screen | "brightness 40%", "dim the screen", "auto-rotate off", "adaptive brightness on" |
| Media | "play", "pause the music", "next track", "volume up", "set volume to 30" |
| Calls and texts | "call Mom", "dial 555 123 4567", "text Sam saying I'm running late" |
| Clock | "set an alarm for 7:30", "wake me up at 6", "timer 10 minutes" |
| Apps and places | "open camera", "navigate to the library" |
| Status | "battery", "what time is it", "what's the date" |

Calls and texts always show who and what first and wait for you to tap **Call** or **Send**.

Chat, web search and voice input aren't in yet (see [Roadmap](#roadmap)). Anything Shuttle
doesn't recognise gets a short "I can't do that yet".

## What Android allows (no root, no Shizuku)

Shuttle uses only what a normal app may do, so some switches need one tap from you:

- **Changes directly:** flashlight, Do Not Disturb, ringer mode, brightness, auto-rotate,
  adaptive brightness, media playback and volume, alarms, timers, calls and texts.
- **Opens the system panel** (you tap the switch): Wi-Fi, mobile data, NFC. Android hasn't let
  apps switch these since Android 10.
- **Asks to confirm:** Bluetooth on (the system "allow Shuttle to turn on Bluetooth?" prompt).
- **Opens the settings page:** Bluetooth off, airplane mode, location, hotspot, dark theme.

## Permissions

Nothing is asked for up front. Each command asks for what it needs the first time, and the
setup screen lets you grant things ahead of time.

| Permission | Used for |
| --- | --- |
| Contacts | Finding people by name for calls and texts |
| Phone | Placing a call you confirmed |
| SMS | Sending a text you confirmed |
| Nearby devices | Showing the "turn on Bluetooth?" prompt (Android 12+) |
| Do Not Disturb access (special) | Do Not Disturb and silent mode |
| Modify system settings (special) | Brightness and auto-rotate |
| Set alarms | Creating alarms and timers in your clock app |

## Set as your assistant

Open Shuttle and tap **Choose** next to *Default assistant*, then pick Shuttle under
**Digital assistant app**. On GrapheneOS that's *Settings › Apps › Default apps › Digital
assistant app*. Then turn on the gesture or power-button long-press there if it's off.

## Build & install

Every push to `main` produces an optimized release APK (R8-shrunk, non-debuggable) signed with
Shuttle's release key; download it from the run's **Artifacts** under **Actions → Build**.
Each build has a higher version code, so it installs as an update over the last one.

To build locally: `./gradlew assembleRelease` (or `assembleDebug`). Unit tests:
`./gradlew testDebugUnitTest`. Requires JDK 17 and an Android SDK with compileSdk 37.

### Releases

Push `release-notes/vX.Y.Z.md` to `main`, or run **Actions › Build release** and pick the
version bump. CI tags the commit and publishes a GitHub Release with the signed APK and a
change log since the previous release.

### Signing

CI signs with a key stored in four repository secrets:

| Secret | Value |
| --- | --- |
| `SHUTTLE_KEYSTORE_BASE64` | The keystore file, base64-encoded (`base64 -w0 shuttle.jks`) |
| `SHUTTLE_KEYSTORE_PASSWORD` | Keystore password |
| `SHUTTLE_KEY_ALIAS` | Key alias |
| `SHUTTLE_KEY_PASSWORD` | Key password |

Without them CI still compiles and runs the tests, but publishes no APK.

## Roadmap

1. **On-device model** for chat and for commands the rules don't cover. The model is chosen and
   updated by the project, downloaded once on first run; no accounts or API keys. A tiny
   tool-calling model bundled in the APK covers commands before the download finishes. The
   tool list it calls is [docs/tools.json](docs/tools.json).
2. **Live information:** web search and page reading, with sources.
3. **Voice:** speech input and spoken replies.
4. **More tools:** notification reading and replies, calendar, screen context.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
