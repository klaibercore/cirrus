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
│   │   └── components/     # MessageItem, ToolActivity, Composer, ModelPickerSheet, ...
│   ├── conversations/       # ConversationDrawer + ConversationsViewModel
│   ├── memory/             # MemoryScreen + MemoryViewModel (browse, edit, pin, retire)
│   ├── agents/             # AgentsScreen, AgentEditorSheet (AgentDraft), history/template sheets
│   ├── onboarding/         # OnboardingScreen + OnboardingViewModel — the first-run wizard
│   ├── settings/           # SettingsScreen + SettingsViewModel
│   │   ├── mcp/            # McpServersScreen, McpServerEditorSheet (probe), McpViewModel
│   │   └── skills/         # SkillsScreen + SkillsViewModel (install, enable, remove)
│   ├── components/         # HelpTooltip / HelpBadge, shared across screens
│   ├── voice/              # VoiceInput — SpeechRecognizer hoisted into Compose state
│   ├── markdown/           # MarkdownParser, MarkdownInline, SyntaxHighlighter, CodeBlock
│   │   └── math/           # LaTeX: MathParser → MathTypesetter → MathBox, plus MathSpeech
│   └── theme/              # Color, Type, Shape, Theme — the design system (see below)
├── domain/
│   ├── ChatEngine.kt       # the turn protocol: build request → stream → service tool calls
│   ├── SpeechController.kt # read-aloud: chunking, ElevenLabs or the device engine, playback
│   ├── SpeechSummarizer.kt # what gets spoken: an answer rewritten for a listener
│   ├── agents/             # AgentRunner (headless turn) + AgentScheduler/AgentWorker
│   ├── memory/             # MemoryRetriever (pure ranking), MemoryConsolidator, nightly worker
│   ├── skills/             # Skill, SkillDocument (SKILL.md parser), SkillReference, SkillBrief
│   ├── notify/             # Notifier interface + AndroidNotifier
│   ├── TurnController.kt   # owns running turns on the application scope; persistence + errors
│   ├── ConversationTitler.kt # when a thread is named, from what, and what to do on failure
│   ├── ErrorMessages.kt    # the one place a failure becomes a sentence (Throwable.userMessage)
│   ├── model/              # Conversation, ChatMessage, GenerationParams, ModelInfo, ...
│   ├── SuggestionGenerator.kt # openers and agent ideas, written by the user's own model
│   ├── settings/           # SettingsCatalog — every capability switch, and where to find it
│   ├── spotify/            # SpotifySession — token refresh, sign-in, and where they persist
│   └── tools/              # CirrusTool interface, ToolRegistry, web tools, McpTool/McpToolSet
│       ├── github/         # 11 GitHub tools + shared schema/argument plumbing
│       ├── spotify/        # 5 tools: search, now playing, library, playback, playlist edit
│       ├── device/         # LocationTool, MediaControlTool
│       └── shell/          # CommandPolicy, ShellWorkspace/Runner, the clock, calendar, apps
├── data/
│   ├── remote/             # OllamaClient (OkHttp + NDJSON), DTOs, ApiCredentials, exceptions
│   │   ├── github/         # GitHubClient (REST v3), DTOs, GitHubCredentials
│   │   ├── spotify/        # SpotifyClient + SpotifyAuth (PKCE), DTOs, SpotifyCredentials
│   │   └── elevenlabs/     # ElevenLabsClient (text-to-speech), ElevenLabsCredentials
│   ├── mcp/                # McpClient + transports, McpCatalog (known servers)
│   ├── skills/             # SkillRegistry (GitHub discovery + download), SkillCatalog
│   ├── local/              # Room database, DAOs, entities, mappers
│   ├── repository/         # Conversation/Settings/Model + Mcp/Skill repositories
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
- **`CommandPolicy`** — decides whether a shell command may run, *before* it runs. An allow list of
  program names, not a deny list: a deny list is a list of the dangerous programs somebody thought
  of, and Android ships a multi-call binary with a hundred applets behind it. Three rules do the
  work — only listed programs, no absolute paths (so the workspace is the whole reachable world),
  and no `$(…)`/backticks/`..`/background jobs. Anything that runs another program on the command's
  behalf (`sh`, `xargs`, `awk`, `env`, `find -exec`) is refused by name. Pure Kotlin; carries tests.
