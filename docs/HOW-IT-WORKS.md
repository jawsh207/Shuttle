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

The app's own icon opens **the chat** (`MainActivity.kt`, `ui/ChatScreen.kt`): the same
conversation full screen, laid out like the other apps in the family, with a search bar at the
top that searches what was asked and answered, and a settings gear on its right. Until the
first request it shows a greeting and a few suggestions to tap. It doesn't listen or open the
keyboard by itself, and it stays put when a command opens another app. The assist gesture
always opens the small panel, never this chat.

Chats in this view are **saved as you go** (`chats/ChatStore.kt`): one small text file per chat
in the app's private folder, which only Shuttle can read. Android backup is off for the app,
so nothing leaves the phone. Tapping the search bar turns the page into the list of saved chats,
newest first, with New chat at the top; typing searches every chat; tapping one reopens it;
touching and holding one offers Delete, with Undo. The + beside the gear starts a new chat. A
saved chat keeps only text, so a card that was waiting for Send or Allow comes back as plain
text and can never be acted on later. The assist panel's quick commands aren't saved.

The gear opens **Settings** (`ui/SettingsScreen.kt`), sliding in over the chat: a checklist for
choosing Shuttle as assistant, granting permissions and special accesses, checking the voice
services and their settings, a Model section for downloading, updating or deleting the
model, a Chats section for turning saving off or deleting every chat,
and a button to try the assistant panel. Each section (Assistant, Permissions, Voice, Chats,
Special access) folds away from its heading, and while folded it shows a
one-line summary such as "3 of 4 allowed".

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
`voice/VoicePrefs.kt`, which also lists the installed recognisers and engines for Settings and
shows which recogniser is in use. The Settings button for changing it opens Language & region
settings, where Android keeps the "Voice input" choice.

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
Anything else goes to the on-device model (next section).

## The on-device model

Shuttle's model is IBM's Granite 4.2 3B, running on the phone through llama.cpp. It handles
whatever the fast path doesn't: questions, conversation, unusual wordings, follow-ups.

- **The engine.** llama.cpp is part of the app (a git submodule at `app/src/main/cpp/llama.cpp`,
  pinned to a release). `app/src/main/cpp/shuttle_llm.cpp` is a small bridge that loads a model,
  runs a prompt and streams the reply back; `llm/LlamaEngine.kt` is its Kotlin side. Each turn
  re-uses what the model already read last time, so long chats stay quick.
- **Loading and memory.** `llm/LocalModel.kt` runs one reply at a time on a background thread.
  On a phone's CPU, reading a prompt is far slower than writing the answer, and every prompt
  starts with the same instructions and tool list. So when Shuttle opens, it loads the model
  (a few seconds) and reads that shared start straight away; a question then only has to read
  the new words. The model's memory (about 3 GB) is freed after ten quiet minutes. The
  conversation is capped at 8,000 tokens.
- **Stopping.** Stop asks the native code to abort, which llama.cpp checks during every step of
  its work, so it stops even while a long prompt is still being read.
- **Getting the model.** `llm/ModelManager.kt` keeps track of it and `llm/ModelDownloadService.kt`
  downloads it, only when the user taps Download. Shuttle downloads it itself, as a background
  service with a progress notification, rather than handing it to Android's system downloader:
  that way "Wi-Fi only" applies immediately (on mobile data it pauses and waits), a dropped
  connection resumes where it stopped, and if the phone is online but Shuttle's Network
  permission is off (GrapheneOS only asks for it when an app is first installed), it says so
  and offers App info. The finished file's SHA-256 has to match the one in
  `models/manifest.json` or it's deleted. The app checks that manifest on GitHub for a newer
  model and offers it in Settings; a copy of the current choice is built in (`llm/ModelSpec.kt`).
- **Talking to it.** `llm/GranitePrompt.kt` writes the conversation in Granite's own format
  (from its published chat template, with thinking off so answers start straight away) and
  reads tool calls out of the reply, hiding any stray thinking markers. `llm/ModelPrompts.kt`
  holds the instructions it's given: a general assistant first (questions, explanations,
  recipes, advice), concise, using the phone's tools only when asked to do something on the
  phone, and saying its knowledge may be out of date for things that change, since it can't
  go online. Earlier turns of the chat go along as context, but only real answers: Shuttle's
  own notes (errors, "Stopped.") are left out so the model doesn't repeat them. To keep that start short and unchanging, it holds only the date (not the time), and
  the model sees a compact tool list: the twelve on/off switches are one "set switch" tool and
  every "is it on?" question is one "check setting" tool, which are turned back into the real
  tools before anything runs.
- **Doing things.** When the model asks for a tool, the call runs through the same device tools
  as a typed command. Arguments the tool doesn't declare are thrown away first, so the model
  can't mark a call or text as already confirmed: those always stop at the confirmation card.
- **On screen.** The reply streams into its card as it's written; a stop button replaces send
  until it's done. Before the model is downloaded, the empty chat offers the download, and
  other requests get a short note saying so.

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
| `MainActivity.kt` | Launcher entry: the full-screen chat, with Settings over it |
| `ui/ChatScreen.kt` | Full-screen chat: search bar, saved-chat list, new chat, settings gear, greeting and suggestions |
| `llm/LlamaEngine.kt` | Kotlin side of the native bridge to llama.cpp |
| `llm/LocalModel.kt` | Loads the model when needed, streams replies, unloads when idle |
| `llm/ModelManager.kt` | The model's state: download, check, install, update, delete |
| `llm/ModelDownloadService.kt` | Background download with progress notification, resume and Wi-Fi rule |
| `llm/ModelSpec.kt` | Which model, where from, its checksum; reads `models/manifest.json` |
| `llm/GranitePrompt.kt` | Granite's prompt format and tool-call reading |
| `llm/ModelPrompts.kt` | The model's instructions, its tools, and the argument safety check |
| `app/src/main/cpp/shuttle_llm.cpp` | Native bridge: load, generate, free |
| `models/manifest.json` | The project's current model choice, read by the app |
| `chats/ChatStore.kt` | Saved chats: one private text file each; listing, search, delete |
| `chats/ChatPrefs.kt` | Whether chats are saved |
| `ui/SettingsScreen.kt` | Assistant role, permissions, special access and voice settings |
| `assist/AssistActivity.kt` | The assistant panel over other apps |
| `ui/AssistantPanel.kt` | Conversation, input box and result cards |
| `ui/AssistantViewModel.kt` | Holds the conversation; runs commands, confirmations and retries |
| `ui/VoiceConversation.kt` | Listen, run, speak the answer, ask yes/no for calls and texts |
| `voice/VoiceSession.kt` | The phone's speech recogniser and text-to-speech engine |
| `voice/VoicePrefs.kt` | Voice settings; installed recognisers and engines |
| `engine/Replies.kt` | Reads a spoken yes or no |
| `ui/theme/Theme.kt` | Material 3 Expressive theme with wallpaper colours and Google Sans Flex, as in the other apps |
| `engine/Tools.kt` | Shared types: tool calls, tool descriptions, results, accesses |
| `engine/ToolSchemas.kt` | Every tool and its arguments; JSON export |
| `engine/CommandParser.kt` | Fast-path phrasings to tool calls |
| `engine/Quantities.kt` | Number words, clock times, durations |
| `engine/SmallTalk.kt` | Replies to hello, thanks, "what can you do?", "never mind" |
| `tools/*.kt` | The device tools, grouped as above (`Status.kt` answers "is it on?") |
| `docs/tools.json` | Generated tool list for model work (kept in sync by a unit test) |
