(ns hive-spi.slot
  "Validated holders for swappable port implementations.

   A slot is the injection point for an SPI port: implementations install
   themselves at runtime, consumers read whatever is installed. `single-slot`
   holds one active implementation; `multi-slot` holds a keyed map of them."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defprotocol ISlot
  "A validated holder for a single active implementation."
  (install! [this impl]
    "Validate IMPL, store it, return it. Throws when the slot was built with
     a :validate predicate that IMPL fails.")
  (current [this]
    "The installed implementation. When none is installed, applies the
     empty-policy: returns (on-empty) when configured, else nil.")
  (present? [this]
    "True iff an implementation is explicitly installed. An empty-policy
     value does NOT count as present.")
  (clear! [this]
    "Remove the installed implementation, running :teardown on it first when
     configured. Returns nil."))

(defprotocol IRegistry
  "A validated holder for a keyed map of implementations."
  (reg-put! [this k impl]
    "Validate IMPL, store it under K, return it.")
  (reg-merge! [this kvs]
    "Validate every value and merge the whole {k -> impl} map in one swap.
     Returns the merged keys.")
  (reg-get [this k]
    "The implementation under K. When absent, applies the missing-policy:
     returns (on-missing k snapshot) when configured, else nil.")
  (reg-remove! [this k]
    "Remove K. No-op when absent. Returns nil.")
  (reg-snapshot [this]
    "A read-only {k -> impl} snapshot.")
  (reg-clear! [this]
    "Remove every entry. Returns nil."))

(defrecord SingleSlot [state validate on-empty teardown]
  ISlot
  (install! [_ impl]
    (when validate (assert (validate impl)))
    (reset! state impl)
    impl)
  (current [_]
    (if-let [v @state] v (when on-empty (on-empty))))
  (present? [_] (some? @state))
  (clear! [_]
    (when-let [impl @state]
      (when teardown
        (try (teardown impl) (catch #?(:clj Exception :cljs :default :cljr Exception :lpy python/Exception :default :default) _ nil))))
    (reset! state nil)
    nil))

(defrecord MultiSlot [state validate on-missing]
  IRegistry
  (reg-put! [_ k impl]
    (when validate (assert (validate impl)))
    (swap! state assoc k impl)
    impl)
  (reg-merge! [_ kvs]
    (when validate (run! #(assert (validate %)) (vals kvs)))
    (swap! state merge kvs)
    (keys kvs))
  (reg-get [_ k]
    (if-let [v (get @state k)] v (when on-missing (on-missing k @state))))
  (reg-remove! [_ k] (swap! state dissoc k) nil)
  (reg-snapshot [_] @state)
  (reg-clear! [_] (reset! state {}) nil))

(defn single-slot
  "A SingleSlot from a config map:
     :validate  pred — implementations failing it are rejected on install!
     :on-empty  (fn []) — value `current` returns when nothing is installed
     :teardown  (fn [impl]) — run on the installed impl during clear!
     :initial   seed value (default nil)"
  [{:keys [validate on-empty teardown initial]}]
  (->SingleSlot (atom initial) validate on-empty teardown))

(defn multi-slot
  "A MultiSlot from a config map:
     :validate    pred — implementations failing it are rejected on reg-put!
     :on-missing  (fn [k snapshot]) — value `reg-get` returns when K is absent
     :initial     seed map (default {})"
  [{:keys [validate on-missing initial]}]
  (->MultiSlot (atom (or initial {})) validate on-missing))

(defn watch-slot!
  "Run (f old-impl new-impl) when SLOT's installed implementation changes."
  [slot key f]
  (add-watch (:state slot) key (fn [_ _ old new] (f old new)))
  slot)

(defn unwatch-slot!
  "Remove the watch registered under KEY. Safe when absent."
  [slot key]
  (remove-watch (:state slot) key)
  slot)

;; =============================================================================
;; Contracts
;; =============================================================================

(def ^:private Hook
  "A configured callback: absent, or anything invocable."
  [:maybe ifn?])

(def SingleSlotConfig
  "What `single-slot` reads. Nil is an empty config."
  [:maybe [:map
           [:validate {:optional true} Hook]
           [:on-empty {:optional true} Hook]
           [:teardown {:optional true} Hook]
           [:initial {:optional true} :any]]])

(def MultiSlotConfig
  "What `multi-slot` reads. Nil is an empty config; a nil :initial is {}."
  [:maybe [:map
           [:validate {:optional true} Hook]
           [:on-missing {:optional true} Hook]
           [:initial {:optional true} [:maybe :map]]]])

(defn- slot-shaped?
  "Structural, so a slot built before a reload of this namespace (an instance
   of the previous record class) still satisfies the contract: what the
   watch fns need is a map whose :state is an atom."
  [x]
  (and (map? x)
       #?(:clj  (instance? clojure.lang.IAtom (:state x))
          :cljs (instance? cljs.core/Atom (:state x)))))

(def Slot
  "A slot as the constructors build it: a record whose :state is an atom."
  [:fn {:error/message "a SingleSlot or MultiSlot"
        :gen/schema    [:enum :single :multi]
        :gen/fmap      (fn [k] (if (= k :single)
                                 (->SingleSlot (atom nil) nil nil nil)
                                 (->MultiSlot (atom {}) nil nil)))}
   slot-shaped?])

(m/=> single-slot [:=> [:cat SingleSlotConfig] Slot])
(m/=> multi-slot [:=> [:cat MultiSlotConfig] Slot])
(m/=> watch-slot! [:=> [:cat Slot :any ifn?] Slot])
(m/=> unwatch-slot! [:=> [:cat Slot :any] Slot])
