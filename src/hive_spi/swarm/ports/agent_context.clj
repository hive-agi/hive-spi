(ns hive-spi.swarm.ports.agent-context
  "Swarm call-context + budget ports.

   Declared with no host dependency, so a namespace that stamps an entry stays
   loadable on every runtime and the host's request context arrives through an
   installed IAgentCallContext, and per-agent budget enforcement through an
   installed IBudgetGuardrail.

   Contract:
   - `current-agent-id` is the agent-id of the swarm call in flight, or nil.
   - `current-directory` is the working directory of the call in flight, or nil.
     Callers treat nil as 'no context in flight' and branch on it.
   - `register-budget!` starts cumulative-USD tracking for an agent
     ([agent-id max-budget-usd opts] with OPTS {:model str}); returns the entry
     map, or nil when tracking is unavailable. Must not throw.
   - `deregister-budget!` stops tracking ([agent-id]); returns the removed
     entry (or nil), matching the hook's existing return contract.

   Empty-policy: with no context installed the port yields a Noop that answers
   nil for every reader and treats budget registration as a no-op — never
   throws. This matches the degraded paths callers already exercise today
   (nil context, absent budget hook).

   Reload-safety: `defprotocol` is not idempotent, so each declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations. Consumers must NOT re-defprotocol these names."
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -iagentcallcontext-defined? (atom false))

(when (compare-and-set! -iagentcallcontext-defined? false true)
  (defprotocol IAgentCallContext
    "The request context of the swarm tool call currently executing, owned by
     the hosting process.

     Callers treat nil as 'no context in flight' — both existing callers
     (hivemind/tools, tools/swarm/lifecycle) already branch on nil."
    (current-agent-id [this]
      "Agent-id of the call in flight, or nil.")
    (current-directory [this]
      "Working directory of the call in flight, or nil.")))

(defonce ^:private -ibudgetguardrail-defined? (atom false))

(when (compare-and-set! -ibudgetguardrail-defined? false true)
  (defprotocol IBudgetGuardrail
    "Per-agent cumulative-USD budget tracking, owned by the host.

     Mirrors the exact arities the swarm spawn pipeline already soft-resolves.
     When no guardrail is installed the spawn pipeline behaves exactly as it
     does today when the hook is absent — no budget enforcement, never an
     error."
    (register-budget! [this agent-id max-budget-usd opts]
      "Start tracking spend for AGENT-ID with limit MAX-BUDGET-USD.
       OPTS {:model str}. Returns the entry map, or nil when tracking is
       unavailable. Must not throw.")
    (deregister-budget! [this agent-id]
      "Stop tracking spend for AGENT-ID. Returns the removed entry (or nil),
       matching the hook's existing return contract.")))

(def noop
  "Degraded implementation for a standalone process: no context is ever bound
   (both readers answer nil) and budget registration is accepted and forgotten
   (nothing is ever enforced). Never throws."
  (reify IAgentCallContext
    (current-agent-id [_this] nil)
    (current-directory [_this] nil)
    IBudgetGuardrail
    (register-budget! [_this _agent-id _max-budget-usd _opts] nil)
    (deregister-budget! [_this _agent-id] nil)))

(defonce ^:private port-slot
  (slot/single-slot {:validate #(satisfies? IAgentCallContext %)
                     :on-empty (constantly noop)}))

(defn set-agent-context!
  "Install IMPL as the active agent context (and budget guardrail).
   Returns IMPL. Throws when IMPL does not satisfy IAgentCallContext."
  [impl]
  (slot/install! port-slot impl))

(defn get-agent-context
  "The active agent context: the installed one, else the Noop."
  []
  (slot/current port-slot))

(defn clear-agent-context!
  "Remove the installed agent context, so consumers fall back to the Noop.
   Returns nil."
  []
  (slot/clear! port-slot))

(defn agent-context-set?
  "True iff an agent context is explicitly installed. The Noop does not count."
  []
  (slot/present? port-slot))
