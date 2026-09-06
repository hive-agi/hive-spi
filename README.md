# hive-spi

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-spi.svg)](https://clojars.org/io.github.hive-agi/hive-spi)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/hive-spi)](https://cljdoc.org/d/io.github.hive-agi/hive-spi/CURRENT)
[![release](https://github.com/hive-agi/hive-spi/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-spi/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

<!-- /hive-badges -->

Service Provider Interface (SPI) protocol contracts for hive workflows.

This library is **pure protocol definitions** — no implementations, no
runtime state, no third-party deps. It exists so that consumers can depend
on stable contracts rather than concrete implementations.

## Ports

The seam is grouped by domain, one namespace per family. Every protocol below
is part of the 1.0 contract.

| Namespace                     | Protocols                                                                                                                  |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| `hive-spi.workflow.engine`    | `IWorkflowEngine`, `IWorkflowPersistence`                                                                                    |
| `hive-spi.workflow.strategy`  | `IDispatchStrategy`                                                                                                          |
| `hive-spi.memory.ports`       | `IMemoryStore` + 7 optional extensions (analytics, metadata-write, staleness, batch, routing, temporal, liveness)             |
| `hive-spi.kg.protocol`        | `IKGStore`, `IPersistentKGStore`, `ITemporalKGStore`                                                                         |
| `hive-spi.kg.factory`         | `IStoreFactory`                                                                                                              |
| `hive-spi.kg.conn-init`       | `IConnInit`                                                                                                                  |
| `hive-spi.addon.headless`     | `IHeadlessBackend`, `IHeadlessCapabilities`                                                                                  |
| `hive-spi.addon.headless-caps`| `IHookable`, `ICheckpointable`, `ISubagentHost`, `IBudgetGuardable`                                                          |
| `hive-spi.editor.ports`       | `IEditorPort` (required) + `IEditorBufferPort`, `IEditorDocsPort`, `IEditorDaemonPort` (optional)                             |
| `hive-spi.cider.ports`        | `ICiderPort`                                                                                                                 |
| `hive-spi.guard.ports`        | `IGuardRuleSource`, `IGuard`, `IGuardProjection`                                                                             |
| `hive-spi.diag.ports`         | `IHeapProbe`, `IRetainedSizer`, `IAllocationSampler`, `IProfiler`, `ICacheProbe`, `IMemoryClinic`                             |
| `hive-spi.lifecycle.ports`    | `IShutdownHook`, `ISweepable`, `IResourceOwner`, `IShutdownBudget`                                                            |
| `hive-spi.embeddings.ports`   | `EmbeddingProvider`                                                                                                          |
| `hive-spi.crypto.hash` / `.ports` | `IHasher`, `ISigner`                                                                                                     |
| `hive-spi.log.ports`          | `ILogger`                                                                                                                    |
| `hive-spi.time.ports`         | `IClock`                                                                                                                     |
| `hive-spi.notify`             | `INotify`                                                                                                                    |
| `hive-spi.slot`               | `ISlot`, `IRegistry`                                                                                                         |
| `hive-spi.ingest.ports`       | `ISource` (required) + `ISourceHealth`, `IParserRule` (optional); `hive-spi.ingest.model` carries the Document they promise, `hive-spi.ingest.registry` the owner-scoped registry, `hive-spi.ingest.tck` the public conformance kit. Guide: `resources/hive_spi/writing-an-ingest-provider.md` |

A protocol marked *optional* is one an implementation may leave unextended:
callers must probe with `satisfies?` rather than assume it.

## Injection points

Two shapes, for two different questions.

`src/hive_spi/slot.cljc` — a **mutable holder**. Use it when the injection
point is the process: one active implementation (`single-slot`) or a keyed map
of them (`multi-slot`), installed at boot and read from anywhere.

`src/hive_spi/provider.cljc` — an immutable **registry of providers as data**.
Use it when two of them must coexist: a request and its test, a tenant and
another tenant. A provider is an implementation plus a **profile** — plain data
describing its measured behaviour — and the registry is a value threaded
through a call rather than a global installed into.

```clojure
(require '[hive-spi.provider :as provider])

(def RailProfile
  (provider/profile-schema [[:provider/currency :keyword]]))

(def rails
  (provider/registry [(provider/entry #:provider{:id :chain
                                                 :currency :xmr
                                                 :capabilities #{:poll}}
                                      (chain-rail config))
                      (provider/entry #:provider{:id :cards :currency :usd}
                                      (card-rail config))]
                     {:schema RailProfile
                      :satisfies-port? #(satisfies? IRail %)}))

;; the SUBJECT selects its provider; a caller cannot substitute one
(provider/via rails invoice :invoice/provider #(charge! % amount))

;; and a provider is never asked to do what its profile does not admit
(provider/via-capable rails invoice :invoice/provider :poll #(poll % invoice))
```

Three rules the API enforces rather than documents:

1. **Profiles are validated at registration** — a malformed provider fails at
   boot, where an operator is watching, not at the first caller.
2. **Capability is read off the profile**, never inferred from the id. Adding a
   provider is an entry in a registry, not a branch in a component.
3. **A subject selects its provider.** `for-subject` / `via` take the id from
   the subject, so a caller naming a provider is stating a claim to be checked
   — never the authority that resolves it.

`conformance` / `conforming?` are the Liskov check for a test: every registered
implementation is substitutable for the port, and every profile means what its
schema says.

## Layout

```
hive-spi/
├── deps.edn
├── .hive-project.edn
├── src/hive_spi/slot.cljc            (mutable injection points)
├── src/hive_spi/provider.cljc        (providers as data, registry as a value)
├── src/hive_spi/schema/              (capability, derive, gen, help, registry, typed)
├── src/hive_spi/<domain>/ports.cljc  (one port family per domain, see Ports)
└── test/hive_spi/                    (a conformance suite per port family)
```

## Versioning

From 1.0.0 this library follows [Semantic Versioning](https://semver.org).
The public contract is: the protocol names and their method signatures, the
schemas in `hive-spi.schema.*`, and the `slot` / `provider` injection APIs.

- Removing a protocol, renaming a method, or changing an existing method's
  arity or argument order is a **major** change.
- Adding a protocol, or adding an *optional* extension protocol, is a **minor**
  change: existing implementations keep satisfying what they already satisfied.
- Adding a method to an existing protocol is a **major** change, because every
  implementation must grow it. New behaviour arrives as a new optional
  protocol instead.

The library carries no third-party runtime dependencies, so a consumer's
version conflicts can never come from here.
