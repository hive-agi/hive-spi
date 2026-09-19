(ns hive-spi.swarm.spawn-store
  "ISpawnStore: the spawn-time ling registration port. Spawn orchestration
   registers, updates and removes ling rows and reads a ling's file claims
   through this port, never through a concrete swarm store.

   The slot starts empty; the host installs its store at boot. `get-store`
   answers nil until then.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded. Re-evaluating this namespace will not orphan existing
   implementations. Consumers must NOT re-defprotocol this name."
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -ispawnstore-defined? (atom false))

(when (compare-and-set! -ispawnstore-defined? false true)
  (defprotocol ISpawnStore
    "Ling registration at spawn time."
    (add-slave! [this slave-id attrs]
      "Register SLAVE-ID with ATTRS.")
    (remove-slave! [this slave-id]
      "Remove SLAVE-ID's row.")
    (update-slave! [this slave-id updates]
      "Merge UPDATES into SLAVE-ID's row.")
    (claims-for-slave [this slave-id]
      "Vector of file paths SLAVE-ID holds claims on.")))

(defonce ^:private store-slot
  (slot/single-slot {:validate #(satisfies? ISpawnStore %)}))

(defn set-store!
  "Install STORE as the spawn registration store. Returns STORE."
  [store]
  (slot/install! store-slot store))

(defn get-store
  "The installed spawn registration store, or nil."
  []
  (slot/current store-slot))

(defn clear-store!
  "Remove the installed store. Returns nil."
  []
  (slot/clear! store-slot))
