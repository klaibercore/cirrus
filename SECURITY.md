# Security Policy

## Reporting a vulnerability

Please **do not open a public issue** for a security problem.

Report it privately through GitHub's [security advisory
form](https://github.com/klaibercore/cirrus/security/advisories/new). If that is unavailable to
you, email the maintainer address on the GitHub profile instead.

Include what you need to make the problem reproducible: the device and Android version, the app
version from Settings, the steps, and what you expected instead. A proof of concept helps but is
not required.

You will get an acknowledgement within **72 hours** and an assessment within **7 days**. If the
report is valid you will be credited in the advisory unless you ask otherwise.

## Supported versions

Cirrus is a single-track app. Only the latest release on `main` receives security fixes.

## What Cirrus does with your secrets

Worth stating plainly, because it determines what actually counts as a vulnerability here.

| Secret | Where it lives | How it is protected |
| --- | --- | --- |
| Ollama API key | DataStore, on-device | AES-GCM envelope encryption; the wrapping key is generated in and never leaves the Android Keystore (`SecretCipher`) |
| GitHub token | DataStore, on-device | Same envelope encryption as the Ollama key |
| Conversations, messages, attachments | Room database + app-private files | Standard app sandbox; never uploaded anywhere by Cirrus |

Cirrus talks to exactly two kinds of endpoint: the Ollama host you configure, and — only if you
enable the GitHub tools — `api.github.com`. There is no telemetry, no analytics SDK, no crash
reporter, and no third-party backend. `INTERNET` is the only network permission, and
`RECORD_AUDIO` is requested at the moment you first tap the microphone.

## Threat model

**In scope.** Anything that leaks a stored secret off-device, decrypts persisted data without the
Keystore, lets another app on the device read Cirrus's storage, sends conversation content
anywhere other than your configured host, or escalates a tool call beyond what the tool is scoped
to do.

**Out of scope.** Physical access to an unlocked, rooted device. Vulnerabilities in Ollama itself
or in a model's output — report those upstream. The fact that a language model can be talked into
saying something wrong; that is a property of language models, not a flaw in this client. Anything
requiring the user to paste a token into a field explicitly labelled as a token field.

## Hardening notes for contributors

- Never log a key, a token, or message content. Not even at `DEBUG`.
- New network destinations need a line in the table above and a mention in the README.
- A tool that writes anything (opens an issue, posts a review) must be behind an explicit,
  default-off user toggle, and must say in its description that it writes.
- `secrets.properties` and `local.properties` are gitignored. Keep it that way.
