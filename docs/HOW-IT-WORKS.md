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
each has a trash button that deletes it after a confirmation, with Undo. Back from the list, search or Settings returns to the chat; back from the chat closes the app. The + beside the gear starts a new chat. A
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
5. **A card shows what happened** (`ui/AssistantPanel.kt`), with copy and read-aloud buttons
   under each answer (copying drops the Markdown symbols; reading uses the phone's
   text-to-speech, and tapping again stops it), with any Markdown in an answer
   shown as formatting and left out when it's read aloud (`text/Markdown.kt`). The
   conversation itself is kept by `ui/AssistantViewModel.kt`.

If the fast path doesn't recognise the command, `engine/SmallTalk.kt` checks for the social
things people say (hello, thanks, "what can you do?", "never mind", which closes the panel).
Anything else goes to the on-device model (next section).

## The on-device model

Shuttle's model is IBM's Granite 4.2 3B (the Q4_0 build, which llama.cpp rearranges at load
time for the phone CPU's dot-product instructions, making it much faster than other 4-bit
builds), running on the phone through llama.cpp. It handles
whatever the fast path doesn't: questions, conversation, unusual wordings, follow-ups.

- **The engine.** llama.cpp is part of the app (a git submodule at `app/src/main/cpp/llama.cpp`,
  pinned to a release). `app/src/main/cpp/shuttle_llm.cpp` is a small bridge that loads a model,
  runs a prompt and streams the reply back; `llm/LlamaEngine.kt` is its Kotlin side. Each turn
  re-uses what the model already read last time, so long chats stay quick.
- **Loading and memory.** `llm/LocalModel.kt` runs one reply at a time on a background thread.
  On a phone's CPU, reading a prompt is far slower than writing the answer, and every prompt
  starts with the same instructions and tool list. That start is read only once per model:
  the result is saved to a file in the app's private storage, and every later load restores it
  in a moment. Loading and restoring start as soon as Shuttle has drawn its first frame (not
  before, so the screen isn't slowed down), and a question only has to read its own words. The instructions never change, so the saved copy stays valid; the date
  goes in a separate short note after them. The model's memory (about 3 GB) is freed after ten
  quiet minutes. The context is capped at 12,000 tokens: the instructions and tool list take
  about 3,500, the rest is for the chat, shared text and the reply. Replies can run to about 1,000
  tokens. Sampling is gentle (temperature 0.3, with min-p and a light repeat penalty so a
  small model doesn't loop through the same list item).
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
  recipes, advice), starting with the answer and matching its length to the question, light
  Markdown for lists and key terms, using the phone's tools only when asked to do something on
  the phone, searching the web for anything current, and never naming a knowledge cutoff year
  (it can't know it). It's told to treat the user as an adult and answer sensitive subjects (health, drugs, law, security, mature themes) frankly and specifically, without lecturing, and to decline only requests that would seriously help someone hurt people. Earlier turns of the chat go along as context: a window of up to roughly
  3,000 tokens whose start stays put; when it outgrows that, the oldest turns are dropped until
  it's down to half. Dropping one turn per question would change the start of every prompt and
  make the model re-read the whole chat each time. The model
  is told to treat short or vague messages as follow-ups and to build on its earlier
  answers when asked to continue, expand or change one. Only real answers are sent: Shuttle's
  own notes (errors, "Stopped.") are left out so the model doesn't repeat them.
- **Searching the web.** `search/WebSearch.kt` asks DuckDuckGo's plain-HTML results page, then
  Wikipedia's search API (in the phone's language); `search/SearchResults.kt` reads
  DuckDuckGo's page (skipping ads, and spotting its "are you a robot?" page, which counts as no
  results) and writes the results out for the model. The model is told to search for anything
  current or likely to have changed. When it asks for `web_search`, or the user says "search
  for…"/"look up…", the search runs in the background ("Searching the web…"), the results go
  back to the model as the tool's answer, and it writes the reply from them, with the pages
  listed as sources under it. One search per question. Shuttle doesn't wait to be told, either
  (`engine/SearchHints.kt`): a question plainly about now (news, scores, prices, who holds an
  office, new releases, a recent year) is searched first, and an answer that turns out to be
  "I don't have real-time information" or "want me to search?" is replaced by a search and a
  fresh answer. To keep that from showing, an answer stays hidden (the card shows the model
  thinking) until its first sentence is written; one that starts as a deferral is stopped
  right there and the search runs instead. "Search it" or "look it up" searches the question asked before. Only the search words are sent. Before
  the model is downloaded, a search shows the results themselves.
- **Follow-ups to a command.** "Turn it off", "switch it back on", "it's still on", "that
  didn't work" and "try again" act on the last phone command (`engine/FollowUps.kt`): "it's
  still on" after "flashlight off" turns it off again. They're handled before the parser and
  never by the model, which could otherwise just say it had done it; the model is also told
  never to claim a change it didn't make with a tool.
