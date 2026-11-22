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
            [hive-spi.slot :as slot]
            [malli.core :as m]))

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

;; register-store! stays hand-written above: it runs the decorator chain, and
;; set-store! is routed through it so a :default install is decorated too.
(slot/defregistry store-slot
  {:noun "memory store" :key-name :store-key
   :hint "Call set-store! or register-store! :default first."
   :set-default-via register-store!
   :unregister unregister-store! :snapshot registered-stores :require get-store
   :set-default set-store! :default-present? store-set? :reset reset-registry!
   :doc {:require "The store under KEY, or the :default store when called with no argument.\n\n   Throws ex-info naming the available keys when the store is absent: a\n   caller reaching for a store it never registered has a wiring bug, and a\n   nil here surfaces as a NullPointerException far from the cause."}})

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

;;; ============================================================================
;;; Contracts
;;; ============================================================================

;; The store installed is the DECORATED one: a decorator may answer any
;; IMemoryStore, so the output is the port, not STORE itself.
(def MemoryStore
  "Any IMemoryStore implementation (decorated or bare): the port's schema,
   re-published under the name cov2/spi gave it."
  ports/MemoryStore)

(def Decorator
  "A store decorator, as hive-spi.memory.decorate/add! accepts it."
  decorate/Decorator)

(m/=> register-store! [:=> [:cat :any ports/MemoryStore] ports/MemoryStore])
(m/=> redecorate! [:=> [:cat] :nil])
(m/=> add-decorator! [:=> [:cat decorate/Decorator] :nil])
(m/=> remove-decorator! [:=> [:cat :any] :nil])
(m/=> unregister-store! [:=> [:cat :any] :nil])
(m/=> registered-stores [:=> [:cat] [:map-of :any ports/MemoryStore]])
(m/=> get-store [:function
                 [:=> [:cat] ports/MemoryStore]
                 [:=> [:cat :any] ports/MemoryStore]])
(m/=> set-store! [:=> [:cat ports/MemoryStore] ports/MemoryStore])
(m/=> store-set? [:=> [:cat] :boolean])
(m/=> reset-registry! [:=> [:cat] :nil])
(m/=> reset-active-store! [:=> [:cat] :nil])
;; These pass the store's own answer through unchecked. The port documents
;; :success? / :healthy? / :backend, but a store answering another map (the
;; registries test double does) reaches the caller as-is: state that, not
;; the documented ideal.
(m/=> connect-active-store! [:=> [:cat :any] [:maybe :map]])
(m/=> active-store-healthy? [:=> [:cat] [:maybe :map]])
(m/=> active-store-status [:=> [:cat] [:maybe :map]])
