# Shuttle

A private, on-device assistant for Android, built for GrapheneOS and other phones without
Google services. Set it as your default digital assistant and it opens over any app with the
assist gesture or a long-press of the power button. Built with Jetpack Compose and
**Material 3 Expressive**, licensed Apache 2.0.

New here? [How Shuttle works](docs/HOW-IT-WORKS.md) is a plain-language tour of what the app
does and where each part lives in the code. Working on the code? See
[CONTRIBUTING.md](CONTRIBUTING.md).

## Two ways in

- **The assist gesture** (or a long-press of the power button) opens a small panel at the bottom
  of the screen, over whatever you're doing. It starts listening straight away.
- **The launcher icon** opens a full-screen chat, laid out like the other apps in the family: a
  search bar at the top for searching the conversation, and the settings gear on its right.

Both run the same commands the same way.

### Chats

Chats in the full-screen view are saved as you go, on the phone only. Tap the search bar to see
them, newest first, and type to search every chat. Tap one to reopen it, or touch and hold it to
delete it (with Undo). **New chat** is at the top of that list, and the **+** next to the gear
starts one straight away. Each time you open Shuttle from the launcher, it starts a new chat.

Reopened chats are read-only history for anything that was waiting on you: an old "Send?" card
can't send. Quick commands from the assist panel aren't saved. Settings › Chats turns saving
off and deletes every saved chat.

## What it does today

Shuttle understands spoken or typed commands for the phone itself. Everything runs on the
device; nothing is sent anywhere.

| Area | Examples |
| --- | --- |
| Flashlight | "flashlight on", "toggle the torch" |
| Radios | "turn off Wi-Fi", "Bluetooth on", "mobile data off", "NFC on" |
| Other switches | "airplane mode on", "location off", "hotspot on", "dark mode on" |
| Do Not Disturb and ringer | "turn on do not disturb", "silence my phone", "vibrate mode" |
| Screen | "brightness 40%", "dim the screen", "auto-rotate off", "adaptive brightness on" |
| Media | "play", "pause the music", "next track", "volume up", "set volume to 30" |
| Calls and texts | "call Mom", "dial 555 123 4567", "text Sam saying I'm running late" |
| Clock | "set an alarm for 7:30", "wake me up at quarter to 7", "timer 10 minutes", "remind me in 5 minutes" |
| Apps and places | "open camera", "navigate to the library" |
| Status | "battery", "what time is it", "what's the date" |
| Is it on? | "is Wi-Fi on?", "am I on silent?", "is the flashlight still on?", "what's the volume at?", "what's on?" |

These are only examples: each command understands many everyday wordings ("kill the wifi",
"it's too bright", "make it louder", "let Sam know I'm late", "wake me up at half past six",
"remind me in 10 minutes", "where's the nearest pharmacy"), and ignores hesitations and polite
extras ("um, could you… please"). Several commands can go in one sentence: "turn off wifi and
bluetooth", "set an alarm for 7 and a timer for 10 minutes". Shuttle only acts when it
understands every part.

It also answers the social things people say: hello, thanks, "what can you do?", and "never
mind" (which closes the panel).

Asking about a setting you can switch offers to flip it ("Wi-Fi is on. Turn it off?"); say or
tap yes. Android doesn't let apps check the hotspot, so Shuttle offers its settings instead. The
flashlight is known once Android has reported it, which happens as the panel opens.

Calls and texts always show who and what first and wait for you to tap **Call** or **Send**,
or to say yes or no.

## The on-device model

