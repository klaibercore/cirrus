# Changelog

All notable changes to Cirrus are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [1.2.0] - 2026-08-13

### Added

- **Maths is typeset, not approximated.** Formulas were flattened to Unicode, so `\frac{a}{b}`
  arrived as `a/b`, a summation lost its limits and a matrix was hopeless. There is now a real
  layout engine: fractions stack over a rule, scripts sit where scripts belong, delimiters and
  radicals stretch to fit what is inside them, and matrices, `cases` and aligned environments come
  out as matrices, cases and aligned environments. Spacing follows TeX's rules, so `a + b` is
  looser than `ab` and `a = b` looser still — which is most of what separates maths from a row of
  symbols. Display equations get their own line and scroll sideways when they are wider than the
  screen; long-press copies the LaTeX.
- **Answers can be selected.** Long-press any reply to select and copy part of it. Formulas copy
  as readable text rather than as a gap, so a selected paragraph gives you `x²`, not a hole.
- **Read aloud.** A speak button under finished replies. What gets spoken is not the raw markdown:
  code blocks are announced rather than dictated, links read as "link", tables as heading-and-value
  pairs, and maths as words — "the sum from i equals 1 to n, of x sub i".
- **ElevenLabs voices, optionally.** Bring an API key and replies are read in a far better voice,
  with the voice and model chosen in settings. Without a key it uses Android's own engine; there is
  no state in which the button does nothing.
- **Find in conversation.** Search the open thread from the overflow menu, with every match
  highlighted and arrows to step between them.
- **Memory across conversations.** Cirrus can write down something worth keeping and look it up
  later, through tools it drives itself — deliberately on demand, so what it keeps is a short list
  of durable facts rather than a summary of everything you have ever said. **Settings → Memory**
  shows every line, and lets you edit, pin, retire or delete any of it. Pinned memories are sent
  with every message; the rest have to be recalled.
- **Overnight consolidation.** Once a night, on a charger, Cirrus reads the threads since the last
  pass, writes down anything durable that was said in passing, merges memories that say the same
  thing and retires what has been overtaken. Nothing is deleted — retired memories can be restored.
- **Scheduled agents.** A prompt that runs on its own clock: a morning briefing, a Friday summary,
  a nightly check on something. Each run writes into an ordinary conversation you can open, scroll,
  branch from and reply to, and can notify you when it lands. **Settings → Agents**.
- **A notification tool.** The model can put something on your notification shade when you asked to
  be told about it — and a scheduled agent can reach you with what it found at 3am.

### Changed

- **Settings is a hub rather than a scroll.** Thirty controls in one column, ordered by when each
  was written, became eight groups plus Memory and Agents. Nothing was removed.
- **The launcher icon is just the cloud.**

### Fixed

- **Memory and notifications are no longer behind the tools switch.** That switch governs what
  reaches off the phone — search, GitHub, MCP — and gating local, instant, free tools behind it
  meant memory silently doing nothing in most conversations.
- **The tools switch now actually stops a tool.** Only the schemas were gated, so a model that
  named `web_search` anyway — from an earlier turn, or by guessing — would still have reached the
  network with external tools switched off. Resolving a tool now applies the same gate that
  decides which ones are offered.

## [1.1.0] - 2026-08-11

### Added

- **MCP servers.** Attach a Model Context Protocol server and its tools are offered to the model
  alongside Cirrus's own, under the same per-conversation tools switch. Servers live in
  **Settings → Tools → MCP servers**; each one can be switched off without being removed, and its
  token is stored with the same Keystore-backed encryption as the Ollama and GitHub keys.
- **Discovery before you commit.** Adding a server connects to it, asks what tools it offers, and
  lists them — with the transport it negotiated — before anything is saved. A URL that parses
  proves nothing, and a misconfigured server otherwise fails much later, mid-answer, as a tool
  call the model cannot explain. Editing the URL or token after testing marks the result stale
  rather than showing a number that no longer applies.
- **A short list of known servers** (GitHub, Sentry, Linear, Hugging Face, DeepWiki) that prefill
  the form. They are starting points, not endorsements, and each still has to be reached before
  it can be saved.
- **Jump to latest.** Scrolling up during an answer now offers a way back, labelled "New response"
  while a turn is still streaming.

### Fixed

- **Scrolling up mid-answer no longer fights you.** The transcript followed the tail on every
  token, so trying to re-read something while a response streamed dragged you straight back to
  the bottom. It now follows only when you are already at the bottom — and does so without
  restarting an animation per token, which was also the source of some of the jitter.
- **Your own messages can be acted on.** Copy, edit and resend, branch and delete were all
  implemented and reachable for assistant turns only; the actions sheet had an edit-and-resend
  branch that nothing could open. Long-press any message you sent.
- **Touch targets meet the 48dp minimum.** Nine controls — the composer's icon row, the send
  button, the per-message actions, the code-block buttons, the conversation overflow menu and the
  error banner's dismiss — had hit areas smaller than their icons suggested.
- **TalkBack announces answers.** A completed response is now a polite live region, so it is read
  out when it lands instead of arriving in silence.

### Security

- **An MCP server can no longer receive your GitHub token.** The MCP transports shared the GitHub
  HTTP client, whose interceptor replaces `Authorization` on every request it handles — so a
  server's own token would have been overwritten with the user's GitHub PAT and sent to a
  third-party host. MCP now has its own client that attaches no credential of its own. Nothing
  was exposed in a released build: no code path reached the MCP client until this release.

