# Cirrus — Android Ollama chat client

Native Android (Kotlin + Jetpack Compose) client for [Ollama](https://ollama.com). Talks to
either a local Ollama instance or the hosted cloud API over HTTP, streams responses token by
token, renders markdown with syntax-highlighted code blocks, and calls tools — web search, a
GitHub integration, and any MCP server the user attaches.

## Build & test

```bash
./gradlew :app:assembleDebug          # build the debug APK
./gradlew :app:testDebugUnitTest      # run the JVM unit test suite
./gradlew :app:lintDebug              # Android lint
./gradlew :app:installDebug           # install on a connected device/emulator
```

- `compileSdk`/`targetSdk` 37, `minSdk` 29, JVM target 17.
- AGP 9 ships built-in Kotlin; the root `buildscript` raises KGP to `2.3.21`. Compose, KSP and
  serialization plugin versions must all track that same release (see `gradle/libs.versions.toml`).
- Secrets are never committed. The Ollama key and the GitHub token live in DataStore, encrypted
  with an Android Keystore AES-GCM key (`SecretCipher`). `secrets.properties` and
  `local.properties` are gitignored.

## Architecture

Layered, with Hilt wiring it together. Dependencies point inward: `ui → domain → data`.

```
app/src/main/java/dev/klaiber/cirrus/
├── MainActivity.kt          # entry point; maps ACTION_SEND share intents to a SharedPayload
├── service/
│   └── GenerationService.kt # foreground service that keeps a streaming turn alive off-screen
├── ui/                     # Compose screens + components
│   ├── CirrusApp.kt        # NavHost + modal drawer (chat / settings routes)
│   ├── chat/               # ChatScreen, ChatViewModel, ChatUiState, ConversationExporter
│   │   └── components/     # MessageItem, Composer, ModelPickerSheet, ParametersSheet, ...
│   ├── conversations/       # ConversationDrawer + ConversationsViewModel
│   ├── settings/           # SettingsScreen + SettingsViewModel
│   │   └── mcp/            # McpServersScreen, McpServerEditorSheet (probe), McpViewModel
│   ├── components/         # HelpTooltip / HelpBadge, shared across screens
│   ├── voice/              # VoiceInput — SpeechRecognizer hoisted into Compose state
│   ├── markdown/           # MarkdownParser, MarkdownInline, SyntaxHighlighter, CodeBlock
│   └── theme/              # Color, Type, Theme (Material 3, dynamic color support)
├── domain/
│   ├── ChatEngine.kt       # the turn protocol: build request → stream → service tool calls
│   ├── TurnController.kt   # owns running turns on the application scope; persistence + errors
│   ├── ConversationTitler.kt # when a thread is named, from what, and what to do on failure
│   ├── ErrorMessages.kt    # the one place a failure becomes a sentence (Throwable.userMessage)
│   ├── model/              # Conversation, ChatMessage, GenerationParams, ModelInfo, ...
│   └── tools/              # CirrusTool interface, ToolRegistry, web tools, McpTool/McpToolSet
│       └── github/         # 11 GitHub tools + shared schema/argument plumbing
├── data/
│   ├── remote/             # OllamaClient (OkHttp + NDJSON), DTOs, ApiCredentials, exceptions
│   │   └── github/         # GitHubClient (REST v3), DTOs, GitHubCredentials
│   ├── mcp/                # McpClient + transports, McpCatalog (known servers)
│   ├── local/              # Room database, DAOs, entities, mappers
│   ├── repository/         # Conversation/Settings/Model + McpServerRepository
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
  lines; cancelling the collector cancels the underlying OkHttp call. `showModel` reads
  `/api/show`, which is the only authoritative source of a model's capabilities.
- **`TurnController`** — owns every turn in flight, keyed by conversation, on the application
  scope. A turn used to run in `ChatViewModel`'s scope, which dies with its back-stack entry, so
  switching threads or letting Android reclaim the screen killed the answer halfway. The
  ViewModel now only *watches* `turns`/`errors` for the thread it is showing; starting, stopping,
  throttled persistence and finalization all live here. `GenerationService` follows the same
  `turns` flow and is what stops the OS freezing the process mid-stream.
- **`ConversationTitler`** — owns auto-titling: whether a thread is due one, the digest that gets
  summarised, and the local fallback when the model cannot answer. It runs on the application
  scope on purpose — titling happens just after an answer lands, which is exactly when people
  switch threads, and a request cancelled there used to leave the thread called "New chat"
  forever. `Conversation.autoTitledAt` is the record: null means the name is the user's.
- **`ToolRegistry`** — maps tool names to `CirrusTool` implementations. `definitions` is
  **computed per turn**, not fixed at construction: which tools are offered depends on the user's
  toggles, whether a GitHub token exists, and which MCP servers are currently attached and
  reachable. Sending a schema for a tool that cannot run wastes context and invites the model to
  call it and fail. `find` resolves built-ins before MCP tools, so a remote server cannot take
  over `web_search` by naming a tool after it.
- **`GitHubClient`** — REST v3 transport. Has its own `OkHttpClient` (`@GitHubHttp`) because the
  Ollama client attaches the Ollama key to every request, and that key must never reach a third
  party. All mutating calls funnel through `requireWrites()`.
- **`McpClient`** — `initialize` → `tools/list` → `tools/call`, over either HTTP transport
  (`McpTransport`). Handles both a plain JSON body and an SSE frame, and retries on the older
  transport when a server answers streamable-HTTP with the SSE handshake. Server-initiated
  requests, sampling and resources are not implemented; a client that only consumes tools never
  needs them.
- **`McpServerRepository`** — the attached servers (DataStore, tokens encrypted with
  `SecretCipher`) *and* the tools resolved from them. The two live together because a tool list is
  only meaningful for the config version it was fetched against. `probe(url, token)` is discovery:
  it reaches a server without saving it, which is what the add/edit sheet requires before it will
  let you save. `bindings` is a snapshot `ToolRegistry` can read synchronously mid-turn — that is
  the wrong moment to find out a server is down.
- **`SettingsRepository`** — single source of truth for config (DataStore). Mirrors the connection
  fields into `ApiCredentials` and `GitHubCredentials` (volatile fields) so the OkHttp
  interceptors can read them without suspending.
- **`ModelRepository`** — caches the catalogue and enriches it in the background from `/api/show`,
  four models at a time, giving up after the first few failures so a host without that route is
  not hammered.
- **`ConversationRepository`** — Room-backed conversation/message/preset persistence, plus
  fork/truncate/rename operations.

### Data flow for a chat turn

1. `ChatViewModel.send()` appends the user message, then calls `TurnController.start(id)`.
2. The controller appends the assistant placeholder, publishes a `LiveTurn` — which brings
   `GenerationService` up — and collects `ChatEngine.respond(...)` on the application scope.
3. The engine builds `ChatRequestDto` (system prompt, context window, params, tools) and emits
   `RequestPrepared` with the exact JSON (surfaced by developer mode).
4. `OllamaClient.streamChat` streams NDJSON chunks; the engine re-emits content/thinking deltas.
5. If the model requests tools, the engine executes each via `ToolRegistry`, feeds results back
   as `role: "tool"` messages, and loops. When the round budget is spent it withholds the tools
   and asks once more, so the turn ends on an answer rather than an unanswered call.
6. The controller throttles DB writes during streaming and finalizes the message on `Finished`;
   the ViewModel overlays the live buffer onto the persisted row for whichever thread is on
   screen.

## Conventions

- **Wire DTOs** (`data/remote/dto`, `data/remote/github/dto`) are `@Serializable` and mirror the
  upstream API. `think` and `format` are `JsonElement` because Ollama accepts multiple shapes for
  each. GitHub DTOs are trimmed hard — a repository object has ~100 fields, most of them URLs.
- **Domain models** are plain data classes. `GenerationParams` fields are nullable on purpose:
  null means "don't send this option" so the server applies the model's own default.
- **`Json`** is configured with `ignoreUnknownKeys`, `explicitNulls = false`,
  `encodeDefaults = false` — default-valued fields are omitted from requests.
- **Markdown** is a hand-written CommonMark subset (`MarkdownParser`) tolerant of truncated
  input, because it re-parses on every streamed token. Inline spans are resolved at render time
  (`MarkdownInline`). Syntax highlighting is a hand-written lexer (`SyntaxHighlighter`), not
  regex passes, because regexes can't tell a `//` inside a string from a real comment.
- **Errors** map to typed exceptions (`OllamaException`, `GitHubException`, `McpException`) so
  the UI — or the model — can pick the right recovery.
- **Cancellation** is respected end-to-end: stopping a generation cancels the collector, which
  cancels the OkHttp call so the server stops too.
- **Help text** lives next to the control it explains, via `HelpBadge`/`HelpTooltip`. If you add
  a setting or a parameter, it needs help copy — that is the whole point of the pattern.

### Writing a tool

Implement `CirrusTool`, register it in `ToolRegistry` (via `GitHubToolSet` for GitHub ones), and:

- **Never throw.** The model is mid-turn; an exception ends the turn with a stack trace instead of
  letting it recover. Wrap the body in `runTool { }` and return a JSON error object.
- **Truncate aggressively.** The JSON you return is fed straight back as context. Return the
  smallest useful shape and tell the model how to ask for more (`start_line`, `limit`).
- **Anything that writes must be default-off** and must say so in its description, in capitals,
  because the model reads that description when deciding whether to call it.
- **Parse arguments leniently.** Models send numbers as strings. `intOrNull`/`booleanOrNull` in
  `GitHubToolSupport` already handle this.

## Testing

Unit tests live in `app/src/test/java/...` mirroring the main package. Run with
`./gradlew :app:testDebugUnitTest`.

- **`ChatEngineTest`**, **`OllamaClientTest`**, **`GitHubClientTest`** and **`McpClientTest`** use
  OkHttp's **MockWebServer3** (OkHttp 5). Note the API differs from the old mockwebserver2:
  - `server.close()` (not `shutdown()`)
  - `request.url.encodedPath` (not `request.path`)
  - `request.body!!.utf8()` — body is a nullable `ByteString`, not a Buffer
- **Turbine 1.2.1** is used for flow assertions. The receiver method is `awaitItem()` /
  `awaitError()` / `awaitComplete()` — `expectItem()` no longer exists.
- `ChatEngineTest` builds a real `ToolRegistry` (with a DataStore-backed `SettingsRepository`)
  so the tool loop is exercised against the mock server end-to-end. It passes a `GitHubToolSet`
  with no token configured, so only the web tools are offered.
- `GitHubCredentials.apiBaseUrl` is a `var` so tests can point the client at a mock server. It is
  not an injected constructor parameter because Dagger would then need a binding for a bare
  `String`.
- Compose text APIs used in tests (`getLinkAnnotations`) are `@ExperimentalTextApi`; call them
  by their Java getter name and opt in at the class level.
- `SecretCipher` and Room DAOs need Android runtime APIs and are not covered by JVM tests.

## Gotchas

- `encodeDefaults = false` means `stream = true` (a default) is omitted from serialized
  requests — don't assert on it in tests.
- The inline-emphasis matcher is simple: `**bold *italic***` (inner closing marker adjacent to
  the outer one) is not disambiguated; `**bold *italic* text**` works.
- `ApiCredentials.normalizeBaseUrl` strips a trailing `/api` so callers can append `/api/...`.
- Room is at **schema version 2**. `conversations.autoTitledAt` was added by `MIGRATION_1_2`;
  null means the title belongs to the user and auto-titling must not touch it. A locally derived
  fallback title is stamped `Conversation.FALLBACK_TITLED_AT` (the epoch) so it reads as "titled,
  but long ago" and the next turn is free to replace it. Any further schema change needs a
  migration and a regenerated `app/schemas/*.json`.
- Ollama enables thinking by *default* on any model whose `/api/show` reports the capability, so
  omitting `think` does not mean "no thinking". It also rejects `think: true` outright on a model
  without the capability. Anything that needs a short answer has to send `think: false`
  explicitly — and budget for a model that ignores it.
- MCP servers get their own `OkHttpClient` (`@McpHttp`) that attaches **no** credential. The
  other clients set `Authorization` from a credential holder in an interceptor, and an
  interceptor's `header()` *replaces* whatever the request already had — pointing the MCP
  transports at `@GitHubHttp` (as they originally were) meant every attached server received the
  user's GitHub PAT instead of its own token. Anything per-request-credentialled belongs on a
  client with no auth interceptor.
- GitHub's `/issues` endpoint returns pull requests too. `ListIssuesTool` filters on
  `pull_request == null`; forgetting that double-counts every PR as an issue.
- `Modifier.size(n.dp)` on an `IconButton` shrinks its **hit rectangle**, not just its bounds, so
  anything under 48dp quietly breaks the touch-target minimum. Use
  `Modifier.minimumInteractiveComponentSize()` and size the `Icon` inside it instead.
- `LiveRegionMode` has no "off". To stop TalkBack announcing a message, omit the `semantics` block
  rather than trying to set a mode — see the `.then(...)` in `AssistantMessage`.
- Material 3 tooltip types are `@ExperimentalMaterial3Api`. `HelpTooltip`/`HelpBadge` keep them
  out of their own signatures so call sites need no opt-in — don't leak `TooltipState` back out.
- Do not write raw control characters into Kotlin sources. A literal NUL in a char literal makes
  the file binary to `grep` and breaks the parser; use `it.code == 0` instead.
- **Never run a turn on `viewModelScope`.** Each conversation gets its own back-stack entry and
  therefore its own ViewModel, so that scope dies when you switch threads — and a backgrounded
  process is frozen by the OS within seconds, which stalls the socket read until the connection
  dies. Anything that has to survive the screen belongs on the application scope, with
  `GenerationService` up for as long as it runs.
- A stream that ends without a chunk carrying `done` is **truncated, not finished**
  (`OllamaException.Truncated`). Treating it as a normal completion is what makes half an answer
  look like the model's final word. `ChatEngine` re-issues such a round only while it has emitted
  nothing; after the first delta, restarting would duplicate text on screen.