- **`ShellRunner` / `ShellWorkspace`** — the process, and the one directory it may write to. A
  watchdog coroutine kills the process at the deadline *and* in its `finally`, so a cancelled turn
  takes the process with it. The output is read on a **plain daemon thread that is abandoned** at
  the deadline rather than waited on, because that read cannot be ended from outside: `sh -c` execs
  a lone command but forks for a pipeline, and on Linux the surviving grandchild holds the write end
  of the pipe, so neither `destroyForcibly` nor closing the stream unblocks it. The deadline is
  therefore enforced on `Thread.join`, which *is* interruptible. macOS kills the whole group and
  hides all of this, which is why it passed on a laptop and hung for the full command on CI. Stdin
  is written on a daemon thread for the mirror-image reason: a pipe write blocks just as
  uninterruptibly once the buffer fills, so a command that never reads its input would hold the turn
  open past its own deadline. The environment is built rather than inherited, because Android's
  `date` reports UTC unless `TZ` is set. Output is capped at both ends rather than the first 8,000
  characters — most text jobs here end in their answer (`wc` after a pipeline, the last hunk of a
  diff), so a head-only cap throws away the line the command was run for.
- **Topics** — the workspace is divided by job, and every command runs inside one. A single flat
  scratch directory across a long session becomes `out.txt`, `out2.txt`, `tmp.txt`, and the model
  starts reading the wrong one; a topic also *isolates*, since `..` is refused and one job therefore
  cannot reach another's files. Names are normalised rather than rejected (`Invoice Totals (Q3)` →
  `invoice-totals-q3`), which is also what stops a topic — a tool argument, so never seen by
  `CommandPolicy` — naming somewhere else. Cleanup is arranged at three levels because a rule the
  model has to remember at the end of a session is the rule it will not remember: `clear` on process
  start, `sweep` before every command (idle topics, and a cap on how many can be live), and
  `clean_workspace` for the model to call when a job is actually over. A sweep says what it took, so
  a missing file is a sentence rather than a puzzle.
- **`SuggestionGenerator`** — asks the configured model for the four openers on an empty chat and
  for agent ideas, telling it exactly which tools this install has so nothing is suggested that
  would fail on the first tap. Generated once per process per capability signature. The static
  `StarterPrompt`/`AgentTemplate` lists are the floor, not the plan: they show while a request is in
  flight and stay if it fails.
- **`SpotifySession`** — the answer to "is there a usable token right now?". Separate from
  `SpotifyClient` because refreshing is not transport: it persists, it must happen exactly once when
  four tools find an expired token together (hence the `Mutex`), and it distinguishes a token that
  aged out (refresh) from one that was revoked (sign in again). Auth is **PKCE**, so there is no
  client secret anywhere — an app the user can unzip cannot keep one. The client ID is the user's
  own, from developer.spotify.com; Cirrus ships none.
- **`SettingSwitch`** — the catalogue. Every capability switch with its title, the path to it, what
  it unlocks, and how to read its state. `ToolRegistry` turns a refusal into a sentence naming the
  switch, and `describe_settings` hands the whole list over. The paths are load-bearing and
  `SettingsCatalogTest` asserts each one names a section that exists — a path that has drifted is
  worse than none, because it sends someone looking for a row that is not there.
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
- **`SkillRegistry` / `SkillRepository`** — skills, which are `SKILL.md` files from public GitHub
  repositories: the same format and the same registry `npx skills` uses, so a skill written for
  Claude Code works here unread. There is no npx and there cannot be — Android has refused since
  API 29 to execute a binary an app downloaded — so the registry does with three HTTP requests what
  the CLI does with a clone: default branch, recursive tree, then each `SKILL.md` off the raw host.
  Storage is DataStore rather than Room because a skill is a document, there are a handful of them,
  and nothing queries across them; that also means no schema migration, which matters when the
  schema file can only be regenerated by a build. Discovery follows the layouts the ecosystem
  actually uses, and falls back to any shallow `SKILL.md` so a repository with its own layout is not
  reported as empty. The `@GitHubHttp` client carries the user's token if they have one — GitHub's
  own token to GitHub's own hosts, for the rate limit; anonymous is the normal case.
