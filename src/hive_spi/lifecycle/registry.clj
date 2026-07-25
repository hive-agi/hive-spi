(ns hive-spi.lifecycle.registry
  "The injection point for lifecycle participants.

   Shutdown hooks, sweepers and resource owners each register under a key;
   the orchestrator reads the registries when it runs a shutdown or a sweep
   tick."
  (:require [hive-spi.lifecycle.ports :as ports]
            [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private shutdown-slot
  (slot/multi-slot {:validate #(satisfies? ports/IShutdownHook %)}))

(defonce ^:private sweep-slot
  (slot/multi-slot {:validate #(satisfies? ports/ISweepable %)}))

(defonce ^:private owner-slot
  (slot/multi-slot {:validate #(satisfies? ports/IResourceOwner %)}))

(defn register-shutdown!
  "Install HOOK under KEY. Returns HOOK."
  [key hook]
  (slot/reg-put! shutdown-slot key hook))

(defn unregister-shutdown!
  "Remove the shutdown hook under KEY. Returns nil."
  [key]
  (slot/reg-remove! shutdown-slot key))

(defn registered-shutdown-hooks
  "Registered shutdown hooks in ascending `shutdown-priority` order."
  []
  (->> (slot/reg-snapshot shutdown-slot)
       vals
       (sort-by ports/shutdown-priority)
       vec))

(defn register-sweep!
  "Install SWEEPER under KEY. Returns SWEEPER."
  [key sweeper]
  (slot/reg-put! sweep-slot key sweeper))

(defn unregister-sweep!
  "Remove the sweeper under KEY. Returns nil."
  [key]
  (slot/reg-remove! sweep-slot key))

(defn registered-sweeps
  "A read-only {key -> sweeper} snapshot."
  []
  (slot/reg-snapshot sweep-slot))

(defn register-resource-owner!
  "Install OWNER under KEY. Returns OWNER."
  [key owner]
  (slot/reg-put! owner-slot key owner))

(defn unregister-resource-owner!
  "Remove the resource owner under KEY. Returns nil."
  [key]
  (slot/reg-remove! owner-slot key))

(defn get-resource-owner
  "The resource owner under KEY, or nil."
  [key]
  (slot/reg-get owner-slot key))

(defn registered-resource-owners
  "A read-only {key -> owner} snapshot."
  []
  (slot/reg-snapshot owner-slot))

(defn registry-snapshot
  "One snapshot of all three registries, keyed by kind."
  []
  {:shutdown (slot/reg-snapshot shutdown-slot)
   :sweeps   (slot/reg-snapshot sweep-slot)
   :owners   (slot/reg-snapshot owner-slot)})

(defn reset-all!
  "Clear all three registries. Returns nil."
  []
  (slot/reg-clear! shutdown-slot)
  (slot/reg-clear! sweep-slot)
  (slot/reg-clear! owner-slot)
  nil)

(defn restore-all!
  "Replace all three registries with SNAPSHOT, as produced by
   `registry-snapshot`. Returns nil."
  [{:keys [shutdown sweeps owners]}]
  (reset-all!)
  (when (seq shutdown) (slot/reg-merge! shutdown-slot shutdown))
  (when (seq sweeps) (slot/reg-merge! sweep-slot sweeps))
  (when (seq owners) (slot/reg-merge! owner-slot owners))
  nil)
