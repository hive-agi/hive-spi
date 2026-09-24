(ns hive-spi.memory.registry
  "The injection point for IMemoryStore implementations.

   A store installs itself under a key; consumers read the active store.
   The key :default names the active store `get-store` returns with no
   argument.

   Every store passes through the decorator chain
   (hive-spi.memory.decorate) on its way in, so what the registry holds is
   the decorated store. `add-decorator!` and `remove-decorator!` re-apply
   the chain to the stores already registered."
  (:require [hive-spi.memory.decorate :as decorate]
            [hive-spi.memory.ports :as ports]
            [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private store-slot
  (slot/multi-slot {:validate #(satisfies? ports/IMemoryStore %)}))

(defn register-store!
  "Install STORE under KEY, run through the decorator chain. Returns the
   store actually installed. Throws when STORE does not satisfy
   IMemoryStore, or when a decorator throws (nothing is installed then)."
  [key store]
  (slot/reg-put! store-slot key (decorate/apply-chain key store)))

(defn redecorate!
  "Re-run the decorator chain over every registered store. A key whose
   decoration throws keeps the store it had; the failures are thrown
   together, as one ex-info, after every other key is done."
  []
  (let [failures (reduce-kv (fn [acc k s]
                              (try
                                (slot/reg-put! store-slot k (decorate/apply-chain k s))
                                acc
                                (catch Exception e
                                  (assoc acc k (ex-message e)))))
                            {}
                            (slot/reg-snapshot store-slot))]
    (when (seq failures)
      (throw (ex-info "store decorators failed to apply" {:failures failures})))
    nil))

(defn add-decorator!
  "Add DECORATOR (see hive-spi.memory.decorate) and apply it to every store
   already registered. Returns nil."
  [decorator]
  (decorate/add! decorator)
  (redecorate!))

(defn remove-decorator!
  "Remove the decorator with ID and re-decorate every registered store
   without it. Returns nil."
  [id]
  (decorate/remove! id)
  (redecorate!))

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
  "Disconnect the :default store, then drop it from the registry. No-op when
   none is installed. Returns nil. Never deletes the store's data."
  []
  (when (store-set?)
    (try
      (ports/disconnect! (get-store :default))
      (catch Exception _)))
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
