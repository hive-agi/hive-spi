(ns hive-spi.swarm.ports.ling-host
  "Ports for the host-bound halves of the ling spawn pipeline, consumed by
   the swarm subsystem hosted outside hive-mcp.

   Two small protocols (ISP): ILingReadiness answers 'is this ling ready to
   receive a dispatch'; ILingCatchup answers 'what compact context should be
   injected into this ling's task'. Both are answered by the HOST process
   (hive-mcp): readiness polls DataScript rows plus per-spawn-mode CLI state
   (Emacs elisp, headless stdout, agent-sdk session), and catchup requires
   the extension-layer context reconstruction plus the token-budget
   truncator — state and machinery a standalone process must not own.

   Config reads (IGlobalConfig, IHeadlessDefaults, IPresetFiles) are
   deliberately NOT ported here: hive-agent.config serves them after the
   move. Only the two genuine host calls live on this port.

   Empty-policy: with nothing installed the port resolves the Noop —
   readiness answers the same failure shape a poll timeout produces, so the
   caller takes its existing 'ling not ready, task will not be dispatched'
   branch; catchup answers nil, the documented 'lings run /catchup
   themselves' fallback. Never throws.

   Reload-safety: `defprotocol` is not idempotent, so each declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations. Consumers must NOT re-defprotocol these names."
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -ilingreadiness-defined? (atom false))

(when (compare-and-set! -ilingreadiness-defined? false true)
  (defprotocol ILingReadiness
    "Host-bound readiness polling (DataScript row + per-spawn-mode CLI
     state) before the spawn pipeline dispatches a task. Replaces the soft
     requiring-resolve of
     hive-mcp.tools.consolidated.workflow.readiness/wait-for-ling-ready."

    (wait-for-ling-ready [this agent-id spawn-mode]
      "Block until ling `agent-id` (spawned in `spawn-mode`) is ready, or
       the configured readiness timeout elapses; returns a map with at
       least :ready?, :phase, :elapsed-ms and :attempts (:slave when
       DataScript knows the ling). The timeout and the per-mode polling —
       including the config read [:services :forge :readiness-timeout-ms] —
       stay host-side. A not-ready answer is terminal for the dispatch, the
       same branch as today's poll timeout.")))

(defonce ^:private -ilingcatchup-defined? (atom false))

(when (compare-and-set! -ilingcatchup-defined? false true)
  (defprotocol ILingCatchup
    "Compact context injection at spawn time. Replaces the STATIC require of
     hive-mcp.workflows.catchup-ling in the spawn pipeline — the impl is
     host-bound twice over (requiring-resolves the extension layer AND
     truncates through the token budget), so neither belongs outside
     hive-mcp."

    (ling-catchup [this opts]
      "`opts` {:directory :task :kanban-task-id :token-budget} → the
       budget-capped context string (hard cap 10000 chars), or nil when
       there is nothing to inject. nil is the documented fallback: lings
       run /catchup themselves.")))

;;; ============================================================================
;;; Noop — the degraded host: readiness never ready, catchup never injects
;;; ============================================================================

(def noop
  "Degraded implementation for a standalone process: readiness answers the
   failure shape of a poll that never saw the ling ({:ready? false :phase
   :no-host :elapsed-ms 0 :attempts 0}), catchup answers nil. Never throws."
  (reify
    ILingReadiness
    (wait-for-ling-ready [_this _agent-id _spawn-mode]
      {:ready? false :phase :no-host :elapsed-ms 0 :attempts 0})

    ILingCatchup
    (ling-catchup [_this _opts] nil)))

(defonce ^:private port-slot
  (slot/single-slot {:validate #(satisfies? ILingReadiness %)
                     :on-empty (constantly noop)}))

(defn set-ling-host!
  "Install IMPL as the active ling-readiness/catchup port. Returns IMPL."
  [impl]
  (slot/install! port-slot impl))

(defn get-ling-host
  "The active port: the installed one, else the Noop."
  []
  (slot/current port-slot))

(defn clear-ling-host!
  "Remove the installed port, so consumers fall back to the Noop.
   Returns nil."
  []
  (slot/clear! port-slot))

(defn ling-host-set?
  "True iff a port is explicitly installed. The Noop does not count."
  []
  (slot/present? port-slot))
