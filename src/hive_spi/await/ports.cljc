(ns hive-spi.await.ports
  "The await probe contract: what an addon implements so a host's poll
   engine can wait on something the host itself knows nothing about.

   The contract is SINGLE-SHOT. A source parses a subscription, probes once
   and judges one observation. Cadence, deadline, dedup, redelivery, error
   counting, the scheduler and the poller cap are the HOST's engine, never
   the source's. So a source has no thread, no timer and no loop.

     IProbeSource    descriptor, parse-spec, probe!, judge
     ISecretScheme   a <scheme>:<id> secret-ref resolver, bounded by itself
     IPollScheduler  the repeating-schedule port the host's engine runs on

   The clock is hive-spi.time.ports/IClock; no second clock port is declared
   here.

   The rules every source applies in the same shape (closed specs, refusals,
   comparators, allowlists) are pure fns in hive-spi.await.spec, and
   hive-spi.await.conformance is the executable form of this contract.

   Reload-safety: each defprotocol is guarded, so re-evaluating this
   namespace does not mint a fresh interface that existing records then fail
   to satisfy."
  (:require [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; =============================================================================
;; Protocols
;; =============================================================================

(defonce ^:private -iprobesource-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -iprobesource-defined? false true))
  (defprotocol IProbeSource
    "One kind of thing an awaiter can wait on, probed once per call.

     Every method but probe! is pure. A refusal is an ex-info whose data
     carries :error :await/invalid-subscription or :await/not-allowed (see
     hive-spi.await.spec/refusal); it names keys, never values."

    (descriptor [this]
      "The SourceDescriptor: :type (the subscription :type this source
       answers), :spec-shape (the closed key tree parse-spec accepts),
       :parsed-schema (a CLOSED malli schema of what parse-spec answers) and
       :timing.")

    (parse-spec [this subscription policy]
      "The parsed spec of SUBSCRIPTION (a map holding :spec), or a refusal.
       POLICY is the operator's allowlist map for this source type, or nil,
       and nil denies. The answer is what a host persists. It must satisfy
       :parsed-schema and parse to itself again (idempotent), so a host can
       store only parsed specs and re-parse them at boot.")

    (probe! [this parsed ctx]
      "ONE bounded read at the I/O boundary for PARSED, under CTX (a
       ProbeCtx). Answers a plain-data Observation map, or throws ex-info
       with an ErrorKeyword under :error. A resolved secret is never put in
       the observation, the message or the data.")

    (judge [this parsed observation]
      "The Verdict for OBSERVATION: {:match? bool :data map?}. Pure.")))

(defonce ^:private -isecretscheme-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -isecretscheme-defined? false true))
  (defprotocol ISecretScheme
    "The resolver of one secret-ref scheme. A ref is the string <scheme>:<id>.
     It NAMES a secret and is never the secret itself."

    (scheme [this]
      "The scheme name: lower case, [a-z][a-z0-9-]{0,15}.")

    (valid-id? [this id]
      "True when ID is a well-formed id of this scheme. Pure; never looks
       the secret up.")

    (resolve! [this id opts]
      "The secret value under ID, as a non-blank string. OPTS holds
       {:timeout-ms n}. The scheme bounds the lookup ITSELF and kills
       whatever it started; the caller only abandons it. Throws ex-info
       {:error :await/secret-missing} or {:error :await/secret-timeout},
       naming the id and never the value.")))

(defonce ^:private -ipollscheduler-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -ipollscheduler-defined? false true))
  (defprotocol IPollScheduler
    "The repeating schedule a host's poll engine runs probes on."

    (schedule-repeating! [this f interval-ms]
      "Run the zero-arg F now and then every INTERVAL-MS until cancelled.
       Answers a zero-arg cancel fn. F never throws (the engine catches).")

    (shutdown! [this]
      "Stop every schedule and release the threads. Answers nil. Scheduling
       after shutdown! throws.")))

;; =============================================================================
;; Schemas
;; =============================================================================

(def ErrorKeyword
  "The :error keywords the await contract speaks."
  [:enum
   :await/invalid-subscription :await/not-allowed
   :await/source-missing :await/scheme-missing
   :await/secret-missing :await/secret-timeout
   :await/poll-failed :await/poll-timeout :await/regex-timeout
   :await/invalid-provider :await/unknown-port
   :await/scheduler-shutdown])

(def Shape
  "The closed key tree of a spec. A set names leaf keys. A map names each
   key with the Shape of its value, or nil for a leaf. A map value under a
   leaf, or under a key the shape does not name, is unknown."
  ;; A predicate, not a recursive [:ref]: the typed-rung projection walks a
  ;; schema's form and would follow a self reference without end.
  [:fn {:error/message "must be a set of keywords, or a map of keyword to Shape or nil"}
   (fn shape? [x]
     (cond
       (set? x) (every? keyword? x)
       (map? x) (every? (fn [[k v]] (and (keyword? k) (or (nil? v) (shape? v)))) x)
       :else false))])

(def Timing
  "How often a source may be probed, and how long one probe may take."
  [:map
   [:min-poll-ms pos-int?]
   [:default-poll-ms pos-int?]
   [:probe-timeout-ms pos-int?]])

(def SourceDescriptor
  "What a source declares about itself."
  [:map
   [:type :keyword]
   [:spec-shape Shape]
   [:parsed-schema :any]
   [:timing Timing]
   [:addon {:optional true} :string]
   [:doc {:optional true} :string]])

(def Subscription
  "What an awaiter subscribes with: its :type, the source's :spec and an
   optional :poll-ms."
  [:map
   [:type {:optional true} :keyword]
   [:spec {:optional true} [:maybe :map]]
   [:poll-ms {:optional true} :any]])

(def ProbeCtx
  "What the host hands one probe!. :resolve-secret is a fn of a ref string
   answering its value, already bounded by the host."
  [:map
   [:awaiter-id {:optional true} :any]
   [:created {:optional true} [:maybe :int]]
   [:now {:optional true} :int]
   [:poll {:optional true} :int]
   [:timeout-ms {:optional true} pos-int?]
   [:resolve-secret {:optional true} ifn?]])

(def Observation
  "What one probe! read: plain data, never a secret."
  :map)

(def Verdict
  "What judge answers."
  [:map
   [:match? :boolean]
   [:data {:optional true} [:maybe :map]]])

(def ProbeSource
  "Any IProbeSource implementation."
  [:fn {:error/message "must satisfy IProbeSource"} #(satisfies? IProbeSource %)])

(def SecretScheme
  "Any ISecretScheme implementation."
  [:fn {:error/message "must satisfy ISecretScheme"} #(satisfies? ISecretScheme %)])

(def PollScheduler
  "Any IPollScheduler implementation."
  [:fn {:error/message "must satisfy IPollScheduler"} #(satisfies? IPollScheduler %)])

(def SchemeName
  "A secret-ref scheme name."
  [:re #"^[a-z][a-z0-9-]{0,15}$"])

(defn valid-descriptor?
  "True when D is a SourceDescriptor. Pure."
  [d]
  (m/validate SourceDescriptor d))

(m/=> valid-descriptor? [:=> [:cat :any] :boolean])
(tc/ann-op valid-descriptor?)
