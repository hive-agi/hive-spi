# hive-spi

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

## Layout

```
hive-spi/
├── deps.edn
├── .hive-project.edn
├── src/hive_spi/workflow/ports.cljc
└── test/hive_spi/workflow/ports_test.clj
```