Anything the commands above don't cover (questions, conversation, unusual wordings) goes to
**Granite 4.2 3B**, IBM's Apache 2.0 model, running entirely on the phone through
[llama.cpp](https://github.com/ggml-org/llama.cpp). There's no account, no API key and no usage
limit, and nothing you ask leaves the phone.

- **Download once:** Settings › Model › Download (2.1 GB, Wi-Fi only by default). It continues
  with Shuttle closed, shows progress in the notification shade, resumes after a dropped
  connection, and pauses on mobile data while "Wi-Fi only" is on. Until then, phone commands
  work and other requests get a short note saying the model isn't downloaded yet.
- **GrapheneOS Network permission:** GrapheneOS asks about network access only when an app is
  first installed; an update that adds it (like this one) starts with it off, and there's no
  prompt an app can show. If it's off, Settings › Model says so and opens App info, where it's
  under Permissions › Network.
- **Checked before use:** the download's SHA-256 must match the one in
  [models/manifest.json](models/manifest.json); anything else is deleted.
- **Updates:** the project chooses the model in `models/manifest.json`. When it changes,
  Settings offers the update. Users never pick or configure a model.
- **Tools:** the model can use every phone command. Calls and texts it starts still stop at the
  confirmation card, and it can't skip it.
- **Speed:** the model's instructions are read once per model and the result is saved, so
  later openings restore it in a moment and each question only has to read your words. The
  build used (Q4_0) is one llama.cpp runs especially fast on phone CPUs. Replies stream in as
  they're written, and **Stop** works at any point.
- **Memory:** about 3 GB while loaded. It loads when Shuttle opens (if downloaded) and unloads
  after ten quiet minutes.
- **No internet yet:** the model answers from what it learned in training, so for things that
  change (who holds an office, news, prices) it says its answer may be out of date (see
  [Roadmap](#roadmap)).

## Voice

Shuttle has no speech engine of its own: it listens through your phone's default speech
recogniser and speaks through your default text-to-speech engine, so whichever on-device engine
you've chosen (Distillery, or any other) is the one used. Change them in Settings and Shuttle
follows.

- The panel starts listening as soon as it opens; tap the mic to speak again, or start typing to
  use the keyboard instead. Tap the moving indicator to stop listening early.
- Answers to spoken requests are spoken back; typed requests get text only.
- For a call or text, Shuttle reads out who and what and asks "Send it?". Only a clear yes or no
  counts; anything else is taken as a new request and the card stays on screen to tap.
- The chat from the launcher icon doesn't start listening on its own; tap the mic.
- Both behaviours can be turned off in Settings (*Listen when the panel opens*, *Speak replies*).

## What Android allows (no root, no Shizuku)

Shuttle uses only what a normal app may do, so some switches need one tap from you:

- **Changes directly:** flashlight, Do Not Disturb, ringer mode, brightness, auto-rotate,
  adaptive brightness, media playback and volume, alarms, timers, calls and texts.
- **Opens the system panel** (you tap the switch): Wi-Fi, mobile data, NFC. Android hasn't let
  apps switch these since Android 10.
- **Asks to confirm:** Bluetooth on (the system "allow Shuttle to turn on Bluetooth?" prompt).
- **Opens the settings page:** Bluetooth off, airplane mode, location, hotspot, dark theme.

## Permissions

Nothing is asked for up front. Each command asks for what it needs the first time, and
Settings (the gear in the chat's search bar) lets you grant things ahead of time.

| Permission | Used for |
| --- | --- |
| Contacts | Finding people by name for calls and texts |
| Phone | Placing a call you confirmed |
| SMS | Sending a text you confirmed |
| Nearby devices | Showing the "turn on Bluetooth?" prompt (Android 12+) |
| Internet (GrapheneOS: the Network permission in App info) | Downloading the model once, and checking `models/manifest.json` for model updates. Requests never go online |
| Wi-Fi and network state (granted at install) | Answering "is Wi-Fi on?" and "is mobile data on?" |
| Notifications (Android 13+, asked when you tap Download) | Showing the model download's progress |
| Microphone | Voice input; speech recognisers only accept apps that hold it. The recogniser does the recording |
| Do Not Disturb access (special) | Do Not Disturb and silent mode |
| Modify system settings (special) | Brightness and auto-rotate |
| Set alarms | Creating alarms and timers in your clock app |

## Set as your assistant

Open Shuttle, tap the gear in the search bar, and tap **Choose** next to *Default assistant*, then pick Shuttle under
**Digital assistant app**. On GrapheneOS that's *Settings › Apps › Default apps › Digital
assistant app*. Then turn on the gesture or power-button long-press there if it's off.

## Build & install

Every push to `main` produces an optimized release APK (R8-shrunk, non-debuggable) signed with
Shuttle's release key; download it from the run's **Artifacts** under **Actions → Build**.
Each build has a higher version code, so it installs as an update over the last one.

Until the release signing secrets are set (see [Signing](#signing)), each run instead publishes
**Shuttle-debug-…**, a debug APK signed with a throwaway test key that's kept in the repository
(`app/debug.keystore`). It installs next to release builds as *Shuttle Debug*, and each debug
build updates the last one. It's for testing only: the key is public, and releases never use it.

The repository includes llama.cpp as a git submodule, so clone with
`git clone --recursive` (or run `git submodule update --init` after cloning).

To build locally: `./gradlew assembleRelease` (or `assembleDebug`). Unit tests:
`./gradlew testDebugUnitTest`. Requires JDK 17, an Android SDK with compileSdk 37, and the Android NDK and CMake (Android Studio
installs both when asked).

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

1. **Live information:** web search and page reading, with sources, so the model can answer
   questions about current events.
2. **More tools:** notification reading and replies, calendar, screen text.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
