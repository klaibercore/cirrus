# Changelog

All notable changes to Cirrus are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Cirrus has not been tagged yet, so everything below is what v1.0.0 will contain. Cutting the
release moves these entries under `## [1.0.0] - YYYY-MM-DD`; see
[docs/RELEASING.md](docs/RELEASING.md).

### Added

- Signed release builds. Pushing a `vX.Y.Z` tag builds, signs and verifies an APK and publishes
  it to GitHub Releases with generated notes and a `.sha256`, so Obtainium can track it.
- `github_create_or_update_file` — commit a single file to a repository, behind the existing
  default-off write switch. Resolves the blob SHA itself so updates do not need one supplied.
- Test coverage for `/api/show` capability detection, against recorded response fixtures.

### Changed

- `/api/show` parsing extracted from `ModelRepository` into `ModelCapabilityDetector`, and the
  model picker's facets extracted from `ModelPickerSheet` into `ModelFilter`, so both are
  testable without a repository or a rendered sheet.
