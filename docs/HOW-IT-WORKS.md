# How Shuttle works

A plain-language tour of what happens when you ask Shuttle something, and which file does
each part. Paths are under `app/src/main/java/app/shuttle/assistant/`.

## Opening the assistant

When Shuttle is your default digital assistant, the assist gesture or a long-press of the power
button opens **the assistant panel** (`assist/AssistActivity.kt`): a sheet at the bottom of the
screen over whatever you were doing. It starts listening straight away (see Voice below). Tap
the dimmed area or press back to close it. Each time
it opens it starts a fresh conversation.

Shuttle becomes eligible for the assistant role by handling Android's "assist" request with an
ordinary activity. It doesn't register as a full voice-interaction service, because Android
would then also make Shuttle your system speech recogniser, replacing whichever speech-to-text
app you use.

The app's own icon opens **setup** (`MainActivity.kt`): a checklist for choosing Shuttle as
assistant, granting permissions and special accesses, checking the voice services and their
settings, and a button to try a command.

## Voice

Shuttle borrows the phone's own speech services (`voice/VoiceSession.kt`): Android's
speech recogniser API binds whichever recogniser is the system default, and the text-to-speech
API uses the default engine. So with Distillery chosen in Settings, Distillery listens and
speaks; with another engine, that one does. Shuttle needs the microphone permission only
because recognisers refuse callers without it; the recogniser does the recording.

`ui/VoiceConversation.kt` runs the back-and-forth: listen, run what was heard, say the
answer, and for a call or text ask "Send it?" and listen once more. `engine/Replies.kt` decides
whether that answer is a yes or a no. It only accepts clear answers; anything else is treated
as a new request and the card stays on screen, so nothing is sent on a guess. Typed requests
go the same way but are never answered aloud.

The two voice settings (listen when the panel opens, speak replies) live in
`voice/VoicePrefs.kt`, which also lists the installed recognisers and engines for setup.

## From words to action

1. **You say or type a command** in the panel (`ui/AssistantPanel.kt`).
2. **The fast path reads it** (`engine/CommandParser.kt`). It tidies the text ("Hey Shuttle,
   um, can you…please" becomes the command itself), then checks it against a list of
   phrasings, questions ("is wifi on?") first. Each command has many: switching Wi-Fi off can be "turn off wifi", "kill the
   wifi", "shut the wifi off" or "wifi off"; turning brightness up can be "brighter" or "the
   screen is too dark". Message text is read exactly as said, so a text ending in "thanks" or
   "now" keeps it. A sentence with several commands ("turn off wifi and bluetooth") is split
   into one each, with the verb carried over; if any part isn't understood, nothing runs.
   Numbers said as words, clock times and durations are handled by `engine/Quantities.kt`
   ("seven thirty pm", "quarter to seven", "an hour and a half", "a couple of minutes"). An
   alarm without am/pm ("wake me at 6") goes to whichever 6 o'clock comes next.
3. **The result is a tool call**: a tool name and its arguments, such as `set_timer` with
   `seconds = 600`. Every tool and its arguments are listed in `engine/ToolSchemas.kt`, which
   is also exported as [tools.json](tools.json) for a tool-calling model.
4. **The tool runs** (`tools/DeviceTools.kt` finds it by name):
   - `tools/Switches.kt`: flashlight, Wi-Fi, mobile data, NFC, Bluetooth, airplane mode,
     location, hotspot, dark theme, Do Not Disturb, auto-rotate, brightness.
   - `tools/Sound.kt`: ringer mode, media volume, play/pause/next/previous.
   - `tools/Communication.kt`: calls and texts, including finding contacts by name.
   - `tools/Clock.kt`: alarms, timers, the time and date.
   - `tools/Apps.kt`: opening apps, directions, battery.
   - `tools/Status.kt`: "is X on?" for every switch, the ringer, brightness and volume, and
     "what's on?" for a summary. For anything Shuttle can switch, the answer is a card offering
     the opposite ("Wi-Fi is on. Turn it off?"). Android hides the hotspot's state from apps,
     so that question offers its settings instead. The flashlight's state only arrives through
     a notification from Android, which Shuttle starts listening for when the panel opens.
5. **A card shows what happened** (`ui/AssistantPanel.kt`). The conversation itself is kept by
   `ui/AssistantViewModel.kt`.

If the fast path doesn't recognise the command, `engine/SmallTalk.kt` checks for the social
things people say (hello, thanks, "what can you do?", "never mind", which closes the panel).
Anything else gets "I can't do that yet". That's
where the on-device model will go.

## Cards that need you

- **Confirm**: calls and texts show who (and what, for a text) with **Call** or **Send**. A text
  like "text Sam I'm running late" has no clear break between name and message, so Shuttle tries
  the first three words, then two, then one, against your contacts, and shows the split it found.
  Confirming sends exactly what the card showed.
- **Allow**: when a tool needs something you haven't granted. Normal permissions (contacts,
  phone, SMS, nearby devices) use Android's permission dialog and then carry on. Do Not Disturb
  access and Modify system settings are switches in Settings: **Allow** opens the page, and
  **Try again** runs the command when you're back.

## What Android lets an app do

Shuttle uses only what any app may do, without root or Shizuku. Since Android 10 apps can't
switch Wi-Fi, mobile data or NFC, so those tools open the system's floating panel for that
setting. Bluetooth on uses the system's confirmation prompt. Bluetooth off, airplane mode,
location, hotspot and dark theme open their settings pages. Everything else changes directly.

Directions use a standard map link, so they open in whatever maps app you have. Alarms and
timers are created in your own clock app, which rings them.

## Files

| File | What it is |
| --- | --- |
| `MainActivity.kt` | Setup checklist |
| `assist/AssistActivity.kt` | The assistant panel over other apps |
| `ui/AssistantPanel.kt` | Conversation, input box and result cards |
| `ui/AssistantViewModel.kt` | Holds the conversation; runs commands, confirmations and retries |
| `ui/VoiceConversation.kt` | Listen, run, speak the answer, ask yes/no for calls and texts |
| `voice/VoiceSession.kt` | The phone's speech recogniser and text-to-speech engine |
| `voice/VoicePrefs.kt` | Voice settings; installed recognisers and engines |
| `engine/Replies.kt` | Reads a spoken yes or no |
| `ui/theme/Theme.kt` | Material 3 Expressive theme with wallpaper colours |
| `engine/Tools.kt` | Shared types: tool calls, tool descriptions, results, accesses |
| `engine/ToolSchemas.kt` | Every tool and its arguments; JSON export |
| `engine/CommandParser.kt` | Fast-path phrasings to tool calls |
| `engine/Quantities.kt` | Number words, clock times, durations |
| `engine/SmallTalk.kt` | Replies to hello, thanks, "what can you do?", "never mind" |
| `tools/*.kt` | The device tools, grouped as above (`Status.kt` answers "is it on?") |
| `docs/tools.json` | Generated tool list for model work (kept in sync by a unit test) |
