# Changelog

All notable changes to Cirrus are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

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

[Unreleased]: https://github.com/klaibercore/cirrus/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/klaibercore/cirrus/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/klaibercore/cirrus/releases/tag/v1.0.0