- **Skills are two halves, and the asymmetry is the design.** Every enabled skill's *name and
  description* go into the system message via `skillsBrief` — that is what makes the model aware one
  exists while it is deciding how to approach the question, which no tool description can do. The
  *instructions* run to thousands of words and are fetched by `use_skill` only once a skill applies.
  Reversing either half breaks the economics: bodies in the prompt cost more than the skills are
  worth, and a roster only behind a tool is a roster nothing reads.
- **`SpeechSummarizer`** — what read-aloud actually says. A written answer is built for a reader who
  can skim, so reading one out verbatim spends four minutes on its *layout* — the restated question,
  the option list, the code. The user's own model writes a spoken version first. Falls back to the
  original text on every failure, and skips anything under a screenful, where the answer is already
  its own summary.
- **`SettingsRepository`** — single source of truth for config (DataStore). Mirrors the connection
  fields into `ApiCredentials` and `GitHubCredentials` (volatile fields) so the OkHttp
  interceptors can read them without suspending.
- **`ModelRepository`** — caches the catalogue and enriches it in the background from `/api/show`,
  four models at a time, giving up after the first few failures so a host without that route is
  not hammered.
- **`ConversationRepository`** — Room-backed conversation/message/preset persistence, plus
  fork/truncate/rename operations.
- **`MemoryRepository` / `MemoryRetriever`** — cross-session memory. Writes fold into a near
  duplicate rather than appending, or the store fills with five phrasings of one fact. Retrieval is
  term overlap weighted by term rarity, nudged by recency and by how often a memory has proved
  useful — no embedding model, because running one on-device for a few hundred short sentences
  costs more than it returns. `MemoryRetriever` is pure, and carries the tests.
- **`MemoryConsolidator`** — the nightly pass. Harvests durable facts from threads touched since
  the last run, then merges duplicates and retires what has been superseded. Nothing is deleted;
  retiring is archiving, and the memory screen can restore anything.
- **`AgentRunner`** — runs a scheduled prompt with nobody watching and writes the answer into an
  ordinary conversation *stamped with the agent's id*, which is what lets every existing transcript
  feature work on it for free while keeping it out of the drawer. One run happens at a time (a
  `Mutex`), it is bounded by `RUN_TIMEOUT_MS`, and it returns an `Outcome` so the worker can tell a
  dropped socket from a rejected key. Every attempt is written to `agent_runs`, success or not.
- **`OnboardingViewModel` / `OnboardingScreen`** — the first-run wizard. Its actual job is not
  collecting settings but ending on a request that demonstrably worked: `testConnection` saves the
  host and key *first*, because the credential holder the HTTP layer reads is fed from the same
  store, and then fetches the catalogue. Skipping counts as finishing.
- **`AgentScheduler` / `ConsolidationScheduler`** — one-shot WorkManager requests that re-book
  themselves. Periodic work has a fifteen-minute floor and drifts against the wall clock, which is
  fine for a sync and useless for "07:30 on weekdays".
- **`MathTypesetter`** — lays out a parsed formula as a tree of `MathBox`es, each a width plus a
  baseline splitting an ascent from a descent. The rules are TeX's in miniature: fractions and
  stretched delimiters centre on the *axis* (¼ em above the baseline) rather than on the baseline,
  scripts shrink to 0.72 and then stop, and the space between two atoms comes from their classes —
  `a+b` looser than `ab`, `a=b` looser still. That last rule is most of what separates maths from a
  row of symbols. Delimiters and radicals are stroked paths, not glyphs, so their weight stays
  constant however far they stretch. `MathParser` is pure Kotlin and carries the test suite;
  layout is judged by eye.
- **`SpeechController`** — owns read-aloud on the application scope, for the same reason
  `TurnController` does: audio outlives the screen. One message speaks at a time. Long answers are
  split at sentence ends, and the hosted path synthesises one chunk ahead of the one playing so
  there is no gap at the seam. ElevenLabs needs a key and is opt-in; without one it falls back to
  Android's own engine rather than failing.

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
- **Emphasis is two axes, not one.** `**strong**` is `ExtraBold` *and* tracks tighter; `*emphasis*`
  is a real italic (`FontSynthesis.All`) *and* tracks looser. Weight alone did not separate them
  from the paragraph at reading size, and neither separated them from *each other* — a heavy run and
  a slanted run are both merely "darker" in peripheral vision. `MarkdownInlineTest` asserts on the
  tracking, because that is the part a tidy-up would delete as noise.
