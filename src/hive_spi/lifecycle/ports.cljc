(ns hive-spi.lifecycle.ports
  "Participation contracts for orchestrated shutdown and periodic maintenance.

   Shutdown priority bands, lowest first:
     0-99     external service stop
     100-199  subprocess kill
     200-299  client close
     300-399  store close
     400+     final bookkeeping

   Reload-safety: `defprotocol` is not idempotent, so each declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations.")

;; SPDX-License-Identifier: MIT

(defonce ^:private -ishutdownhook-defined? (atom false))

(when (compare-and-set! -ishutdownhook-defined? false true)
  (defprotocol IShutdownHook
    "Participation in ordered system shutdown. Registered implementations are
     invoked in ascending `shutdown-priority` order."

    (shutdown-priority [this]
      "An integer ordering priority. Lower runs earlier; see the band
       guidance in the namespace docstring.")

    (shutdown-name [this]
      "A human-readable identifier for this hook, used in logs and shutdown
       reports.")

    (shutdown! [this ctx]
      "Perform the stop action. CTX is {:reason kw :timeout-ms int}.
       Must be idempotent and must not throw on double-shutdown.")))

(defonce ^:private -isweepable-defined? (atom false))

(when (compare-and-set! -isweepable-defined? false true)
  (defprotocol ISweepable
    "Participation in periodic background maintenance. The orchestrator calls
     `sweep!` at each implementation's declared cadence."

    (sweep-interval-s [this]
      "Desired seconds between sweeps. Treated as a hint; scheduling may
       jitter to avoid a thundering herd.")

    (sweep-name [this]
      "A human-readable identifier for this sweeper, used in logs and
       metrics.")

    (sweep! [this ctx]
      "Perform one sweep pass. CTX is {:now-ms long}. Returns
       {:swept int :errors seq}. Must not throw — unexpected failures
       surface via :errors.")))

(defonce ^:private -iresourceowner-defined? (atom false))

(when (compare-and-set! -iresourceowner-defined? false true)
  (defprotocol IResourceOwner
    "Per-entity resource ownership. An implementation represents one logical
     entity and releases everything it owns when that entity is reaped."

    (owner-id [this]
      "The entity identifier this owner represents.")

    (owned-resources [this]
      "An inventory map of currently-owned resources, for observability.
       Must be cheap — no I/O.")

    (release-all! [this]
      "Release every owned resource. Must be idempotent and must not throw;
       partial failures are logged and swallowed so downstream release paths
       still run.")))

(defonce ^:private -ishutdownbudget-defined? (atom false))

(when (compare-and-set! -ishutdownbudget-defined? false true)
  (defprotocol IShutdownBudget
    "Optional per-hook wall-clock budget. A hook that does not extend this
     protocol runs under the shutdown sequence's default budget."

    (shutdown-timeout-ms [this]
      "Milliseconds this hook may run before the orchestrator abandons it.
       A non-numeric or non-positive value falls back to the default.")))
