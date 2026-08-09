# Changelog

All notable changes to Cirrus are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Cirrus has not been tagged yet, so everything below is what v1.0.0 will contain. Cutting the
release moves these entries under `## [1.0.0] - YYYY-MM-DD`; see
[docs/RELEASING.md](docs/RELEASING.md).

### Added

- An F-Droid build recipe, staged in `klaibercore/fdroid-cirrus-metadata`. Not yet submitted to
  fdroiddata; it needs a `v1.0.0` tag to build from.
- MCP over the older two-channel SSE transport, alongside the streamable-HTTP one. A server that
  answers the streamable-HTTP handshake with an `endpoint` event is retried on SSE automatically,
  so attaching an older server needs no configuration.
- Signed release builds. Pushing a `vX.Y.Z` tag builds, signs and verifies an APK and publishes
  it to GitHub Releases with generated notes and a `.sha256`, so Obtainium can track it.
- `github_create_or_update_file` — commit a single file to a repository, behind the existing
  default-off write switch. Resolves the blob SHA itself so updates do not need one supplied.
- Test coverage for `/api/show` capability detection, against recorded response fixtures.

### Fixed

- Cloud models showed a raw parameter count in the picker — `32682372656` rather than `32.7B` —
  because `/api/show` labels the value for local models but not for hosted ones. A model that
  publishes no count showed a bare `0`; it is now omitted.

### Changed

- Screenshots in the README, and a positioning tagline carried by it and the social preview card.
- `McpClient` split from its wire: transports now sit behind an `McpTransport` interface.
- The README no longer claims MCP servers can be attached from the UI. The client exists and is
  tested, but nothing reaches it yet, so the claim was not true.

- `/api/show` parsing extracted from `ModelRepository` into `ModelCapabilityDetector`, and the
  model picker's facets extracted from `ModelPickerSheet` into `ModelFilter`, so both are
  testable without a repository or a rendered sheet.
