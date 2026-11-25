# Contributing to Shuttle

## Documentation

Every change keeps the code readable by someone who didn't write it.

- **Every Kotlin file starts with a description.** Before the main class, object or
  composable, a `/** … */` comment says in plain words what it is, why it exists, and anything
  surprising about how it works (an Android rule it works around, a reason it runs where it
  does). New files get one; changed files keep theirs accurate.
- **Explain why, not what.** Comment the reasons a reader couldn't guess from the code:
  Android restrictions, GrapheneOS behaviour, choices between alternatives. Don't narrate
  each line.
- **Keep the plain-language guide current.** [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)
  explains, without code, what Shuttle does and which file does it. When a change adds,
  removes or moves a feature, tool, screen or file, update the guide in the same change.
- **Keep the README current** for anything about building, installing, permissions, what
  Android allows, or how a command behaves.
- **Only describe what's there.** Check claims against the code; don't document planned
  features as if they exist (the README's Roadmap section is the one place for plans).

## Adding a tool

1. Describe it in `engine/ToolSchemas.kt` (name, description, arguments).
2. Implement it in the matching file under `tools/` and add it to that file's `runners`.
3. Add fast-path phrasings to `engine/CommandParser.kt` if people will say it often, with
   tests in `CommandParserTest`.
4. Regenerate `docs/tools.json` (the `toolsJsonIsCurrent` test fails until you do and shows
   the new content).
5. Use only public APIs a normal app may use: no root, no Shizuku, no hidden APIs. When
   Android doesn't allow a change, open the system panel or settings page instead and say so
   in the result text.
6. Anything that contacts a person or spends money (calls, texts) returns a confirmation first.

## The model

- The model is chosen by the project in `models/manifest.json` (id, URL, SHA-256, size,
  licence). Changing it there offers the update to every user; also update `ModelSpec.BUILT_IN`
  to match. Only Apache-2.0 or compatible models.
- llama.cpp is a submodule pinned to a release. To update it, check out the new release tag in
  `app/src/main/cpp/llama.cpp`, fix `shuttle_llm.cpp` for any API changes, and commit the new
  pointer.
- If a new model uses a different prompt format, give it its own file like
  `llm/GranitePrompt.kt` with tests, rather than changing Granite's.

## Tests

Unit tests live in `app/src/test/` and run on every build (`./gradlew testDebugUnitTest`);
a failing test stops the APK from being built. The parser, number and time handling, and tool
schemas are plain Kotlin, so add tests for any change there.

## Commits

- Commit messages: a short summary line, then a few lines on what changed and why.
- Commits are authored by the project owner. Don't add co-author or tool attribution lines.

## Builds and releases

Every push to `main` builds a signed, optimized test APK on GitHub Actions (or, until the release
key is set, the same optimized build signed with the throwaway key in `app/debug.keystore`). Releases: push
`release-notes/vX.Y.Z.md`, or run **Actions › Build release**. See the README for details.
