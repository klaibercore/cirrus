# Contributing to Cirrus

Thanks for taking the time. This document says what the project expects, so you can spend your
effort on the change rather than on guessing the conventions.

## Before you write code

- **Bugs**: open an issue with the device, Android version, app version, and the steps. A stack
  trace from `adb logcat` is worth a hundred words of description.
- **Features**: open an issue first and describe the problem, not the solution. Cirrus is a
  focused client; a feature that only helps one workflow is likely to be declined, and it is
  kinder to say so before you have built it.
- **Security**: do not open an issue. See [SECURITY.md](SECURITY.md).

Small, obvious fixes — a typo, a crash with a one-line cause — can go straight to a pull request.

## Getting set up

```bash
git clone https://github.com/klaibercore/cirrus.git
cd cirrus
./gradlew :app:assembleDebug
```

You need JDK 17 and an Android SDK with API 37. Android Studio will offer to install both. No API
key is needed to build or to run the tests; you only need one to talk to Ollama's hosted API, and
a local `ollama serve` needs no key at all.

```bash
./gradlew :app:testDebugUnitTest     # JVM unit tests
./gradlew :app:lintDebug             # Android lint
./gradlew :app:installDebug          # onto a connected device
```

## What a good pull request looks like

**One change per pull request.** A refactor bundled with a bug fix is two pull requests; reviewing
them together means reviewing neither properly.

**Tests for anything with logic.** The suite is fast and runs on the JVM. Parsers, budgeting,
state derivation and wire encoding are all testable without a device — if your change is not, say
why in the description. `ChatEngineTest` and `OllamaClientTest` show the MockWebServer3 pattern;
`MarkdownParserTest` shows the table-driven style for pure functions.

**Match the surrounding code.** The codebase has a consistent voice: comments explain *why* a
non-obvious decision was made, never *what* the next line does. If a line needs a comment saying
what it does, rename something instead.

```kotlin
// Good: explains a decision the reader cannot infer.
// Cancelling the coroutine must abort the socket read, which is otherwise blocking.
currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

// Bad: restates the code.
// Cancel the call when the job completes.
currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
```

**Keep the layering.** Dependencies point inward: `ui → domain → data`. `ChatEngine` knows nothing
about Compose or Room. `OllamaClient` knows nothing about conversations. If your change needs to
break that, it probably belongs somewhere else.

**Nullable means unset.** `GenerationParams` fields are nullable on purpose: `null` means "send
nothing for this option and let the model apply its own default", which is a different request
from sending the default explicitly. Do not paper over this with default values.

## Commit messages

Write the subject in the imperative, under 72 characters, and explain the *why* in the body if it
is not obvious:

```
Cancel the OkHttp call when the collector is cancelled

Stopping a generation previously left the request running server-side,
so the host kept burning tokens after the user hit stop.
```

No conventional-commit prefixes are required.

## Style

`.editorconfig` covers the mechanical parts and your IDE should apply it automatically. Beyond
that: 100-column soft limit, trailing commas in multi-line argument lists, explicit visibility
only when it is not `public`, and no wildcard imports.

Run lint before you push. CI runs build, tests and lint on every pull request, and a red check
will not be reviewed.

## Adding a tool

Tools are the main extension point. Implement `CirrusTool`, register it in `ToolRegistry`, and
remember that the JSON you return is fed straight back to the model as context — every byte
competes with the conversation. Truncate aggressively and let the model ask for more.

Anything that writes to a remote service must be default-off and must say so in its description.

## Code of conduct

Participation is governed by the [Contributor Covenant](CODE_OF_CONDUCT.md).

## Licence

Contributions are accepted under the [Apache License 2.0](LICENSE). By opening a pull request you
confirm you have the right to license your contribution under those terms.