- **Follow-ups versus commands.** Right after the model has answered, a few words that are
  otherwise phone commands ("continue", "next", "go back", "again") go to the model instead, so
  "continue" after a long answer carries on with it rather than starting the music; small talk
  ("no", "thanks") also goes to the model, as a reply to what it said. Saved chats remember
  which answers came from the model, so this works in a reopened chat too. To keep that start short and unchanging, it holds only the date (not the time), and
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
  phone, SMS, nearby devices, calendar, location, notifications) use Android's permission
  dialog and then carry on. Do Not Disturb access, Modify system settings, notification access
  and phone control are switches in Settings: **Allow** opens the page, and **Try again** runs
  the command when you're back.

## Assistant tools

- **Reminders** (`tools/Reminders.kt`): Shuttle's own notifications, set with AlarmManager
  for the time said (`engine/When.kt` reads "at 6", "tomorrow at 9", "Friday", "in 20
  minutes"). They're kept in private storage and set again after a restart or update. "Remind
  me in 5 minutes" with nothing to remind still starts a timer.
- **Calendar** (`tools/CalendarTools.kt`): reading lists the day's (or week's) events from
  every calendar shown on the phone. Adding opens the calendar app's own new-event screen,
  filled in, for the user to save; Shuttle never writes to a calendar.
- **Messages** (`tools/Messages.kt`, with `MessagesService`, a notification listener the
  user turns on): reads the current notifications (chat apps' recent messages, others'
  text), newest first, optionally only from an app or person. Replies use the message's own
  Reply button, so the app it came from sends it, after a confirmation card. With nothing to
  reply to, "reply to Sam…" sends a text instead.
- **Weather** (`engine/Weather.kt`, `tools/WeatherTool.kt`): the US National Weather Service
  for places in the US (falling back to MET Norway if it doesn't answer), MET Norway
  everywhere else. Both are free for any use and keyless, and want a User-Agent naming the
  app; the answer credits the source. A named place is found on the phone in the GeoNames
  place list (`engine/Places.kt`, `assets/places/`): a region after the name narrows it, and
  otherwise the biggest match wins with a strong lean toward the user's own country.
  Without a place, the phone's approximate location is used, rounded to about a kilometre.
  Only coordinates are sent. Units follow the region. It runs in the background with a card
  that fills in.
- **Settings shortcuts, camera, contacts, email, notes** (`tools/Shortcuts.kt`): settings
  pages by name (`engine/SettingsPages.kt`) and any app's App info; the camera for a photo,
  selfie or video; a contact's number, email, address or birthday; an email started in the
  email app; a note sent to the notes app (the only notes-like app that takes shared text, or
  the share sheet).
- **Music** (`tools/Music.kt`): Android's "play from search" request with what kind of thing
  it is (artist, album, song, playlist, genre), to the named app or the default player.
- **Alarms and timers** (`tools/Clock.kt`): the next alarm comes from Android (the time the
  clock app registered); showing and turning off go to the clock app. Timers Shuttle starts
  are kept with their end time (`TimerLog`) to say what's left; a running timer can't be
  stopped by an app, so cancelling opens the clock's timers. Reminders (Shuttle's own) can be
  listed and cancelled (`tools/Reminders.kt`).
- **Call history** (`tools/CallHistory.kt`): missed and recent calls from the call log; "call
  them back" hands the last caller's number to the call tool, which confirms first.
- **Memory** (`engine/Memory.kt`, `tools/MemoryTools.kt`): facts the user asked to remember,
  in private storage. They go in the per-question note, so changing them never makes the
  model re-read its instructions, and any contact in a command is first looked up in them
  ("my wife" → Sam).
- **Routines** (`engine/Routines.kt`, `RoutineStore` in `tools/MemoryTools.kt`): a phrase
  ("good night", or "run my good night routine") runs its lines through the same parser, one
  after another; lines it doesn't understand are skipped and reported.