- **Errors** map to typed exceptions (`OllamaException`, `GitHubException`, `McpException`) so
  the UI — or the model — can pick the right recovery.
- **Cancellation** is respected end-to-end: stopping a generation cancels the collector, which
  cancels the OkHttp call so the server stops too.
- **Maths** is typeset, not flattened: `$…$` becomes an `InlineTextContent` placeholder inside the
  paragraph and `$$…$$` its own `MdBlock.Math`. `renderMathToUnicode` still exists and is still the
  right answer for every context that needs *text* — the clipboard, the export, the alternate text
  behind a placeholder (which is what makes a selected paragraph copy as `x² + 1` rather than as a
  hole), and `speakMath` for read-aloud.
- **Help text** lives next to the control it explains, via `HelpBadge`/`HelpTooltip`. If you add
  a setting or a parameter, it needs help copy — that is the whole point of the pattern.

### The design system

The interface follows ollama.com: a monochrome page, hairline borders instead of shadows, a full
pill on anything pressable, and a rounded display face over the platform's own body face. Four
rules, and holding to them is what keeps the app looking like one thing.

- **Colour is monochrome by default.** `ui/theme/Color.kt` is one neutral ramp from `#FFFFFF` to
  `#000000`, and `primary` is near-black rather than a hue — which is what makes a filled button
  read as the reference design's black pill for free, since Material 3 buttons are already pills.
  There is **no dynamic colour**: Material You derives a scheme from the wallpaper, which is
  precisely destructive of a design that has committed to zero chroma. The setting was removed
  rather than defaulted off, because the state it enabled was one where a screenshot of Cirrus is
  unrecognisable as Cirrus.
