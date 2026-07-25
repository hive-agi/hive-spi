(ns hive-spi.memory.registry
  "The injection point for IMemoryStore implementations.

   A store installs itself under a key; consumers read the active store.
   The key :default names the active store `get-store` returns with no
   argument."
  (:require [hive-spi.memory.ports :as ports]
            [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private store-slot
  (slot/multi-slot {:validate #(satisfies? ports/IMemoryStore %)}))

(defn register-store!
  "Install STORE under KEY. Returns STORE. Throws when STORE does not
   satisfy IMemoryStore."
  [key store]
  (slot/reg-put! store-slot key store))

(defn unregister-store!
  "Remove the store under KEY. No-op when absent. Returns nil."
  [key]
  (slot/reg-remove! store-slot key))

(defn registered-stores
  "A read-only {key -> store} snapshot."
  []
  (slot/reg-snapshot store-slot))

(defn get-store
  "The store under KEY, or the :default store when called with no argument.
   Nil when absent."
  ([] (get-store :default))
  ([key] (slot/reg-get store-slot key)))

(defn set-store!
  "Install STORE as the :default store. Returns STORE."
  [store]
  (register-store! :default store))

(defn store-set?
  "True iff a :default store is installed."
  []
  (some? (get-store :default)))

(defn reset-registry!
  "Remove every registered store. Returns nil."
  []
  (slot/reg-clear! store-slot))

(defn reset-active-store!
  "Reset the :default store's own state via `ports/reset-store!`, then drop
   it from the registry. Returns nil."
  []
  (when-let [store (get-store :default)]
    (ports/reset-store! store))
  (unregister-store! :default))

(defn connect-active-store!
  "Connect the :default store with CONFIG."
  [config]
  (ports/connect! (get-store) config))

(defn active-store-healthy?
  "The :default store's health check, or nil when no store is installed."
  []
  (when (store-set?)
    (ports/health-check (get-store))))

(defn active-store-status
  "The :default store's status, or nil when no store is installed."
  []
  (when (store-set?)
    (ports/store-status (get-store))))