- **Sharing in** (`MainActivity`, `ui/ChatScreen.kt`'s `SharedCard`): Shuttle is a share target
  for text. The shared item waits above the chat; a button or a typed question sends it to
  the model with the instruction (up to about 8,000 characters). A link is fetched and its
  readable text taken first (`search/PageText.kt`). What was shared goes with follow-ups in
  the same session but isn't saved with the chat.
- **Instant answers** (`engine/InstantAnswers.kt`): arithmetic, unit conversions and the time
  in other places, answered before the model (which is slower and can get sums wrong). Only
  a question it fully understands is answered there.

## What Android lets an app do

Shuttle uses only what any app may do, without root or Shizuku. Since Android 10 apps can't
switch Wi-Fi, mobile data or NFC, so those tools open the system's floating panel for that
setting. Bluetooth on uses the system's confirmation prompt. Bluetooth off, airplane mode,
location, hotspot and dark theme open their settings pages. Everything else changes directly.

### Phone control (optional)

The optional **phone control** service (`tools/QuickTilesService.kt`, an accessibility
service) gets around that the way a person would. It hears only the system UI's events and
reads the shade only while carrying out a request.

- **Quick Settings tiles.** With it on, those switches and the `quick_setting` tool (any tile
  by name) are done by `tools/QuickTiles.kt`. It opens Quick Settings, finds the tile by its
  label (`engine/QuickTileNames.kt` matches names and reads "On"/"Off"), taps it only if that
  changes anything, checks the result and closes the shade.
- **Finding the tile.** Real tiles (which say whether they're on) are preferred over status
  bar icons with the same name, such as the Wi-Fi and Bluetooth icons at the top of the shade.
  Later pages are reached by paging the element holding the most tiles (not the notification
  list), or by swiping across the tiles if that changes nothing.
- **Tapping.** Newer Android splits a large tile in two: the icon switches it, the rest opens
  its details. So Shuttle taps the tile's own switch element if it has one, then touches the
  icon (the start of a wide tile), then the tile as a whole, checking after each try and going
  back if a tap opened the details. It never taps again once the tile has changed.
- **Dialogs.** A tile that opens a dialog (Internet, Bluetooth) has the right switch in it
  used. Android's own confirmations ("Turn off mobile data?") are answered with the matching
  button. A dialog Shuttle can't work out is left open, and the reply quotes it.
- **Mobile data** never uses a Wi-Fi tile or switch. Every page is searched for a tile called
  Mobile data (or one named after the carrier that mentions LTE, 5G and so on) before the
  combined Internet tile is tried, and that only through its dialog: an Internet tile that
  switches by itself is Wi-Fi's, so the tap is undone.
- **Fallbacks.** If no tile matches, a switch opens its panel or settings page, saying so and
  listing the tiles found.
- **Timing.** Tapping watches the screen for a second or two, so it runs on its own thread:
  the assistant shows a "Tapping Quick Settings…" card and fills it in, and several commands
  in one sentence wait for each other.
- **Phone actions** (`tools/PhoneActions.kt`): screenshot, lock, home, back, recent apps,
  notifications, Quick Settings, power menu and split screen, Android's own actions for
  accessibility services. The panel closes first and the action follows a moment later, so a
  screenshot shows the app underneath.

Directions use a standard map link, so they open in whatever maps app you have. Alarms and
timers are created in your own clock app, which rings them.

## Files

| File | What it is |
| --- | --- |
| `MainActivity.kt` | Launcher entry: the full-screen chat, with Settings over it |
| `ui/ChatScreen.kt` | Full-screen chat: search bar, saved-chat list, new chat, settings gear, greeting and suggestions |
| `engine/SearchHints.kt` | When to search without being asked: current questions, "I can't know" answers, "search it" |
| `search/WebSearch.kt` | Web search: DuckDuckGo's HTML results, then Wikipedia |
| `search/SearchResults.kt` | Reading DuckDuckGo's results page; results written out for the model |
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
| `ui/MarkdownText.kt` | Answers drawn with their formatting (lists, bold, code, links) |
| `text/Markdown.kt` | Reading the model's Markdown; plain words for speaking replies |
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
| `tools/QuickTiles.kt` | Tapping Quick Settings tiles: finding, reading, tapping, dialogs |
| `tools/QuickTilesService.kt` | The optional accessibility service (phone control) behind tiles and phone actions |
| `tools/PhoneActions.kt` | Screenshot, lock, home, back, recents, power menu and the like |
| `tools/Reminders.kt` | Reminders: scheduling, showing, restoring after a restart |
| `tools/CalendarTools.kt` | Reading the calendar; adding events through the calendar app |
| `tools/Messages.kt` | Reading notifications and replying to messages; the notification listener |
| `tools/WeatherTool.kt` | Fetching the weather; the phone's approximate location |
| `tools/Shortcuts.kt` | Settings pages, App info, camera, contact details, email, notes |
| `tools/Music.kt` | Playing music by name |
| `tools/CallHistory.kt` | Missed and recent calls; calling back |
| `tools/MemoryTools.kt` | Remembered facts and routines, kept on the phone |
| `engine/FollowUps.kt` | "Turn it off", "it's still on", "try again": acting on the last command |
| `engine/Memory.kt` | Facts for the model; "my wife" → a name |
| `engine/Routines.kt` | Matching a routine's phrase; its lines as commands |
| `search/PageText.kt` | The readable text of a shared link's page |
| `engine/When.kt` | Days and times as said, for reminders and events |
| `engine/InstantAnswers.kt` | Arithmetic, unit conversions, time elsewhere |
| `engine/Weather.kt` | National Weather Service and MET Norway answers; the weather report |
| `engine/Places.kt` | Finding a named place in the offline place list |
| `app/src/main/assets/places/` | The place list: towns of 1,000+ people from GeoNames, with region, country, coordinates and time zone |
| `tools/places/make_places.py`, `.github/workflows/places.yml` | Build the place list from GeoNames and commit it (Actions › Update place list) |
| `engine/Json.kt` | A small JSON reader (testable without Android) |
| `engine/SettingsPages.kt` | Settings pages by name |
| `engine/QuickTileNames.kt` | Tile labels for each switch, name matching, reading on/off |
| `tools/icon/make_icon.py` | Draws the launcher icon (foreground and one-colour version) and an SVG preview; run it from the repo root after changing a shape |
| `docs/tools.json` | Generated tool list for model work (kept in sync by a unit test) |
