(ns hive-spi.slot
  "Validated holders for swappable port implementations.

   A slot is the injection point for an SPI port: implementations install
   themselves at runtime, consumers read whatever is installed. `single-slot`
   holds one active implementation; `multi-slot` holds a keyed map of them.

   Every port namespace exposes the same accessors over its slot (set, get,
   clear, present?) and every keyed registry the same required lookup.
   `defslot` emits the accessors from one spec and `reg-require` is the
   lookup, so a port declares WHAT it holds and never re-types HOW."
  (:require [clojure.string :as str]
            [malli.core :as m])
  #?(:cljs (:require-macros [hive-spi.slot])))

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
  (clear! [this]
    (when-let [impl @state]
      (when teardown
        (try (teardown impl)
             (catch #?(:clj Exception :cljs :default :cljr Exception :lpy python/Exception :default :default) e
               ;; The slot is the bottom stratum (the log port is built on
               ;; it), so it cannot log; it hands the failure to the
               ;; configured :on-teardown-error seam instead of dropping it.
               (when-let [report (:on-teardown-error this)]
                 (report impl e))))))
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
     :validate           pred — implementations failing it are rejected on install!
     :on-empty           (fn []) — value `current` returns when nothing is installed
     :teardown           (fn [impl]) — run on the installed impl during clear!
     :on-teardown-error  (fn [impl throwable]) — told when :teardown throws;
                         clear! still empties the slot and returns nil
     :initial            seed value (default nil)"
  [{:keys [validate on-empty teardown on-teardown-error initial]}]
  (cond-> (->SingleSlot (atom initial) validate on-empty teardown)
    on-teardown-error (assoc :on-teardown-error on-teardown-error)))

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
;; Accessors: one spec per port instead of one hand-typed defn per verb
;; =============================================================================
;;
;; Every port namespace wrapped its slot in the same four (or eight) one-line
;; defns. `defslot` / `defregistry` emit them from a spec naming each accessor,
;; so the PUBLIC NAMES and ARITIES stay whatever the port already published;
;; only the bodies are shared. A role absent from the spec is not emitted, so a
;; port with a bespoke verb (a decorated register, a custom key) keeps writing
;; that one by hand.

(def ^:private fallback-phrase
  "How a port describes what consumers see when nothing is installed."
  {:host-default "the host default"
   :noop         "the noop"})

(defn- falls-back-to
  "The fallback phrase for SPEC, or nil when the port has none."
  [spec]
  (get fallback-phrase (:fallback spec)))

(def ^:private role-docs
  "role -> (fn [noun fallback-phrase-or-nil] docstring). The table is the
   whole wording policy: a new role is a new row, never a new branch."
  {:set              (fn [n _] (str "Install IMPL as the active " n ". Returns IMPL. Throws when IMPL\n   fails the slot's :validate predicate."))
   :get              (fn [n fb] (if fb
                                  (str "The active " n ": the installed one, else " fb ".")
                                  (str "The active " n ", or nil when none is installed.")))
   :clear            (fn [n fb] (if fb
                                  (str "Remove the installed " n ", so consumers fall back to " fb ".\n   Returns nil.")
                                  (str "Remove the active " n ". Returns nil.")))
   :present?         (fn [n fb] (if fb
                                  (str "True iff a " n " is explicitly installed. " (str/capitalize fb) "\n   does not count.")
                                  (str "True iff a " n " is installed.")))
   :register         (fn [n _] (str "Install IMPL (a " n ") under KEY. Returns IMPL. Throws when IMPL\n   fails the registry's :validate predicate."))
   :unregister       (fn [n _] (str "Remove the " n " under KEY. No-op when absent. Returns nil."))
   :snapshot         (fn [n _] (str "A read-only {key -> " n "} snapshot."))
   :lookup           (fn [n _] (str "The " n " under KEY, or nil."))
   :require          (fn [n _] (str "The " n " under KEY, or the :default " n " when called with no\n   argument. Throws ex-info naming the available keys when it is absent: a\n   caller reaching for one it never registered has a wiring bug."))
   :set-default      (fn [n _] (str "Install IMPL as the :default " n ". Returns IMPL."))
   :default-present? (fn [n _] (str "True iff a :default " n " is installed. Never throws."))
   :reset            (fn [n _] (str "Remove every registered " n ". Returns nil."))})

(defn accessor-doc
  "The docstring `defslot` / `defregistry` give the accessor playing ROLE
   for the port SPEC describes ({:noun str :fallback kw}). An explicit
   (get-in spec [:doc role]) wins over the generated text."
  [role spec]
  (or (get-in spec [:doc role])
      ((get role-docs role) (:noun spec) (falls-back-to spec))))

(defn missing-entry
  "Why a required registry lookup failed, as {:message str :data map}.
   REQUESTED is the key asked for, or ::default when the caller asked for the
   default entry without naming a key; AVAILABLE is the keys present. SPEC
   names the entry kind (:noun), the ex-data key the requested key is
   reported under (:key-name) and the operator hint for a missing default."
  [{:keys [noun key-name hint]} requested available]
  (if (= ::default requested)
    {:message (str "No default " noun " registered.")
     :data    {:registry-keys (vec available) :hint hint}}
    {:message (str "Unknown " noun " key: " requested)
     :data    {key-name requested :available (vec available)}}))

(defn reg-require
  "The implementation REGISTRY holds under K (the :default entry when K is
   omitted). Throws the `missing-entry` ex-info when it is absent: a nil here
   would surface as a NullPointerException far from the wiring bug."
  ([registry spec] (reg-require registry spec ::default))
  ([registry spec k]
   (or (reg-get registry (if (= ::default k) :default k))
       (let [{:keys [message data]} (missing-entry spec k (keys (reg-snapshot registry)))]
         (throw (ex-info message data))))))

