(ns hive-spi.await.stub
  "Faithful test doubles for the await ports, shipped in src so every host
   and addon test drives the same doubles, and held to the same conformance
   suite a real source runs (test/hive_spi/await/stub_conformance_test.clj).

     make-source     an IProbeSource of type :stub-probe whose probe! reads
                     an observation table instead of doing I/O
     make-scheme     an ISecretScheme (default name \"test\") over a map
     make-scheduler  an IPollScheduler that runs only when tick! is called

   The stub source's spec (CLOSED, nested included):
     {:target \"alpha\"                    ; allowlisted under policy :targets
      :ref \"test:alpha/secret\"?          ; a secret ref, resolved on probe
      :predicate {:op \">=\" :value 3}}    ; compared to the observed value"
  (:require [clojure.string :as str]
            [hive-spi.await.ports :as ports]
            [hive-spi.await.spec :as spec]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; =============================================================================
;; Source
;; =============================================================================

(def source-type :stub-probe)

(def spec-shape
  "The closed key tree of a :stub-probe spec."
  {:target nil :ref nil :predicate #{:op :value}})

(def Parsed
  "What the stub source's parse-spec answers."
  [:map {:closed true}
   [:target :string]
   [:ref {:optional true} :string]
   [:predicate [:map {:closed true}
                [:op (into [:enum] (sort (keys spec/comparators)))]
                [:value [:or :string number?]]]]])

(def timing
  {:min-poll-ms 10 :default-poll-ms 50 :probe-timeout-ms 1000})

(def descriptor-value
  {:type source-type
   :spec-shape spec-shape
   :parsed-schema Parsed
   :timing timing
   :addon "hive-spi.await.stub"
   :doc "Test double: probes an in-memory observation table."})

(defn- refuse [message]
  (throw (spec/refusal source-type message {})))

(def ^:private ref-re #"[a-z][a-z0-9-]{0,15}:[^\s:@]{1,256}")

(defn parse-stub-spec
  "The Parsed spec of SUBSCRIPTION under POLICY, or a refusal. Pure."
  [subscription policy]
  (let [s     (spec/closed-spec source-type subscription spec-shape)
        tgt   (:target s)
        ref   (:ref s)
        {:keys [op value]} (:predicate s)]
    (when-not (and (string? tgt) (re-matches #"[a-z0-9][-a-z0-9]{0,62}" tgt))
      (refuse ":target is required: a lower-case name"))
    (when (and (some? ref) (not (and (string? ref) (re-matches ref-re ref))))
      (refuse ":ref must be a secret ref <scheme>:<id>"))
    (when-not (spec/comparator-of op)
      (refuse (str ":predicate.op must be one of " (str/join " " (sort (keys spec/comparators))))))
    (when-not (or (string? value) (number? value))
      (refuse ":predicate.value must be a string or a number"))
    (spec/allowed! source-type policy :targets tgt "target")
    (cond-> {:target tgt :predicate {:op (str/trim (name op)) :value value}}
      ref (assoc :ref ref))))

(defrecord StubSource [observations probes]
  ports/IProbeSource
  (descriptor [_] descriptor-value)
  (parse-spec [_ subscription policy] (parse-stub-spec subscription policy))
  (probe! [_ parsed ctx]
    (swap! probes inc)
    (let [t (:target parsed)
          v (get @observations t ::absent)]
      (when-let [r (:ref parsed)]
        (let [resolve (or (:resolve-secret ctx)
                          (throw (ex-info "await stub-probe: no secret resolver in ctx"
                                          {:error :await/secret-missing :ref r})))]
          ;; resolved and dropped: a secret never reaches the observation
          (resolve r)))
      (cond
        (= v ::absent) (throw (ex-info (str "await stub-probe: no observation for " t)
                                       {:error :await/poll-failed :target t}))
        (= v ::timeout) (throw (ex-info "await stub-probe: probe timed out"
                                        {:error :await/poll-timeout :target t}))
        :else {:target t :value v})))
  (judge [_ parsed observation]
    (let [{:keys [op value]} (:predicate parsed)]
      {:match? (spec/compare-values op (:value observation) value)
       :data {:target (:target parsed) :value (:value observation)}})))

(defn make-source
  "A :stub-probe source over OBSERVATIONS, a {target value} map. A value of
   ::timeout makes probe! throw :await/poll-timeout; an absent target
   throws :await/poll-failed."
  ([] (make-source {}))
  ([observations]
   (->StubSource (atom observations) (atom 0))))

(defn set-observation!
  "Make SOURCE observe VALUE for TARGET from the next probe! on. Returns nil."
  [source target value]
  (swap! (:observations source) assoc target value)
  nil)

(defn probe-count
  "How many times probe! ran on SOURCE."
  [source]
  @(:probes source))

;; =============================================================================
;; Secret scheme
;; =============================================================================

(def ^:private id-re #"[a-z0-9][-a-z0-9/]{0,127}")

(defrecord StubScheme [name secrets resolves]
  ports/ISecretScheme
  (scheme [_] name)
  (valid-id? [_ id]
    (boolean (and (string? id) (re-matches id-re id) (not (str/includes? id "..")))))
  (resolve! [_ id _opts]
    (swap! resolves inc)
    (let [v (get secrets id)]
      (if (and (string? v) (not (str/blank? v)))
        v
        (throw (ex-info (str "await: secret " name ":" id " did not resolve")
                        {:error :await/secret-missing :ref (str name ":" id)}))))))

(defn make-scheme
  "An ISecretScheme named NAME (default \"test\") over SECRETS, an {id value}
   map."
  ([secrets] (make-scheme "test" secrets))
  ([name secrets] (->StubScheme name secrets (atom 0))))

;; =============================================================================
;; Scheduler
;; =============================================================================

(defrecord ManualScheduler [tasks shut?]
  ports/IPollScheduler
  (schedule-repeating! [_ f interval-ms]
    (when @shut?
      (throw (ex-info "await stub scheduler: shut down" {:error :await/scheduler-shutdown})))
    (let [id (gensym "task")]
      (swap! tasks assoc id {:f f :interval-ms interval-ms})
      (fn cancel [] (swap! tasks dissoc id) nil)))
  (shutdown! [_]
    (reset! shut? true)
    (reset! tasks {})
    nil))

(defn make-scheduler
  "An IPollScheduler that runs nothing by itself: each tick! runs every live
   schedule once, the first tick standing for \"now\"."
  []
  (->ManualScheduler (atom {}) (atom false)))

(defn tick!
  "Run every live schedule of SCHEDULER once. Answers how many ran."
  [scheduler]
  (let [fs (map :f (vals @(:tasks scheduler)))]
    (doseq [f fs] (f))
    (count fs)))

(defn live-schedules
  "How many schedules SCHEDULER holds."
  [scheduler]
  (count @(:tasks scheduler)))

;; =============================================================================
;; Contracts
;; =============================================================================

(def Source [:fn {:error/message "must be a StubSource"} #(instance? StubSource %)])
(def Scheduler [:fn {:error/message "must be a ManualScheduler"} #(instance? ManualScheduler %)])

(m/=> parse-stub-spec [:=> [:cat [:maybe :map] [:maybe :map]] Parsed])
(tc/ann-op parse-stub-spec)
(m/=> make-source [:function
                   [:=> [:cat] Source]
                   [:=> [:cat [:map-of :string :any]] Source]])
(tc/ann-op make-source)
(m/=> set-observation! [:=> [:cat Source :string :any] :nil])
(tc/ann-op set-observation!)
(m/=> probe-count [:=> [:cat Source] :int])
(tc/ann-op probe-count)
(m/=> make-scheme [:function
                   [:=> [:cat [:map-of :string :string]] ports/SecretScheme]
                   [:=> [:cat :string [:map-of :string :string]] ports/SecretScheme]])
(tc/ann-op make-scheme)
(m/=> make-scheduler [:=> [:cat] Scheduler])
(tc/ann-op make-scheduler)
(m/=> tick! [:=> [:cat Scheduler] :int])
(tc/ann-op tick!)
(m/=> live-schedules [:=> [:cat Scheduler] :int])
(tc/ann-op live-schedules)
