(ns hive-spi.addon.headless-caps
  "Optional capability contracts for headless backends (ISP).

   Each protocol is opt-in: the host checks `satisfies?` before calling, so a
   backend that declares none still dispatches. They live beside
   `hive-spi.addon.headless` because a backend implements them, and a backend
   must be able to do that without compile-depending on a host.

   Reload-safety: `defprotocol` is not idempotent, so each declaration is
   guarded: re-evaluating this namespace will not orphan existing
   implementations.")

;; SPDX-License-Identifier: MIT

(defonce ^:private -ihookable-defined? (atom false))

(when (compare-and-set! -ihookable-defined? false true)
  (defprotocol IHookable
    "A backend that accepts hook injection: gating hooks, pre-tool-use
     validation, post-tool-use logging."

    (register-hooks! [this ling-id hooks-map]
      "Register hooks for a session.
       HOOKS-MAP keys :pre-tool-use, :post-tool-use, :on-error, :on-complete,
       each a (fn [hook-context] ...).
       Returns {:registered? bool :hook-count int}.")

    (active-hooks [this ling-id]
      "The currently active hooks map for a session, or nil.")))

(defonce ^:private -icheckpointable-defined? (atom false))

(when (compare-and-set! -icheckpointable-defined? false true)
  (defprotocol ICheckpointable
    "A backend that can save session state and return to it."

    (checkpoint! [this ling-id]
      "Checkpoint the current session state.
       Returns {:checkpoint-id str :created-at long}.")

    (rewind! [this ling-id checkpoint-id]
      "Rewind the session to a previous checkpoint.
       Returns {:rewound? bool :checkpoint-id str}.")))

(defonce ^:private -isubagenthost-defined? (atom false))

(when (compare-and-set! -isubagenthost-defined? false true)
  (defprotocol ISubagentHost
    "A backend that supports native subagent definitions: nested agent
     hierarchies declared by the caller."

    (register-subagents! [this ling-id agent-defs]
      "Register subagent definitions for a session.
       AGENT-DEFS maps agent-name -> {:description str :prompt str
       :tools [str] :model str}.
       Returns {:registered? bool :agent-count int}.")

    (list-subagents [this ling-id]
      "The registered subagent definitions for a session, or nil.")))

(defonce ^:private -ibudgetguardable-defined? (atom false))

(when (compare-and-set! -ibudgetguardable-defined? false true)
  (defprotocol IBudgetGuardable
    "A backend that enforces a per-session spending limit, interrupting the
     session when it is exceeded."

    (set-budget! [this ling-id max-usd]
      "Set the maximum USD budget for a session.
       Returns {:budget-set? bool :max-usd number}.")

    (budget-status [this ling-id]
      "Budget status for a session:
       {:max-usd number :spent-usd number :remaining-usd number
        :exceeded? bool}, or nil when no budget is set.")))

(def capability-protocols
  "Capability keyword -> the protocol a backend implements to provide it.
   One registry so a host can report what a backend opted into without
   naming each protocol at its call site."
  {:cap/hooks         IHookable
   :cap/checkpointing ICheckpointable
   :cap/subagents     ISubagentHost
   :cap/budget        IBudgetGuardable})

(defn provided-capabilities
  "The capability keywords BACKEND actually implements, derived by
   satisfaction rather than by declaration."
  [backend]
  (into #{}
        (keep (fn [[cap proto]] (when (satisfies? proto backend) cap)))
        capability-protocols))
