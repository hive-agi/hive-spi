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

   Throws ex-info naming the available keys when the store is absent — a
   caller reaching for a store it never registered has a wiring bug, and a
   nil here surfaces as a NullPointerException far from the cause."
  ([]
   (or (slot/reg-get store-slot :default)
       (throw (ex-info "No default memory store registered."
                       {:registry-keys (vec (keys (registered-stores)))
                        :hint "Call set-store! or register-store! :default first."}))))
  ([key]
   (or (slot/reg-get store-slot key)
       (throw (ex-info (str "Unknown memory store key: " key)
                       {:store-key key
                        :available (vec (keys (registered-stores)))})))))

(defn set-store!
  "Install STORE as the :default store. Returns STORE."
  [store]
  (register-store! :default store))

(defn store-set?
  "True iff a :default store is installed. Never throws."
  []
  (some? (slot/reg-get store-slot :default)))

(defn reset-registry!
  "Remove every registered store. Returns nil."
  []
  (slot/reg-clear! store-slot))

(defn reset-active-store!
  "Reset the :default store's own state via `ports/reset-store!`, then drop
   it from the registry. No-op when none is installed. Returns nil."
  []
  (when (store-set?)
    (ports/reset-store! (get-store :default)))
  (unregister-store! :default))

(defn connect-active-store!
  "Connect the :default store with CONFIG."
  [config]
  (ports/connect! (get-store) config))

(defn active-store-healthy?
  "The :default store's health check, or nil when no store is installed."
  []
  (when (store-set?)
    (ports/health-check (get-store :default))))

(defn active-store-status
  "The :default store's status, or nil when no store is installed."
  []
  (when (store-set?)
    (ports/store-status (get-store :default))))
