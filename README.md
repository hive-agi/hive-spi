# hive-spi

Service Provider Interface (SPI) contracts for the hive-workflows rebuild
(HWF2 — see hive memory `20260627145530-2c4394a8`).

This library is **pure protocol definitions** — no implementations, no
runtime state, no third-party deps. It exists so the spine
(hive-workflows, hive-mcp, hive-notify, addons) can depend on stable
contracts rather than concrete impls.

## D1 SAFE SCAFFOLD slice (current)

`src/hive_spi/workflow/ports.cljc` defines **only** the seven new ports that
are unblocked by the canonical design decision:

| Protocol            | Purpose                                                  |
|---------------------|----------------------------------------------------------|
| `IPlanCompiler`     | Lower a Plan-EDN front-end into the wf-IR node-map tree. |
| `IPlanGraph`        | Read-only view of a Plan as a Kahn-orderable DAG.        |
| `ITaskBoard`        | Headless task/kanban surface used by methods.            |
| `IHeadlessDispatcher` | Spawn/dispatch on a headless backend (ling-style).     |
| `IWorkflowStore`    | Persistence facade for authored workflow ASTs.           |
| `IEffectHandler`    | Self-describing verb seam (routes into hive.events.fx).  |
| `IIntrospectable`   | Sibling probe for strategies/verbs (satisfies?-tested).  |

### Deferred (NOT in this slice)

The following are documented in the design decision but **gated on open
questions** with Pedro and intentionally **not** defined here:

- `WorkflowEvent` ADT (`hive-dsl/defadt` 8 variants) — pending taxonomy lock.
- `WorkflowAST` malli schema — pending front-end binding decision.
- `INotify` — pending hive-notify home decision.
- `IWorkflowEngine` / `IDispatchStrategy` / `WorkflowStrategyEntry` — stay
  in `hive-mcp.protocols.workflow` until M9 repo-split (88 callers).

## Layout

```
hive-spi/
├── deps.edn
├── .hive-project.edn
├── src/hive_spi/workflow/ports.cljc
└── test/hive_spi/workflow/ports_test.clj
```
