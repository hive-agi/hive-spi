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

`src/hive_spi/workflow/ports.cljc` defines the workflow ports:

| Protocol            | Purpose                                                  |
|---------------------|----------------------------------------------------------|
| `IPlanCompiler`     | Lower a Plan-EDN front-end into the wf-IR node-map tree. |
| `IPlanGraph`        | Read-only view of a Plan as a Kahn-orderable DAG.        |
| `ITaskBoard`        | Headless task/kanban surface used by methods.            |
| `IHeadlessDispatcher` | Spawn/dispatch on a headless backend.                  |
| `IWorkflowStore`    | Persistence facade for authored workflow ASTs.           |
| `IEffectHandler`    | Self-describing verb seam for routing effects.           |
| `IIntrospectable`   | Probe for strategies and verbs.                          |

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
├── src/hive_spi/slot.cljc          — mutable injection points
├── src/hive_spi/provider.cljc      — providers as data, registry as a value
├── src/hive_spi/workflow/ports.cljc
└── test/hive_spi/workflow/ports_test.clj
```
