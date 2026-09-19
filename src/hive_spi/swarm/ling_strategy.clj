(ns hive-spi.swarm.ling-strategy
  "ILingStrategy: mode-specific spawn, dispatch, status, kill and interrupt
   for a ling. One implementation per spawn mode (terminal addon, headless
   backend); the spawn pipeline selects one by mode and calls through it.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded. Re-evaluating this namespace will not orphan existing
   implementations. Consumers must NOT re-defprotocol this name.")

;; SPDX-License-Identifier: MIT

(defonce ^:private -ilingstrategy-defined? (atom false))

(when (compare-and-set! -ilingstrategy-defined? false true)
  (defprotocol ILingStrategy
    "Strategy protocol for mode-specific ling operations."

    (strategy-spawn! [this ling-ctx opts]
      "Spawn a ling using this strategy's mechanism.")

    (strategy-dispatch! [this ling-ctx task-opts]
      "Dispatch a task to a running ling.")

    (strategy-status [this ling-ctx ds-status]
      "Get mode-specific liveness and status information.")

    (strategy-kill! [this ling-ctx]
      "Terminate the ling using this strategy's mechanism.")

    (strategy-interrupt! [this ling-ctx]
      "Interrupt the current query/task of a running ling.")))