- **Colour that survives carries meaning**, and lives in `TagColors`, not the scheme: capability
  tags on a model card (cyan/blue/indigo, the reference site's own assignments), hyperlinks, and
  the search highlight. The last two are load-bearing rather than decorative — a link in `primary`
  is now the same colour as the sentence around it, and a grey highlight on a grey ramp is
  invisible. Reach for `LocalTagColors` for those three things and nothing else.
- **Two radii, no ladder.** `Pill` for anything interactive, `ContainerShape` (12dp) or
  `LargeContainerShape` (16dp) for anything holding content. `CirrusShapes` maps the Material scale
  onto the same pair so untouched Material components land in the same language.
- **Depth is a border, never a shadow.** Use `OutlinedPanel` rather than a filled `Surface` with
  `shadowElevation`; use `Hairline` between rows and under the app bar. A filled panel is reserved
  for the two cases where a border cannot do the job: the user's own message bubble, and a code
  block (which takes both, because a near-black inset on a near-black page has no edge of its own).
- **Type** is `DisplayFamily` (Nunito, bundled as a single 277KB variable font) for headings and
  titles, and the platform sans for everything read at length — the reference site's split of
  SF Pro Rounded over `system-ui`. Every style states `letterSpacing = 0.sp`, because Material's
  default tracking on small labels is a surprising amount of why an interface reads as Android
  rather than as the thing being copied.

Shared parts live in `ui/components/Primitives.kt` (`OutlinedPanel`, `PillButton`, `Tag`,
`Hairline`). Assemble a screen from those rather than styling a `Surface` by hand.

### Agents keep their own threads

An agent run is an ordinary conversation — that is what makes every transcript feature work on it
for free — but `conversations.agentId` says which agent wrote it, and every drawer query filters on
`agentId IS NULL`. A daily agent used to contribute a thread a day to the same list as the
conversations someone actually had; after a fortnight the list was mostly machine. The runs are not
hidden, they live on the agent that wrote them.

Three consequences worth keeping straight:

- **Replying detaches.** `ChatViewModel.send` (and the banner's "Keep") clears `agentId`, because a
  thread you have joined in on is a conversation, not an artefact. Branching a run produces a normal
  thread for the same reason.
- **Retention only reclaims what is still a run.** `Agent.keepRuns` bounds how many threads an agent
  keeps; `AgentRepository.pruneRuns` deletes the oldest beyond it, on failures as well as successes,
  and never touches one that has been detached.
- **The nightly memory pass skips them.** `ConversationDao.updatedSince` excludes agent threads, or
  a scheduled prompt gets harvested as a durable fact about the user every single night.

### Memory, agents, the shell, and the tools switch

The conversation's tools switch governs **external** tools only — web search, GitHub, MCP. Memory,
skills, notifications and the device tools (the shell, the clock, the calendar, `system_info`) are
offered either way, because that switch exists to control latency, cost and what leaves the device, and none
of those spends any of it. Gating memory behind it meant cross-session memory silently not working
in most conversations, which is indistinguishable from it being broken; gating the clock behind it
means a model answering "how long until Friday?" from the year it was trained in. `ToolRegistry.find`
re-checks every gate, so a model that names `web_search` with the switch off is told the tool is
unknown rather than quietly reaching the network.

Settings of their own sit beside it. `skillsEnabled` (default **on**) covers the installed skills, and costs nothing until one is
installed — the roster is omitted when it is empty. `shellToolsEnabled` (default **on**) covers the
device tools;
`appControlEnabled` (default **off**) covers `list_installed_apps`, `open_app`, `install_app` and
`media_control`, which act rather than answer; `locationEnabled` (default **off**, and needing
Android's permission on top) covers `get_location`; `spotifyEnabled` needs a client ID and a
sign-in behind it. `install_app` cannot install: it opens a store listing, and Android's own
installer asks.

### One write gate, for everything

`CirrusTool.writes` means: **the effect outlives the turn, happens outside Cirrus, and cannot be
reversed by calling the same tool again.** Each clause matters. Pausing music changes something and
is not a write — the next call unpauses it. Remembering a fact is not a write — it is Cirrus's own
store, and the memory screen restores anything. Opening a GitHub issue is, because nothing unopens
it and other people can already see it. Gating too eagerly is not free either: putting memory behind
this switch would make cross-session memory silently stop working.

`writeToolsAllowed` (default **off**) governs every one of them at once, and migrates from the old
GitHub-only `github_writes` key so anyone who had already allowed writes keeps them. A gate per
integration meant the third integration shipped without one — which is exactly what had happened to
MCP.

**MCP tools default to writing.** `McpTool.writes` is `descriptor.readOnly != true`, read from the
spec's optional `annotations` (`readOnlyHint`, `destructiveHint`, with destructive winning a
contradiction). Absent annotations — most servers, today — count as writing. Every other tool in
this app can be understood by reading it; an MCP tool is an arbitrary function on somebody else's
server described by a sentence that server wrote about itself, and "nobody said it was destructive"
is not evidence. The cost is real: an unannotated server offers nothing until writes are allowed.

`ToolRegistry.standingBrief()` is the other half of that, and it goes into the system message next
to the memory brief. A tool description is read when the model is deciding whether to call *that
tool*; "clean up before you finish" is about the end of a session, which is exactly the moment
nobody is reading a tool description. Two sentences, because this is paid for on every turn.

### Writing a tool

Implement `CirrusTool`, register it in `ToolRegistry` (via `GitHubToolSet` for GitHub ones), and:

- **Declare `writes`** if the effect outlives the turn, is outside Cirrus, and cannot be undone by
  calling the tool again. Reversible or local effects are not writes — see the gate above.
- **Never throw.** The model is mid-turn; an exception ends the turn with a stack trace instead of
  letting it recover. Wrap the body in the neighbouring set's helper — `GitHubTool`'s base class,
  `memoryTool { }`, `shellTool { }` — and return a JSON error object. Each of those rethrows
  `CancellationException`: swallowing it would turn "the user pressed stop" into a tool result.
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
- Room is at **schema version 4**. `MIGRATION_3_4` adds `conversations.agentId`, `agents.keepRuns`
  and the `agent_runs` table, and back-fills `agentId` from each agent's `lastConversationId` so the
  backlog is re-homed too. `keepRuns` declares `@ColumnInfo(defaultValue = "10")` on the entity as
  well as in the ALTER: a default the database has and the entity does not is the kind of mismatch
  that only surfaces as a crash on somebody else's upgrade. `memories` and `agents` came from
  `MIGRATION_2_3`. Column types must match what Room generates exactly or the identity hash check
  fails at open, and every schema change needs a regenerated `app/schemas/*.json` — that file is
  written by a build, so run one after changing an entity.
- **Only one agent runs at a time**, and that is load-bearing rather than tidy: `SendNotificationTool`
  is a singleton carrying the conversation its notification should open, so two overlapping runs
  hand each other's threads to each other's notifications. Agents fire on the minute, and "08:00 on
  weekdays" is the likeliest time for anyone to have scheduled two.
- A stalled stream does not throw — it simply never delivers another byte — so `AgentRunner` bounds
  a run with `withTimeout`. Being killed by the work manager's own deadline instead would leave the
  run marked as running forever *and* skip the re-booking that schedules tomorrow's. Runs that were
  killed anyway (reboot, low memory) are closed out by `failInterruptedRuns` on the next start.
- `AgentWorker` re-books the next occurrence under the same unique work name the retry chain uses,
  so it must not do that while returning `Result.retry()` — it would cancel the retry it just asked
  for.
- WorkManager's own initialiser is **removed in the manifest**: workers are built by Hilt, and
  WorkManager initialising itself first means the first scheduled agent cannot be instantiated.
  `CirrusApp` implements `Configuration.Provider` and installs the Hilt factory instead.
- Room was at **schema version 2**. `conversations.autoTitledAt` was added by `MIGRATION_1_2`;
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
- Since API 29 Android **refuses to execute a binary an app downloaded** into its own data
  directory (W^X). There is therefore no way to install a command-line program *into* Cirrus, and
  `run_command` is limited to what the system's toybox already provides — which varies by vendor,
  hence the `shell.available`/`shell.missing` block in `system_info`. The honest answer for someone
  who wants a package manager is a terminal app with its own userland, which `install_app` can offer
  and nothing here can be.
- A blocking read on a pipe **cannot be ended from another thread** on Linux: not by interrupting
  it, not by `destroyForcibly` on the direct child, and not by closing the stream. Anything with a
  deadline over a subprocess has to stop *waiting* rather than try to stop the read — see
  `ShellRunner`, where the reader is a daemon thread and the timeout sits on `Thread.join`.
- A **refusal is not "Unknown tool"**. `ToolRegistry.explainRefusal` names the switch that is in the
  way and where it lives, because a model that cannot tell "this app cannot do that" from "not until
  somebody flips a switch" guesses the first one — and then tells the user their app lacks a feature
  it shipped with, with nothing in the conversation to correct it.
- **Spotify's playback API needs Premium** and answers 403 on free accounts with a message about
  nothing else. `SpotifyTool` catches it and points at `media_control`, which drives Android's own
  media keys, needs no account, and works with any player. A 404 on `me/player` means no active
  device, not a missing endpoint.
- The Spotify redirect is `cirrus://spotify/callback` and must match the registration **exactly**.
  `MainActivity` is `singleTop` so the return lands in `onNewIntent` rather than a second instance,
  and the PKCE verifier is persisted rather than held in memory — Android is free to kill Cirrus
  while somebody reads the consent screen.
- **Android blocks activity starts from the background**, so `open_app` and `install_app` check
  `IMPORTANCE_FOREGROUND` first and return an explanation rather than reporting success for a screen
  that never appeared. A scheduled agent calling either at 3am gets the refusal, which is correct.
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
- Anything that speaks, records or renders on a Canvas has a package-visibility catch: without a
  `<queries>` entry for `android.intent.action.TTS_SERVICE`, `TextToSpeech` silently binds to no
  engine on Android 11+, exactly as `SpeechRecognizer` does without its own.
- Compose's `PlaceholderVerticalAlign.AboveBaseline` puts the *bottom* of an inline placeholder on
  the text baseline, so anything with a descender hangs below the line. Inline maths is made
  symmetric about the maths axis and aligned with `TextCenter` instead.
- The launcher icon is a union of three discs over a stadium base, not a traced outline, because
  the union keeps its bumps even at 48dp. A stroked outline was tried — closer to the reference's
  drawn mascot — and abandoned: eroding a union of discs to cut the inner counter leaves ink
  slivers wherever two discs meet shallowly. `ic_notification.xml` shares the same viewport and
  path data on purpose; the two had already drifted into different shapes once.
- The billows of the launcher icon are deliberately narrower than its base. Lining their outer
  edges up exactly puts a visible dent in each side of the cloud where the two arcs cross.
- A stream that ends without a chunk carrying `done` is **truncated, not finished**
  (`OllamaException.Truncated`). Treating it as a normal completion is what makes half an answer
  look like the model's final word. `ChatEngine` re-issues such a round only while it has emitted
  nothing; after the first delta, restarting would duplicate text on screen.
