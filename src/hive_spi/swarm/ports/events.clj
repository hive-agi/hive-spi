(ns hive-spi.swarm.ports.events
  "EVENTS / LIFECYCLE / TELEMETRY ports for the swarm slice, consumed by code
   hosted outside hive-mcp (hive-agent, tests, alternate hosts).

   Group coverage (vars + arities surveyed, per the reviewed proposal):

     IEventDispatch         <- hive-mcp.events.core (dispatch [event-v] /
                               handler-registered? [event-id])
     IAgentEventPublisher   <- hive-mcp.nats.bridge (publish-event! [payload] /
                               publish-shout! [payload]); soft requiring-resolve
                               from agent/ling/spawn.clj
     IHookTrigger           <- hive-mcp.hooks.core (trigger-hooks [registry event ctx],
                               as used by swarm/sync.clj; the registry is closed
                               over by the adapter, not passed through the port)
     ISwarmTelemetry        <- hive-mcp.telemetry.prometheus (set-lings-active! [n],
                               as used by tools/swarm/lifecycle.clj)
     IDagWaveScheduler      <- hive-mcp.scheduler.dag-waves (start-dag! [plan-id opts] /
                               stop-dag! [] / dag-status [], as used by
                               tools/agent/dag.clj)
     IAgentEventBroadcaster <- hive-mcp.transport.olympus (emit-agent-event!
                               [event-type agent-data], as used by swarm/sync.clj)
     IVesselContextSource   <- hive-mcp.protocols.vessel (resolve-agent-context
                               [agent-id], as used by hivemind/messaging.clj)

   NOT ported here, by design:
     - event-backbone protocol + slot already exist in hive-contracts
       (hive-contracts.event-backbone) — reuse, do not duplicate.
     - hive-mcp.protocols.dispatch moves VERBATIM (pure protocol + records).
     - ISweepable is already hive-spi.lifecycle.ports; hive-mcp.protocols.lifecycle
       is only a def-alias of it.
     - hive-mcp.system.registry: the lone swarm-slice reference is a stale
       require; sweep registration belongs to hive-spi.lifecycle.registry.

   Empty-policy: with nothing installed the slot yields the all-Noop `noop` —
   dispatch is silent, publishes are nil, hooks return [], the gauge write is
   dropped, the DAG reports {:active false}, broadcasts are dropped and no
   agent has a vessel context. A standalone hive-agent runs entirely on the
   Noop and degrades exactly the way today's requiring-resolve-miss branches
   do. No method throws.

   Reload-safety: `defprotocol` is not idempotent, so every declaration is
   guarded the way hive-spi.time.ports and hive-spi.swarm.ports.memory-scope
   do — re-evaluating this namespace never orphans existing implementations.
   Consumers must NOT re-defprotocol these names."
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Reload-safety guards — defprotocol is not idempotent.
;; =============================================================================

(defonce ^:private -ieventdispatch-defined? (atom false))
(defonce ^:private -iagenteventpublisher-defined? (atom false))
(defonce ^:private -ihooktrigger-defined? (atom false))
(defonce ^:private -iswarmtelemetry-defined? (atom false))
(defonce ^:private -idagwavescheduler-defined? (atom false))
(defonce ^:private -iagenteventbroadcaster-defined? (atom false))
(defonce ^:private -ivesselcontextsource-defined? (atom false))

;; =============================================================================
;; Port 1 — event dispatch (was hive-mcp.events.core)
;; =============================================================================

(when (compare-and-set! -ieventdispatch-defined? false true)
  (defprotocol IEventDispatch
    "The in-process event bus the swarm slice talks to.

     Preferred mapping: hive-events 0.5.16 already provides both operations
     (`hive.events/dispatch`, `hive.events/handler-registered?`), and
     hive-agent.events.dispatch/emit! shows the canonical guarded pattern.
     Install this port only when the host wants to own the registry itself."
    (dispatch! [this event-v]
      "Dispatch EVENT-V, an [event-id payload-map] vector. Fire-and-forget:
       must not throw even when no handler is registered. Returns EVENT-V.")

    (handler-registered? [this event-id]
      "True iff some handler is registered for EVENT-ID. Callers use this to
       guard dispatch during bootstrap (boot-race pattern in
       swarm/datascript/lings.clj and swarm/lifecycle/sweep.clj).")))

;; =============================================================================
;; Port 2 — system-event publisher (was hive-mcp.nats.bridge)
;; =============================================================================

(when (compare-and-set! -iagenteventpublisher-defined? false true)
  (defprotocol IAgentEventPublisher
    "Publish agent lifecycle events onto the distributed event backbone.

     This is the narrowing of nats.bridge for the swarm slice: the swarm never
     needs subjects, stamps or subscriptions — only 'put this payload on the
     bus'. Subject derivation (hive.v1.* hierarchy) is the adapter's business.

     Payload contract (unchanged from nats.bridge/publish-event!):
       {:type :agent-spawn|:agent-kill|..., :agent-id \"ling-123\",
        :timestamp ms, :data {...}}
     Shout contract (publish-shout!):
       {:agent-id \"...\" :event-type :progress :message \"...\"
        :task \"...\" :project-id \"...\" :timestamp ms :data {...}}"
    (publish-event! [this payload]
      "Publish a system event map. No-op when disconnected. Returns nil.")

    (publish-shout! [this payload]
      "Publish a hivemind shout map. No-op when disconnected. Returns nil.")))

;; =============================================================================
;; Port 3 — domain hooks (was hive-mcp.hooks.core)
;; =============================================================================

(when (compare-and-set! -ihooktrigger-defined? false true)
  (defprotocol IHookTrigger
    "Trigger the domain hook registry (:task-complete, :task-start, ...).

     The hook REGISTRY (event-type -> [handlers] atom) stays host-owned; the
     port carries only the trigger so swarm/sync.clj does not name
     hive-mcp.hooks.core. The adapter closes over the registry and delegates
     to hooks.core/trigger-hooks, preserving registration-order execution and
     safe-failure semantics (a throwing handler is logged, not raised)."
    (trigger-hooks [this event context]
      "Run every handler registered for EVENT (a keyword like :task-complete)
       with CONTEXT (a map). Returns a vector of per-handler results —
       {:result v} or {:error ex} per handler — never throws.")))

;; =============================================================================
;; Port 4 — swarm telemetry gauge (was hive-mcp.telemetry.prometheus)
;; =============================================================================

(when (compare-and-set! -iswarmtelemetry-defined? false true)
  (defprotocol ISwarmTelemetry
    "The single metric the swarm slice writes: active-ling count.

     tools/swarm/lifecycle.clj calls set-lings-active! on spawn, kill, batch
     kill and collect paths. Everything else prometheus exposes stays host
     business — do not widen this port into a general metrics facade."
    (set-lings-active! [this n]
      "Set the gauge 'active lings' to N. No-op without a telemetry backend.")))

;; =============================================================================
;; Port 5 — DAG wave scheduler (was hive-mcp.scheduler.dag-waves)
;; =============================================================================

(when (compare-and-set! -idagwavescheduler-defined? false true)
  (defprotocol IDagWaveScheduler
    "Plan-driven ling scheduling (tools/agent/dag.clj MCP surface).

     start-dag! accepts {:cwd dir :max-slots n :presets [...] :project-id ...};
     start! throws on an already-active plan in the host, but the PORT must
     not: report the refusal in the return value so a Noop host and a
     real scheduler are indistinguishable to error handling."
    (start-dag! [this plan-id opts]
      "Start the DAG scheduler for PLAN-ID with OPTS. Returns the scheduler's
       result map (at minimum {:active bool :plan-id ...}); a Noop returns
       {:active false :plan-id plan-id :reason :no-scheduler}.")

    (stop-dag! [this]
      "Stop the active scheduler, if any. Idempotent. Returns the scheduler's
       stop summary ({:stopped true :plan-id ... :completed-count ...}), or
       nil when there is no scheduler.")

    (dag-status [this]
      "A progress snapshot {:active bool :plan-id ... :max-slots ... ...}.
       The Noop returns {:active false}.")))

;; =============================================================================
;; Port 6 — agent-event broadcaster (was hive-mcp.transport.olympus)
;; =============================================================================

(when (compare-and-set! -iagenteventbroadcaster-defined? false true)
  (defprotocol IAgentEventBroadcaster
    "Emit agent lifecycle events to the headed UI (Olympus Web UI today).

     Fire-and-forget by contract — swarm/sync.clj already wraps every call in
     try/catch and logs failures; the port formalizes that: implementers log,
     callers stay try-free against the port."
    (emit-agent-event! [this event-type agent-data]
      "Emit EVENT-TYPE (:agent-spawned | :agent-status | :agent-killed) with
       AGENT-DATA (a map). No-op without a UI. Returns nil.")))

;; =============================================================================
;; Port 7 — vessel context lookup (was hive-mcp.protocols.vessel)
;; =============================================================================

(when (compare-and-set! -ivesselcontextsource-defined? false true)
  (defprotocol IVesselContextSource
    "Resolve which headed environment an agent runs in.

     IVessel itself already lives in hive-addon (hive-addon.vessel/IVessel) and
     vessels implement THAT — it is not re-declared here. What crosses the seam
     is only the host-registry lookup hive-mcp.protocols.vessel owns: query
     every registered vessel, first non-nil answer wins."
    (resolve-agent-context [this agent-id]
      "The context AGENT-ID runs in as {:project-id :cwd :session-id}, or nil
       when no vessel knows the agent. Must not throw; nil is normal in
       headless mode.")))

;; =============================================================================
;; Noop — headless / standalone is a first-class mode
;; =============================================================================

(def noop
  "The all-Noop implementation. Every protocol degrades silently: dispatch is
   a silent no-op (callers checking handler-registered? simply never fire),
   publishes and UI emissions are dropped, hook triggering returns an empty
   result vector, the telemetry gauge write is discarded, the DAG scheduler
   reports an inactive plan instead of throwing, and no agent has a vessel
   context (headless). Never throws."
  (reify
    IEventDispatch
    (dispatch! [_ _event-v] nil)
    (handler-registered? [_ _event-id] false)

    IAgentEventPublisher
    (publish-event! [_ _payload] nil)
    (publish-shout! [_ _payload] nil)

    IHookTrigger
    (trigger-hooks [_ _event _context] [])

    ISwarmTelemetry
    (set-lings-active! [_ _n] nil)

    IDagWaveScheduler
    (start-dag! [_ plan-id _opts]
      {:active false :plan-id plan-id :reason :no-scheduler})
    (stop-dag! [_] nil)
    (dag-status [_] {:active false})

    IAgentEventBroadcaster
    (emit-agent-event! [_ _event-type _agent-data] nil)

    IVesselContextSource
    (resolve-agent-context [_ _agent-id] nil)))

;; =============================================================================
;; Installation — one slot, like hive-spi.time.ports
;; =============================================================================

(defonce ^:private port-slot
  (slot/single-slot {:validate #(satisfies? IEventDispatch %)
                     :on-empty (constantly noop)}))

(defn set-events!
  "Install IMPL as the active events/lifecycle/telemetry port. IMPL must
   satisfy IEventDispatch; the adapter built by the host implements every
   protocol in this namespace. Returns IMPL."
  [impl]
  (slot/install! port-slot impl))

(defn get-events
  "The active port: the installed implementation, else the Noop."
  []
  (slot/current port-slot))

(defn clear-events!
  "Remove the installed port, so consumers fall back to the Noop.
   Returns nil."
  []
  (slot/clear! port-slot))

(defn events-set?
  "True iff a port is explicitly installed. The Noop fallback does not count."
  []
  (slot/present? port-slot))
