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

Shuttle understands spoken or typed commands for the phone itself. They all run on the device;
nothing is sent anywhere.

| Area | Examples |
| --- | --- |
| Flashlight | "flashlight on", "toggle the torch" |
| Radios | "turn off Wi-Fi", "Bluetooth on", "mobile data off", "NFC on" |
| Other switches | "airplane mode on", "location off", "hotspot on", "dark mode on" |
| Quick Settings tiles | "battery saver on", "turn on night light", "extra dim off", "data saver on", "start screen recording", "turn on the Caffeine tile", "tap the sync tile" |
| Do Not Disturb and ringer | "turn on do not disturb", "silence my phone", "vibrate mode" |
| Screen | "brightness 40%", "dim the screen", "auto-rotate off", "adaptive brightness on" |
| Media | "play", "pause the music", "next track", "volume up", "set volume to 30" |
| Calls and texts | "call Mom", "dial 555 123 4567", "text Sam saying I'm running late" |
| Clock | "set an alarm for 7:30", "wake me up at quarter to 7", "timer 10 minutes", "remind me in 5 minutes" |
| Reminders | "remind me to call Mom at 6", "remind me tomorrow to take out the trash", "remind me in 20 minutes to check the oven" |
| Calendar | "what's on my calendar today?", "what do I have Friday?", "am I free next Monday?", "add dentist to my calendar Friday at 3" |
| Messages | "read my notifications", "any new messages?", "what did Sam say?", "reply to Sam saying on my way" |
| Weather | "what's the weather?", "weather in Paris tomorrow", "is it going to rain?", "forecast this week" |
| Phone | "take a screenshot", "lock the phone", "go to the home screen", "show recent apps", "power menu" |
| Settings | "open battery settings", "Bluetooth settings", "app info for Signal" |
| Camera | "take a picture", "take a selfie", "record a video" |
| Contacts and email | "what's Sam's number?", "when is Alex's birthday?", "email Sam about dinner saying see you at 7" |
| Notes | "add milk to my shopping list", "take a note that the code is 4512", "note to self: buy stamps" |
| Quick answers | "what's 15% of 80?", "5 miles in km", "how many cups in a litre?", "what time is it in Tokyo?" |
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
limit, and nothing you ask leaves the phone, except the words of a web search (below).

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
- **Answers:** short and to the point, with lists, bold and code shown as formatting (and read
  aloud without the markup).
- **Memory:** about 3 GB while loaded. It loads when Shuttle opens (if downloaded) and unloads
  after ten quiet minutes.
- **Knowledge date:** the model doesn't know exactly when its training data ends, so it's told
  never to name a cutoff year.

## Web search

For anything current ("who's the current president of France?", "latest GrapheneOS release")
the model searches the web first and answers from the results, which are listed as **Sources**
under the answer; tap one to open it. You can also ask outright: "search for…", "look up…",
"google…", "find out … online". Before the model is downloaded, a search shows the results
themselves.

- **Sources:** DuckDuckGo's plain-HTML results, then Wikipedia (in the phone's language). No
  account, key or server of Shuttle's own.
- **What's sent:** only the search words, never the rest of the chat.
- **Limits:** DuckDuckGo sometimes answers with a "prove you're not a robot" page instead of
  results, especially to unusual networks; Shuttle then uses Wikipedia alone. Sources aren't
  kept when a chat is saved, only the answer.

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

### Phone control (optional)

