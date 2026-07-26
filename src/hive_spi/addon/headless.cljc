(ns hive-spi.addon.headless
  "Contracts for addon-contributed headless backends.

   A backend is initialised during the addon's `initialize!` and torn down
   during its `shutdown!`. Method arities and argument semantics mirror the
   host's ling-strategy surface so the registry can dispatch to an
   addon-contributed backend and an in-tree one identically.

   Reload-safety: `defprotocol` is not idempotent, so each declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations.")

;; SPDX-License-Identifier: MIT

(defonce ^:private -iheadlessbackend-defined? (atom false))

(when (compare-and-set! -iheadlessbackend-defined? false true)
  (defprotocol IHeadlessBackend
    "A headless session backend. Implementations that are addons must also
     satisfy IAddon; in-tree backends need not."

    (headless-id [this]
      "The stable keyword identifying this backend, used as the registry's
       dispatch key.")

    (headless-spawn! [this ctx opts]
      "Spawn a headless session.
       CTX  {:id :cwd :presets :project-id :model}
       OPTS {:task :buffer-capacity :env-extra :agents}
       Returns the slave-id string. Throws ex-info {:id :error} on failure.")

    (headless-dispatch! [this ctx task-opts]
      "Dispatch a task to a running session.
       CTX {:id}, TASK-OPTS {:task :timeout-ms}, :timeout-ms defaulting to
       60000. Returns a result channel, true, or a backend-specific value.
       Throws ex-info {:ling-id :error} on failure.")

    (headless-status [this ctx ds-status]
      "Liveness and status for the session.
       CTX {:id}; DS-STATUS may be nil or {:slave/status kw}. Returns
       {:slave/id str :slave/status kw} or nil, plus backend-specific keys.")

    (headless-kill! [this ctx]
      "Terminate the session. CTX {:id}. Returns {:killed? true :id str} or
       {:killed? false :id str :reason kw}.")

    (headless-interrupt! [this ctx]
      "Interrupt the session's current task. CTX {:id}. Returns
       {:success? true :ling-id str} or {:success? false :ling-id str
       :reason kw}; backends without interrupt support return
       :reason :not-supported.")))

(defonce ^:private -iheadlesscapabilities-defined? (atom false))

(when (compare-and-set! -iheadlesscapabilities-defined? false true)
  (defprotocol IHeadlessCapabilities
    "Optional capability declaration, used for registry queries and feature
     gating."

    (declared-capabilities [this]
      "The set of capability keywords this backend supports, e.g.
       #{:cap/hooks :cap/interrupts :cap/subagents :cap/checkpointing}.")))

(def default-capabilities
  "Capabilities assumed for a backend that does not declare its own."
  #{:cap/streaming :cap/multi-turn})

(defn headless-backend?
  "True iff X satisfies IHeadlessBackend. Does not require IAddon."
  [x]
  (satisfies? IHeadlessBackend x))

(defn headless-addon?
  "True iff X satisfies IHeadlessBackend and ADDON-PROTOCOL, the host's addon
   protocol, passed in so this contract stays a dependency-free leaf."
  [addon-protocol x]
  (and (satisfies? addon-protocol x)
       (satisfies? IHeadlessBackend x)))

(defn capabilities
  "BACKEND's declared capabilities, or `default-capabilities` when it does
   not satisfy IHeadlessCapabilities."
  [backend]
  (if (satisfies? IHeadlessCapabilities backend)
    (declared-capabilities backend)
    default-capabilities))
