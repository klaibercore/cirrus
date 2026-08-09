# Changelog

All notable changes to Cirrus are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Test coverage for `/api/show` capability detection, against recorded response fixtures.

### Changed

- `/api/show` parsing extracted from `ModelRepository` into `ModelCapabilityDetector`, and the
  model picker's facets extracted from `ModelPickerSheet` into `ModelFilter`, so both are
  testable without a repository or a rendered sheet.
