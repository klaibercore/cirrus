# Cirrus

A native Android chat client for [Ollama](https://ollama.com). Streams responses token by
token, renders markdown with syntax-highlighted code blocks, and works with both a local Ollama
instance and the hosted cloud API.

## Features

- **Streaming chat** — responses appear token by token, with thinking/reasoning traces shown in
  a collapsible section for models that emit them.
- **Markdown rendering** — headings, lists (incl. task lists), tables, block quotes, inline
  code, links, and fenced code blocks with a hand-written syntax highlighter covering 20+
  languages.
- **Tool calling** — the model can search the web and fetch pages, with results fed back into
  the conversation. The tool loop is bounded so a model can't spin forever.
- **Conversations** — drawer with date-bucketed threads, auto-titling, pinning, archiving,
  branching (fork) from any message, and export to Markdown.
- **Attachments** — share text or images into Cirrus from any app; document text is inlined so
  non-vision models still see it.
- **Full parameter control** — temperature, top-p/k, penalties, seed, context window, stop
  sequences, structured output (`format`), and per-model thinking effort.
- **Developer mode** — inspect the exact request/response JSON and per-message generation stats
  (tokens, tokens/sec, time-to-first-token).
- **Theming** — system/light/dark with optional Material 3 dynamic color.
- **Privacy** — the API key is stored encrypted with an Android Keystore AES-GCM key and never
  written to disk in plaintext.

## Requirements

- Android 10 (API 29) or newer
- A local Ollama instance (`http://localhost:11434`) **or** an [Ollama cloud](https://ollama.com)
  account with an API key

## Build

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`./gradlew :app:installDebug` or `adb install`.

## Getting started

1. **Open the app.** If you're using a local Ollama instance, set the base URL in Settings to
   `http://localhost:11434` (no API key needed). For the hosted service, add your API key in
   Settings.
2. **Pick a model.** The model list is fetched from your host. Models that support thinking or
   vision are badged accordingly.
3. **Start chatting.** Type a prompt, or share text/images into Cirrus from another app to
   prefill a new conversation.

### Connecting from a device

A physical device can't reach your computer's `localhost`. Either run Ollama on the same
network and use your machine's LAN IP, or use the hosted cloud service.

## Architecture

Layered, with Hilt for dependency injection:

- **`ui/`** — Compose screens (chat, conversations drawer, settings) and the markdown renderer.
- **`domain/`** — `ChatEngine` (the turn protocol: build request → stream → service tool
  calls), domain models, and the tool registry.
- **`data/`** — `OllamaClient` (OkHttp + NDJSON streaming), Room persistence, DataStore-backed
  settings, and Keystore-backed secret storage.

See `CLAUDE.md` for a deeper walkthrough of the architecture and conventions.

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

The JVM unit suite covers the chat turn protocol and HTTP client against a mock server, the
markdown parser and syntax highlighter, and the domain/formatting helpers.

## License

Private project.