Turn on **Phone control** (Settings › Special access › Open takes you to Android's
Accessibility page; it's listed there as *Shuttle phone control*, sometimes under
**Downloaded apps**) and Shuttle switches these itself, the way you would: it opens Quick
Settings, taps the tile (paging through if it's not on the first page), checks that it changed
and closes the shade again. That covers Wi-Fi, mobile data, NFC, Bluetooth, airplane mode,
location, hotspot and dark theme, plus any other tile by name: battery saver, night light,
extra dim, data saver, screen record, cast, or a tile from another app ("turn on the Caffeine
tile").

- **Dialogs:** where a tile opens a dialog instead (Internet on Android 12+, Bluetooth on
  newer versions), Shuttle uses the switch inside and closes it. Tiles that only open something
  (screen record, cast) are tapped and left open for you.
- **What it can see:** it's an accessibility service, so Android warns that it could read the
  screen. It listens only to the system UI's events, reads the shade only while doing what
  you asked, and keeps nothing.
- **Restricted setting:** if Android says the setting is restricted (apps installed from a
  file on Android 13+), open Shuttle's App info, tap ⋮, choose **Allow restricted settings**,
  then turn it on.
- **Languages:** the built-in tile names are English. In another language, name the tile as
  your phone shows it ("turn on the … tile"); otherwise the switches fall back to their panel
  or settings page.
- **A tile that isn't there** (not added to Quick Settings) can't be tapped; Shuttle lists the
  tiles it found.
- **Phone actions** use the same service: screenshots, locking the phone, home, back, recent
  apps, the notification shade, Quick Settings, the power menu and split screen. The panel
  closes first, so a screenshot shows the app underneath. Restarting or turning off is left to
  you in the power menu.

### Notification access (optional)

Reading notifications and replying to messages needs **Notification access** (Settings ›
Special access › Open). Shuttle reads what's in your notifications only when you ask, and
keeps nothing. Replies go through the message's own Reply button, so the app it came from
sends them, after you confirm. With access off (or no message to reply to), "reply to Sam
saying…" sends a text instead. As with phone control, Android may first ask you to **Allow
restricted settings** in Shuttle's App info.

### Weather, reminders and the calendar

- **Weather** comes from [Open-Meteo](https://open-meteo.com): free, no key, no account. A
  named place is looked up there; otherwise Shuttle asks once for approximate location and
  sends only rounded coordinates. Like web search, it needs the Network permission on
  GrapheneOS.
- **Reminders** are Shuttle's own notifications (asked for on Android 13+), on time even if
  the phone is asleep, and set again after a restart.
- **Calendar**: reading needs the Calendar permission and covers every calendar shown on the
  phone (local, DAVx⁵ and so on). Adding an event opens your calendar app's new-event screen,
  filled in, for you to save; Shuttle never writes to a calendar itself.

## Permissions

Nothing is asked for up front. Each command asks for what it needs the first time, and
Settings (the gear in the chat's search bar) lets you grant things ahead of time.

| Permission | Used for |
| --- | --- |
| Contacts | Finding people by name for calls and texts |
| Phone | Placing a call you confirmed |
| SMS | Sending a text you confirmed |
| Nearby devices | Showing the "turn on Bluetooth?" prompt (Android 12+) |
| Internet (GrapheneOS: the Network permission in App info) | Downloading the model once, checking `models/manifest.json` for model updates, web searches (only the search words are sent) and the weather (only the place, or rounded coordinates) |
| Wi-Fi and network state (granted at install) | Answering "is Wi-Fi on?" and "is mobile data on?" |
| Microphone | Voice input; speech recognisers only accept apps that hold it. The recogniser does the recording |
| Do Not Disturb access (special) | Do Not Disturb and silent mode |
| Modify system settings (special) | Brightness and auto-rotate |
| Phone control (optional accessibility service) | Tapping Quick Settings tiles for switches apps can't change; screenshots, lock, home and other phone actions |
| Notification access (optional, special) | Reading notifications and replying to messages, when asked |
| Calendar | "What's on my calendar?" |
| Approximate location | The weather where you are, when no place is named |
| Notifications (Android 13+) | Showing reminders (and the model download's progress) |
| Alarms and reminders (exact alarms, granted at install) | Reminders on time; set again after a restart |
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
**Shuttle-debug-…**: the same optimized build (the `preview` build type, so it opens at full
speed), signed with a throwaway test key that's kept in the repository (`app/debug.keystore`).
It installs next to release builds as *Shuttle Debug*, and each one updates the last (including
older, debuggable test builds), keeping the model and chats. It's for testing only: the key is
public, and releases never use it.

The repository includes llama.cpp as a git submodule, so clone with
`git clone --recursive` (or run `git submodule update --init` after cloning).

To build locally: `./gradlew assembleRelease`, `assemblePreview` (optimized, test key) or
`assembleDebug` (debuggable, much slower to open). Unit tests:
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

1. **Reading pages:** opening the top search results for fuller answers.
2. **More tools:** notification reading and replies, calendar, screen text.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
