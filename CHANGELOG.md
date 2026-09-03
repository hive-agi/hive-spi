# Changelog

Notable changes to hive-spi. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file starts at 1.0.0. Earlier history is in the git log and the release
tags (v0.1.0 through v0.2.1).

## What the version number promises

The public contract is the protocol names and their method signatures, the
schemas in `hive-spi.schema.*`, and the `slot` / `provider` injection APIs.
Removing a protocol, renaming a method, or changing an existing method's arity
is major. Adding a protocol, including an optional extension protocol, is
minor. Adding a method to an existing protocol is major, because every
implementation must grow it; new behaviour arrives as a new optional protocol
instead.

The library declares no third-party runtime dependencies, so it cannot
contribute a version conflict to a consumer's tree.

## [1.0.0]

The seam stopped moving. Nothing in the contract changed for this release.
1.0.0 is the promise that it will not change silently from here, made because
hive-mcp cannot promise stability over ports that do not promise it.

### Added

- `CHANGELOG.md` (this file) and a `## Versioning` section in the README
  stating what a major, minor and patch bump each mean for an implementor.

### Fixed

- The README documented `src/hive_spi/workflow/ports.cljc` and seven protocols
  (`IPlanCompiler`, `IPlanGraph`, `ITaskBoard`, `IHeadlessDispatcher`,
  `IWorkflowStore`, `IEffectHandler`, `IIntrospectable`) that no longer exist
  under those names. The workflow seam is `IWorkflowEngine` and
  `IWorkflowPersistence` in `hive-spi.workflow.engine`, plus `IDispatchStrategy`
  in `hive-spi.workflow.strategy`. The Ports section now lists every port
  family that actually ships, and the Layout tree matches the repository.