## [1.0.2] - 2026-08-11

### Fixed

- **Replies no longer die when Cirrus leaves the screen.** A turn ran in the chat screen's own
  scope, so it was cancelled the moment that screen went away — switching threads killed it
  outright, and backgrounding the app left it to be frozen by Android within seconds of the
  process being cached, which stalls the socket until the connection dies. Turns now belong to
  the application, and a foreground service keeps the process awake and unfrozen for as long as
  one is running. Lock the phone mid-answer and the answer is finished when you come back.
- **A cut-off reply is no longer presented as a finished one.** A stream that ended without its
  terminal chunk was treated as a completed answer, which is what made an interrupted reply look
  like the model deciding to stop — reliably at a sentence end, because that is where tokens
  land. It is now reported as the interruption it is, with the partial text kept, and a round
  that dies before producing anything is quietly retried.
- **Spending the tool budget no longer abandons the task.** When a turn used up its tool rounds,
  the model's pending calls were dropped and the turn ended right there — often mid-plan, and
  sometimes with no text at all. Cirrus now asks once more with the tools withheld, so the turn
  ends on an answer that says what was found and what is still outstanding.

### Added

- **A notification while a reply is streaming**, showing that Cirrus is working and offering a
  Stop that works from anywhere. Permission is asked for at your first generation; refusing it
  costs the notification, not the reply.
- **A per-thread error banner that waits for you.** A failure on a thread you are not looking at
  is shown when you return to it, rather than being lost.

## [1.0.1] - 2026-08-10

### Fixed

- **Threads no longer stay called "New chat".** Auto-titling asked for a title in a way that
  quietly produced nothing on the models most people run it against. Ollama enables thinking by
  default on any model that supports it, so a reasoning model spent the whole 24-token title
  budget on its reasoning and returned an empty answer; one that emits raw `<think>` tags inline
  titled the thread `<think>` instead. Thinking is now switched off explicitly where the model
  has the capability (and the field is omitted entirely where it does not), the budget is wide
  enough to survive a model that reasons anyway, and any reasoning left in the reply is stripped
  before the title is taken.
- **Titling no longer dies when you leave the thread.** It ran inside the chat screen's
  generation job, so switching conversations or backing out in the second after an answer landed
  cancelled the request and left the thread unnamed for good. It now runs on the application
  scope, and stopping a generation still names the thread it produced.
- **A thread always ends up named.** If the host is unreachable or the model returns nothing
  usable, the thread takes its name from its opening message instead, and the next turn still
  tries for a real summary.

## [1.0.0] - 2026-08-09

First release. An Android Ollama client for developers who want their local models to actually
do things.

### Added

- **Capability-aware model picker.** Cards carry parameter count, quantization, on-disk size and
  context window, with capability chips read from `/api/show` rather than guessed from the
  model's name. Filter by vision, reasoning, tools, cloud or local.
- **Token streaming** straight from `/api/chat` over NDJSON. Stopping a generation cancels the
  HTTP call, so the server stops generating too.
- **Reasoning traces.** `thinking` deltas stream into a collapsible section, with an effort
  control for models that support one.
- **Tool calling** in bounded multi-round loops: web search, page fetch, and twelve GitHub tools.
- **GitHub integration.** Read code in public and private repositories, search, browse trees,
  read issues and pull request diffs. Opening issues, commenting, posting reviews and committing
  files sit behind a separate switch that is off by default — and with it off, those tools are
  never offered to the model at all.
- **Markdown built to survive streaming**, with a hand-written lexer for syntax highlighting and
  a practical subset of LaTeX maths rendered as Unicode.
- **Voice dictation**, preferring Android's on-device recogniser.
- **Full sampling control** — temperature, top-p, top-k, min-p, penalties, seed, `num_ctx`,
  `num_predict`, stop sequences, JSON schema output, `keep_alive` — each independently
  overridable and each explained where it sits.
- **Conversation management**: fork from any message, edit and resend, regenerate, export to
  Markdown, and threads that name themselves from their content.
- **Secrets encrypted at rest** with an AES-GCM key generated in and never released from the
  Android Keystore. No analytics, no crash reporter, no telemetry.
- **An MCP client** speaking both HTTP transports — the current streamable-HTTP one and the older
  two-channel SSE one, with the transport auto-detected when a server answers the streamable-HTTP
  handshake with an `endpoint` event. Not yet reachable from the UI; see the README.
- **Signed release builds.** Pushing a `vX.Y.Z` tag builds, signs and verifies an APK and
  publishes it to GitHub Releases with generated notes and a `.sha256`, so Obtainium can track
  it. See [docs/RELEASING.md](docs/RELEASING.md).
- **An F-Droid build recipe**, staged in `klaibercore/fdroid-cirrus-metadata`, pending submission
  to fdroiddata now that there is a tag to build from.

### Known gaps

- MCP has no configuration UI. The client is complete and tested, but nothing persists a server
  or bridges its tools into the registry.
- LaTeX is mapped to Unicode, not typeset. There is no layout, so fractions render as `a/b`.

[Unreleased]: https://github.com/klaibercore/cirrus/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/klaibercore/cirrus/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/klaibercore/cirrus/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/klaibercore/cirrus/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/klaibercore/cirrus/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/klaibercore/cirrus/releases/tag/v1.0.0
