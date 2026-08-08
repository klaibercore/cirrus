# Cirrus — Android Ollama chat client

Native Android (Kotlin + Jetpack Compose) client for [Ollama](https://ollama.com). Talks to
either a local Ollama instance or the hosted cloud API over HTTP, streams responses token by
token, and renders markdown with syntax-highlighted code blocks.

## Build & test

```bash
./gradlew :app:assembleDebug          # build the debug APK
./gradlew :app:testDebugUnitTest      # run the JVM unit test suite
./gradlew :app:installDebug           # install on a connected device/emulator
```

- `compileSdk`/`targetSdk` 37, `minSdk` 29, JVM target 17.
- AGP 9 ships built-in Kotlin; the root `buildscript` raises KGP to `2.3.21`. Compose, KSP and
  serialization plugin versions must all track that same release (see `gradle/libs.versions.toml`).
- The API key is never committed. It lives in DataStore, encrypted with an Android Keystore
  AES-GCM key (`SecretCipher`). `secrets.properties` and `local.properties` are gitignored.

## Architecture

Layered, with Hilt wiring it together. Dependencies point inward: `ui → domain → data`.

```
app/src/main/java/dev/klaiber/cirrus/
├── MainActivity.kt          # entry point; maps ACTION_SEND share intents to a SharedPayload
├── ui/                     # Compose screens + components
│   ├── CirrusApp.kt        # NavHost + modal drawer (chat / settings routes)
│   ├── chat/               # ChatScreen, ChatViewModel, ChatUiState, ConversationExporter
│   │   └── components/     # MessageItem, Composer, ModelPickerSheet, ParametersSheet, ...
│   ├── conversations/       # ConversationDrawer + ConversationsViewModel
│   ├── settings/           # SettingsScreen + SettingsViewModel
│   ├── markdown/           # MarkdownParser, MarkdownInline, SyntaxHighlighter, CodeBlock
│   └── theme/              # Color, Type, Theme (Material 3, dynamic color support)
├── domain/
│   ├── ChatEngine.kt       # the turn protocol: build request → stream → service tool calls
│   ├── model/              # Conversation, ChatMessage, GenerationParams, ModelInfo, ...
│   └── tools/              # CirrusTool interface + WebSearchTool/WebFetchTool + ToolRegistry
├── data/
│   ├── remote/             # OllamaClient (OkHttp + NDJSON), DTOs, ApiCredentials, exceptions
│   ├── local/              # Room database, DAOs, entities, mappers
│   ├── repository/         # ConversationRepository, SettingsRepository, ModelRepository
│   └── prefs/              # SecretCipher (Keystore-backed envelope encryption)
└── di/                     # AppModule, NetworkModule, DatabaseModule, Qualifiers
```

### Key components

- **`ChatEngine`** — the heart of the app. Runs one assistant turn as a cold `Flow<TurnEvent>`:
  builds the wire request, streams `ThinkingDelta`/`ContentDelta`, then loops to service any
  `tool_calls` the model makes (bounded by `maxToolIterations`). Deliberately free of
  persistence/UI concerns so it can be tested against a mock server.
- **`OllamaClient`** — thin HTTP transport. Only speaks HTTP + JSON; knows nothing about
  conversations or tool loops. `streamChat` returns a cold `Flow<ChatChunkDto>` of NDJSON
  lines; cancelling the collector cancels the underlying OkHttp call.
- **`ToolRegistry`** — maps tool names to `CirrusTool` implementations. `definitions` are sent
  in the request's `tools` field.
- **`SettingsRepository`** — single source of truth for config (DataStore). Mirrors the
  connection fields into `ApiCredentials` (volatile fields) so the OkHttp interceptor can read
  them without suspending.
- **`ConversationRepository`** — Room-backed conversation/message/preset persistence, plus
  fork/truncate/rename operations.

### Data flow for a chat turn

1. `ChatViewModel.send()` appends the user message, then calls `ChatEngine.respond(...)`.
2. The engine builds `ChatRequestDto` (system prompt, context window, params, tools) and emits
   `RequestPrepared` with the exact JSON (surfaced by developer mode).
3. `OllamaClient.streamChat` streams NDJSON chunks; the engine re-emits content/thinking deltas.
4. If the model requests tools, the engine executes each via `ToolRegistry`, feeds results back
   as `role: "tool"` messages, and loops until the model answers or the iteration cap is hit.
5. `ChatViewModel` throttles DB writes during streaming and finalizes the message on `Finished`.

## Conventions

- **Wire DTOs** (`data/remote/dto`) are `@Serializable` and mirror the Ollama API. `think` and
  `format` are `JsonElement` because Ollama accepts multiple shapes for each.
- **Domain models** are plain data classes. `GenerationParams` fields are nullable on purpose:
  null means "don't send this option" so the server applies the model's own default.
- **`Json`** is configured with `ignoreUnknownKeys`, `explicitNulls = false`,
  `encodeDefaults = false` — default-valued fields are omitted from requests.
- **Markdown** is a hand-written CommonMark subset (`MarkdownParser`) tolerant of truncated
  input, because it re-parses on every streamed token. Inline spans are resolved at render time
  (`MarkdownInline`). Syntax highlighting is a hand-written lexer (`SyntaxHighlighter`), not
  regex passes, because regexes can't tell a `//` inside a string from a real comment.
- **Errors** map to typed `OllamaException`s (Unauthorized, RateLimited, ModelNotFound, ...) so
  the UI can offer the right recovery action.
- **Cancellation** is respected end-to-end: stopping a generation cancels the collector, which
  cancels the OkHttp call so the server stops too.

## Testing

Unit tests live in `app/src/test/java/...` mirroring the main package. Run with
`./gradlew :app:testDebugUnitTest`.

- **`ChatEngineTest`** and **`OllamaClientTest`** use OkHttp's **MockWebServer3** (OkHttp 5).
  Note the API differs from the old mockwebserver2:
  - `server.close()` (not `shutdown()`)
  - `request.url.encodedPath` (not `request.path`)
  - `request.body!!.utf8()` — body is a nullable `ByteString`, not a Buffer
- **Turbine 1.2.1** is used for flow assertions. The receiver method is `awaitItem()` /
  `awaitError()` / `awaitComplete()` — `expectItem()` no longer exists.
- `ChatEngineTest` builds a real `ToolRegistry` (with a DataStore-backed `SettingsRepository`)
  so the tool loop is exercised against the mock server end-to-end.
- Compose text APIs used in tests (`getLinkAnnotations`) are `@ExperimentalTextApi`; call them
  by their Java getter name and opt in at the class level.
- `SecretCipher` and Room DAOs need Android runtime APIs and are not covered by JVM tests.

## Gotchas

- `encodeDefaults = false` means `stream = true` (a default) is omitted from serialized
  requests — don't assert on it in tests.
- The inline-emphasis matcher is simple: `**bold *italic***` (inner closing marker adjacent to
  the outer one) is not disambiguated; `**bold *italic* text**` works.
- `ApiCredentials.normalizeBaseUrl` strips a trailing `/api` so callers can append `/api/...`.