(def ^:private single-roles
  "Accessor role -> (fn [slot spec] arity-forms) for a SingleSlot, in
   emission order."
  [[:set      (fn [s _] [`([~'impl] (install! ~s ~'impl))])]
   [:get      (fn [s _] [`([] (current ~s))])]
   [:clear    (fn [s _] [`([] (clear! ~s))])]
   [:present? (fn [s _] [`([] (present? ~s))])]])

(def ^:private registry-roles
  "Accessor role -> (fn [slot spec] arity-forms) for a MultiSlot, in
   emission order. :set-default installs through :set-default-via (a
   hand-written register with extra behaviour, not emitted) or else the
   spec's emitted :register, so there is one install path either way."
  [[:register         (fn [s _] [`([~'key ~'impl] (reg-put! ~s ~'key ~'impl))])]
   [:unregister       (fn [s _] [`([~'key] (reg-remove! ~s ~'key))])]
   [:snapshot         (fn [s _] [`([] (reg-snapshot ~s))])]
   [:lookup           (fn [s _] [`([~'key] (reg-get ~s ~'key))])]
   [:require          (fn [s spec]
                        (let [req (select-keys spec [:noun :key-name :hint])]
                          [`([] (reg-require ~s ~req))
                           `([~'key] (reg-require ~s ~req ~'key))]))]
   [:set-default      (fn [s spec]
                        [(if-let [register (or (:set-default-via spec) (:register spec))]
                           `([~'impl] (~register :default ~'impl))
                           `([~'impl] (reg-put! ~s :default ~'impl)))])]
   [:default-present? (fn [s _] [`([] (some? (reg-get ~s :default)))])]
   [:reset            (fn [s _] [`([] (reg-clear! ~s))])]])

(defn- emit-accessors
  "One defn per role SPEC names, in ROLES order, each closing over SLOT."
  [roles slot spec]
  `(do ~@(for [[role arities] roles
               :let [fname (get spec role)]
               :when fname]
           `(defn ~fname ~(accessor-doc role spec) ~@(arities slot spec)))))

(defmacro defslot
  "Define the accessors of the SingleSlot SLOT from SPEC, a literal map:
     :noun      what the slot holds, for the docstrings (\"clock\")
     :fallback  :host-default | :noop | absent — what `current` answers empty
     :set :get :clear :present?   the public name of each accessor to emit
     :doc       {role docstring} overrides
   Each named accessor is a plain defn with the arity its role fixes:
   (set impl) (get) (clear) (present?)."
  [slot spec]
  (emit-accessors single-roles slot spec))

(defmacro defregistry
  "Define the accessors of the MultiSlot SLOT from SPEC, a literal map:
     :noun      what the registry holds (\"cider port\")
     :key-name  the ex-data key a missing requested key is reported under
     :hint      operator hint when the default entry is missing
     :register :unregister :snapshot :lookup :require :set-default
     :default-present? :reset    the public name of each accessor to emit
     :set-default-via  an existing (fn [key impl]) the :set-default accessor
                installs through instead of the emitted :register (not emitted)
     :doc       {role docstring} overrides
   Arities: (register key impl) (unregister key) (snapshot) (lookup key)
   (require) (require key) (set-default impl) (default-present?) (reset)."
  [slot spec]
  (emit-accessors registry-roles slot spec))

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
           [:on-teardown-error {:optional true} Hook]
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

(def AccessorRole
  "A role `defslot` / `defregistry` can emit an accessor for."
  (into [:enum] (keys role-docs)))

(def PortSpec
  "The descriptive part of a `defslot` / `defregistry` spec (the accessor
   names are symbols the macros read at expansion time and need no schema)."
  [:map
   [:noun [:string {:min 1}]]
   [:fallback {:optional true} [:maybe (into [:enum] (keys fallback-phrase))]]
   [:key-name {:optional true} :keyword]
   [:hint {:optional true} [:maybe :string]]
   [:doc {:optional true} [:maybe [:map-of AccessorRole :string]]]])

(def MissingEntry
  "What `missing-entry` answers: the ex-info message and data."
  [:map
   [:message [:string {:min 1}]]
   [:data [:or
           [:map [:registry-keys [:vector :any]] [:hint [:maybe :string]]]
           [:map [:available [:vector :any]]]]]])

(m/=> accessor-doc [:=> [:cat AccessorRole PortSpec] :string])
(m/=> missing-entry [:=> [:cat PortSpec :any [:maybe [:sequential :any]]] MissingEntry])
(m/=> reg-require [:function
                   [:=> [:cat Slot PortSpec] :some]
                   [:=> [:cat Slot PortSpec :any] :some]])
