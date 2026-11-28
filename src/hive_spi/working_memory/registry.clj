(ns hive-spi.working-memory.registry
  "The injection point for the working-memory seams.

   Three slots, one per seam, each a single active implementation installed
   at boot and read from anywhere:

     :store         IWorkingMemoryStore  \u2014 where entries live
     :consolidation IConsolidation       \u2014 the terminal-entry fold
     :activation    IActivation          \u2014 the spawn bundle

   A store is installed by whatever owns the backend (a JetStream KV addon,
   a datalevin adapter, a test double); consolidation and activation are
   optional \u2014 a host that has neither still gets a working store, and the
   readers answer nil rather than throw, so a caller degrades loudly instead
   of crashing at boot.

   `set-store!` is the one required install; `get-store` throws a naming
   ex-info when nothing is installed, because a caller reaching for a store
   it never installed has a wiring bug, and a nil there surfaces far from the
   cause."
  (:require [hive-spi.schema.typed-contract :as tc]
            [hive-spi.slot :as slot]
            [hive-spi.working-memory.ports :as ports]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defonce ^:private store-slot
  (slot/single-slot {:validate #(satisfies? ports/IWorkingMemoryStore %)}))

(defonce ^:private consolidation-slot
  (slot/single-slot {:validate #(satisfies? ports/IConsolidation %)}))

(defonce ^:private activation-slot
  (slot/single-slot {:validate #(satisfies? ports/IActivation %)}))

;; =============================================================================
;; Store
;; =============================================================================

(defn set-store!
  "Install STORE as the active working-memory store. Returns STORE. Throws
   when STORE does not satisfy IWorkingMemoryStore."
  [store]
  (slot/install! store-slot store))

(defn get-store
  "The active store. Throws ex-info {:error :wm/store-unavailable} when none
   is installed \u2014 a nil here surfaces as a NullPointerException far from the
   cause."
  []
  (or (slot/current store-slot)
      (throw (ports/refusal :wm/store-unavailable
                            "no working-memory store installed"
                            {:hint "Call set-store! at boot first."}))))

(defn store-set?
  "True iff a store is installed. Never throws."
  []
  (slot/present? store-slot))

(defn clear-store!
  "Remove the installed store. Returns nil."
  []
  (slot/clear! store-slot))

;; =============================================================================
;; Consolidation
;; =============================================================================

(defn set-consolidator!
  "Install CONSOLIDATOR as the active IConsolidation. Returns it. Throws when
   it does not satisfy the port."
  [consolidator]
  (slot/install! consolidation-slot consolidator))

(defn get-consolidator
  "The active consolidator, or nil when none is installed. Never throws \u2014
   consolidation is optional, so a host without one reads nil and degrades."
  []
  (slot/current consolidation-slot))

(defn consolidator-set?
  "True iff a consolidator is installed."
  []
  (slot/present? consolidation-slot))

(defn clear-consolidator!
  "Remove the installed consolidator. Returns nil."
  []
  (slot/clear! consolidation-slot))

;; =============================================================================
;; Activation
;; =============================================================================

(defn set-activator!
  "Install ACTIVATOR as the active IActivation. Returns it. Throws when it
   does not satisfy the port."
  [activator]
  (slot/install! activation-slot activator))

(defn get-activator
  "The active activator, or nil when none is installed. Never throws."
  []
  (slot/current activation-slot))

(defn activator-set?
  "True iff an activator is installed."
  []
  (slot/present? activation-slot))

(defn clear-activator!
  "Remove the installed activator. Returns nil."
  []
  (slot/clear! activation-slot))

;; =============================================================================
;; Whole-registry helpers
;; =============================================================================

(defn reset-registry!
  "Remove every installed seam. Test support. Returns nil."
  []
  (clear-store!)
  (clear-consolidator!)
  (clear-activator!)
  nil)

(defn registry-status
  "A read-only {:store bool :consolidation bool :activation bool} of what is
   installed."
  []
  {:store (store-set?)
   :consolidation (consolidator-set?)
   :activation (activator-set?)})

;; =============================================================================
;; Contracts
;; =============================================================================

(def Store
  "An IWorkingMemoryStore. Re-published for callers of this namespace."
  ports/WorkingMemoryStore)

(def Consolidator
  "An IConsolidation. Re-published for callers of this namespace."
  ports/Consolidator)

(def Activator
  "An IActivation. Re-published for callers of this namespace."
  ports/Activator)

(def RegistryStatus
  "What `registry-status` answers."
  [:map {:closed true}
   [:store :boolean]
   [:consolidation :boolean]
   [:activation :boolean]])

(m/=> set-store! [:=> [:cat Store] Store])
(tc/ann-op set-store!)
(m/=> get-store [:=> [:cat] Store])
(tc/ann-op get-store)
(m/=> store-set? [:=> [:cat] :boolean])
(tc/ann-op store-set?)
(m/=> clear-store! [:=> [:cat] :nil])
(tc/ann-op clear-store!)
(m/=> set-consolidator! [:=> [:cat Consolidator] Consolidator])
(tc/ann-op set-consolidator!)
(m/=> get-consolidator [:=> [:cat] [:maybe Consolidator]])
(tc/ann-op get-consolidator)
(m/=> consolidator-set? [:=> [:cat] :boolean])
(tc/ann-op consolidator-set?)
(m/=> clear-consolidator! [:=> [:cat] :nil])
(tc/ann-op clear-consolidator!)
(m/=> set-activator! [:=> [:cat Activator] Activator])
(tc/ann-op set-activator!)
(m/=> get-activator [:=> [:cat] [:maybe Activator]])
(tc/ann-op get-activator)
(m/=> activator-set? [:=> [:cat] :boolean])
(tc/ann-op activator-set?)
(m/=> clear-activator! [:=> [:cat] :nil])
(tc/ann-op clear-activator!)
(m/=> reset-registry! [:=> [:cat] :nil])
(tc/ann-op reset-registry!)
(m/=> registry-status [:=> [:cat] RegistryStatus])
(tc/ann-op registry-status)
