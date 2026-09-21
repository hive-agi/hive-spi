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

## [Unreleased]

### Added

- `hive-spi.swarm.dispatch-context`: `IDispatchContext` with its two values,
  `TextContext` and `RefContext`, and `->text-context`, `->ref-context`,
  `ensure-context`. It is the dispatch-context protocol hive-mcp carried as
  `hive-mcp.protocols.dispatch`, made host-free: a `RefContext` takes its
  reconstruction function as data and resolves no host namespace, and one
  built without a function resolves to its prompt and references unexpanded.
  `resolve-context` never throws; a throwing reconstruction is reported
  inside the resolved prompt. A new protocol, so minor.
- `hive-spi.swarm.ports.memory-scope`: `IDiscPropagation`, an optional
  extension of the installed memory-scope port, and `content-changed!`, the
  function consumers call. A swarm that sees a claimed file's content change
  tells the host with a cause keyword (`:hash-mismatch`); the host owns what
  that cause weighs and what it spreads to. `content-changed!` answers nil
  for the Noop, for a host that lacks the extension and for a host whose
  propagation throws. `IProjectScope`, `IDiscStaleness` and `noop` are
  untouched, so every existing implementation keeps working. Minor.
- `hive-spi.memory.conformance`: the executable IMemoryStore contract, as data
  (`cases`) with two projections, `defconformance` (one deftest per case) and
  `run-conformance`. Covers every IMemoryStore method, every documented
  query-entries opt, the expiry and duplicate paths, the degraded semantic
  branch, and each role protocol when the store satisfies it.
- `hive-spi.memory.stub`: atom-backed reference store that passes the suite;
  an optional `:embedder` turns its semantic branch on.
- `hive-spi.memory.entry`: pure entry helpers (type tokens, ISO parsing,
  expiry predicates, reference filter, ordering, projection).
- `hive-spi.memory.ports/degraded-search-result`: the value search-similar
  returns when supports-semantic-search? is false.
- `hive-spi.catchup.registry`: a contribution registry for catchup blocks.
  A contributor (a host domain or an addon) registers
  `{:block/id kw :block/fn (fn [ctx]) :block/order int}`; the host calls
  `compose` with a context and receives `{:blocks {id value} :failed {id
  message}}`, blocks run in `:block/order` and a throwing block never aborts
  the others. Minor: a new seam, nothing existing changed.
- `hive-spi.kanban`: the kanban port. `IKanbanRead` (`list-tasks`,
  `get-task`) and `IKanbanWrite` (`transition!`, `create-task!`), the malli
  schemas for their arguments and results (`Task`, `ListQuery`,
  `TransitionRequest`, `CreateRequest`, `TransitionResult`, `CreateResult`),
  and `conformance`, the cases every provider and every double must pass.
  `hive-spi.kanban.registry` keys the two roles separately and fronts them
  with a facade that degrades to Noops (reads empty, writes
  `{:err {:error :kanban/provider-unavailable}}`) when nothing is
  registered. `hive-spi.kanban.stub` is a recording, atom-backed double held
  to the same conformance. hive-mcp carries this port host-local as
  `hive-mcp.spi.kanban`; this is its library home. New protocols, so minor.

### Changed

- IMemoryStore method docstrings now state the contract the conformance suite
  pins: add-entry! returns the id, cleanup-expired! returns
  `{:count n :deleted-ids [...]}`, search-similar returns a sequential and the
  degraded value when unsupported, :type compares by name, :tags are a set.

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
