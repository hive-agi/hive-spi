(ns hive-spi.await.conformance
  "Conformance suite for hive-spi.await.ports/IProbeSource.

   The suite is data: `cases` is a vector of {:id :doc :run :requires-opt?},
   each :run a (fn [ctx]) that asserts with clojure.test/is. Two projections
   run it:

     (defconformance my-source make-source opts)
       emits one deftest per case, named my-source-<case-id>.

     (run-conformance make-source opts)
       runs every case inside the current clojure.test context and returns
       {:ran [...] :skipped {...}}.

   MAKE-SOURCE is a zero-arg fn answering a FRESH source whose boundary the
   test has arranged (a stub runner, a canned HTTP answer). OPTS:
     :subscription        a subscription that parses under :policy (required)
     :policy              the allowlist policy it parses under
     :ctx                 the ProbeCtx probe! is called with (default {})
     :match-subscription  a subscription whose probe + judge MATCHES
     :miss-subscription   a subscription whose probe + judge does not match
     :error-subscription  a subscription whose probe! throws
     :timeout-subscription a subscription whose probe! SLEEPS past the budget
     :timeout-ms          the host-enforced :timeout-ms the timeout case uses
     :malformed-subscription a subscription whose probe! answers a
                          non-observation (rejected by the Observation schema)
     :secret-canary       a secret value the arranged boundary resolves; it
                          must appear in no observation, verdict or error
     :denies-nil-policy?  false when the source has no allowlist (default true)
     :skip                {case-id \"reason\"}

   The nested-unknown-key case is the reason the suite exists: a spec is
   persisted by the host, so a key the source does not name must be refused
   at EVERY depth, or a password nested under a known key lands on disk."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [is testing]]
            [hive-spi.await.ports :as ports]
            [hive-spi.await.spec :as spec]
            [hive-spi.log.ports :as log]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def canary
  "The value the suite plants in refused specs; it must never come back."
  "s3cr3t-canary")

(def ^:private unknown-key :zz-unknown-key)

;;; ============================================================================
;;; Helpers
;;; ============================================================================

(defn- parse [src sub policy] (ports/parse-spec src sub policy))

(defn- thrown-data
  "The ex-data and message of what (f) throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e {:data (ex-data e) :message (ex-message e)})
       (catch Throwable e {:data nil :message (str (class e) ": " (ex-message e))})))

(defn- refusal-ok?
  "True when THROWN is a contract refusal that does not echo the canary."
  [thrown]
  (and (some? thrown)
       (contains? #{:await/invalid-subscription :await/not-allowed}
                  (get-in thrown [:data :error]))
       (not (str/includes? (pr-str thrown) canary))))

(defn nested-paths
  "Every key path in SHAPE whose value is itself a shape (a nested map),
   e.g. [[:predicate]]. Pure."
  [shape]
  (letfn [(walk [shape prefix]
            (let [sm (if (set? shape) {} shape)]
              (mapcat (fn [[k sub]]
                        (when sub
                          (let [p (conj prefix k)]
                            (cons p (walk sub p)))))
                      sm)))]
    (vec (walk shape []))))

(defn leaf-keys
  "Every key PATH in SHAPE whose value names a leaf, at any depth, sorted:
   [[:predicate :op] [:ref] [:target]]. A set names leaves at its own depth.
   Pure."
  [shape]
  (letfn [(walk [shape prefix]
            (mapcat (fn [[k sub]]
                      (let [p (conj prefix k)]
                        (if sub (walk sub p) [p])))
                    (if (set? shape) (zipmap shape (repeat nil)) shape)))]
    (vec (sort (walk shape [])))))

(defn- spec-map [sub] (spec/spec-of sub))

(defn- with-spec [sub spec'] (assoc sub :spec spec'))

(defn- bounded-probe!
  "probe! bounded the way the HOST enforces :timeout-ms: the call runs on a
   future and, when it has not answered within the budget, is abandoned and
   reported as {:error {:error :await/poll-timeout}}. A source that bounds
   its own I/O answers first. Answers {:ok observation} or {:error data}."
  [src parsed ctx]
  (let [ms  (long (or (:timeout-ms ctx) 1000))
        fut (future (try {:ok (ports/probe! src parsed ctx)}
                         (catch Throwable t
                           {:error (or (ex-data t) {:message (ex-message t)})})))
        r   (deref fut ms ::timeout)]
    (if (= r ::timeout)
      (do (future-cancel fut)
          {:error {:error :await/poll-timeout :timeout-ms ms}})
      r)))

;;; ============================================================================
;;; Cases
;;; ============================================================================

(def cases
  [{:id :descriptor
    :doc "the descriptor is a SourceDescriptor with a closed :parsed-schema"
    :run (fn [{:keys [make-source]}]
           (let [d (ports/descriptor (make-source))]
             (is (ports/valid-descriptor? d) (pr-str (m/explain ports/SourceDescriptor d)))
             (is (keyword? (:type d)))
             (is (<= (:min-poll-ms (:timing d)) (:default-poll-ms (:timing d)))
                 "the default poll interval is not under the minimum")
             (is (spec/closed-schema? (:parsed-schema d))
                 "every :map in :parsed-schema is {:closed true}")))}

   {:id :parse-valid
    :doc "a valid subscription parses to a value of :parsed-schema"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 p (parse src (:subscription opts) (:policy opts))]
             (is (m/validate (:parsed-schema (ports/descriptor src)) p)
                 (pr-str (m/explain (:parsed-schema (ports/descriptor src)) p)))))}

   {:id :parse-idempotent
    :doc "parsing a parsed spec answers it unchanged (a host re-parses stored rows)"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 sub (:subscription opts)
                 p   (parse src sub (:policy opts))]
             (is (= p (parse src (with-spec sub p) (:policy opts))))))}

   {:id :parse-accepts-json-keys
    :doc "string and snake_case keys parse like kebab keywords"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 sub (:subscription opts)
                 jsonish (letfn [(j [x] (cond
                                          (map? x) (into {} (map (fn [[k v]] [(str/replace (name k) "-" "_") (j v)])) x)
                                          (vector? x) (mapv j x)
                                          :else x))]
                           (j (spec-map sub)))]
             (is (= (parse src sub (:policy opts))
                    (parse src (with-spec sub jsonish) (:policy opts))))))}

   {:id :unknown-top-key-refused
    :doc "an unknown top-level spec key is refused, and its value never echoed"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 sub (:subscription opts)
                 bad (with-spec sub (assoc (spec-map sub) unknown-key canary))]
             (is (refusal-ok? (thrown-data #(parse src bad (:policy opts)))))))}

   {:id :unknown-nested-key-refused
    :doc "an unknown key inside a nested spec map is refused at every depth"
    :run (fn [{:keys [make-source opts]}]
           (let [src   (make-source)
                 shape (:spec-shape (ports/descriptor src))
                 sub   (:subscription opts)
                 s     (spec-map sub)
                 nested (filterv #(map? (get-in s %)) (nested-paths shape))]
             (is (seq nested)
                 (str "the sample subscription has no nested map to test: a nested "
                      "key cannot be planted, so the case would assert nothing. "
                      "Give :subscription a spec with a map under " (pr-str (nested-paths shape))
                      ", e.g. a :predicate {:op \">=\" :value 1}. shape " (pr-str shape)))
             (doseq [path nested]
               (testing (str "under " (str/join "." (map name path)))
                 (let [bad (with-spec sub (assoc-in s (conj path unknown-key) canary))]
                   (is (refusal-ok? (thrown-data #(parse src bad (:policy opts))))))))
             (testing "a map planted under a leaf key"
               (doseq [k (leaf-keys shape)]
                 (let [bad (with-spec sub (assoc-in s k {:password canary}))
                       r   (thrown-data #(parse src bad (:policy opts)))]
                   (is (refusal-ok? r) (str "leaf " k)))))
             (testing "a map inside a vector under a leaf key"
               (doseq [k (leaf-keys shape)]
                 (let [bad (with-spec sub (assoc-in s k [{:password canary}]))]
                   (is (refusal-ok? (thrown-data #(parse src bad (:policy opts)))) (str "leaf " k)))))
             (testing "a map inside a set under a leaf key"
               (doseq [k (leaf-keys shape)]
                 (let [bad (with-spec sub (assoc-in s k #{{:password canary}}))]
                   (is (refusal-ok? (thrown-data #(parse src bad (:policy opts)))) (str "leaf " k)))))))}

   {:id :parsed-holds-no-unknown-key
    :doc "what parse answers carries only keys the shape names, at every depth"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 p   (parse src (:subscription opts) (:policy opts))]
             (is (= [] (spec/unknown-keys (:spec-shape (ports/descriptor src)) p)))))}

   {:id :nil-policy-denies
    :requires-opt :denies-nil-policy?
    :doc "with no operator policy every subscription is refused (default deny)"
    :run (fn [{:keys [make-source opts]}]
           (when (:denies-nil-policy? opts)
             (is (refusal-ok? (thrown-data #(parse (make-source) (:subscription opts) nil))))))}

   {:id :probe-match
    :requires-opt :match-subscription
    :doc "probe! then judge matches for the matching subscription"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 p   (parse src (:match-subscription opts) (:policy opts))
                 o   (ports/probe! src p (or (:ctx opts) {}))
                 v   (ports/judge src p o)]
             (is (m/validate ports/Observation o))
             (is (m/validate ports/Verdict v) (pr-str v))
             (is (true? (:match? v)))))}

   {:id :probe-miss
    :requires-opt :miss-subscription
    :doc "probe! then judge does not match for the missing subscription"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 p   (parse src (:miss-subscription opts) (:policy opts))
                 o   (ports/probe! src p (or (:ctx opts) {}))
                 v   (ports/judge src p o)]
             (is (m/validate ports/Observation o) (pr-str (m/explain ports/Observation o)))
             (is (m/validate ports/Verdict v))
             (is (false? (:match? v)))))}

   {:id :probe-timeout
    :requires-opt :timeout-subscription
    :doc "a probe! that sleeps past :timeout-ms yields a host timeout verdict, not a hang"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 p   (parse src (:timeout-subscription opts) (:policy opts))
                 ctx (assoc (or (:ctx opts) {}) :timeout-ms (:timeout-ms opts 250))
                 r   (bounded-probe! src p ctx)]
             (is (contains? r :error)
                 "the host bound the call: probe! did not answer within :timeout-ms")
             (is (m/validate ports/ErrorKeyword (get-in r [:error :error]))
                 (pr-str r))
             (when-let [c (:secret-canary opts)]
               (is (not (str/includes? (pr-str r) c))))))}

   {:id :malformed-observation-rejected
    :requires-opt :malformed-subscription
    :doc "a probe! that answers a non-observation is rejected by the Observation schema"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 p   (parse src (:malformed-subscription opts) (:policy opts))
                 o   (ports/probe! src p (or (:ctx opts) {}))]
             (is (not (m/validate ports/Observation o))
                 (str "probe! answered a malformed observation: " (pr-str o)))))}

   {:id :judge-pure
    :requires-opt :match-subscription
    :doc "judge answers the same verdict for the same observation"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 p   (parse src (:match-subscription opts) (:policy opts))
                 o   (ports/probe! src p (or (:ctx opts) {}))]
             (is (= (ports/judge src p o) (ports/judge src p o)))))}

   {:id :probe-error-keyword
    :requires-opt :error-subscription
    :doc "a failing probe! throws ex-info with an ErrorKeyword, no secret"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 p   (parse src (:error-subscription opts) (:policy opts))
                 r   (thrown-data #(ports/probe! src p (or (:ctx opts) {})))]
             (is (some? r) "probe! threw")
             (is (m/validate ports/ErrorKeyword (get-in r [:data :error])) (pr-str r))
             (when-let [c (:secret-canary opts)]
               (is (not (str/includes? (pr-str r) c))))))}

   {:id :no-secret-in-results
    :requires-opt :secret-canary
    :doc "a resolved secret appears in no observation or verdict"
    :run (fn [{:keys [make-source opts]}]
           (let [src (make-source)
                 c   (:secret-canary opts)]
             (doseq [k [:match-subscription :miss-subscription]
                     :let [sub (get opts k)]
                     :when sub]
               (let [p (parse src sub (:policy opts))
                     o (ports/probe! src p (or (:ctx opts) {}))
                     v (ports/judge src p o)]
                 (is (not (str/includes? (pr-str [p o v]) c)) (str k))))))}])

(def case-ids
  "The case ids, in run order."
  (mapv :id cases))

;;; ============================================================================
;;; Runners
;;; ============================================================================

(defn- case-by-id [id]
  (or (some #(when (= id (:id %)) %) cases)
      (throw (ex-info (str "Unknown await conformance case: " id) {:id id :known case-ids}))))

(defn skip-reason
  "Why case C must not run under OPTS, or nil: an explicit (get-in opts
   [:skip id]) wins, then a :requires-opt key OPTS lacks. Pure."
  [{:keys [id requires-opt]} opts]
  (or (get-in opts [:skip id])
      (when (and requires-opt (not (contains? opts requires-opt)))
        (str "opts lack " requires-opt))))

(defn run-case
  "Run case ID against MAKE-SOURCE with OPTS inside the current clojure.test
   context. Answers :ran, or {:skipped id :reason ..}."
  [make-source opts id]
  (let [{:keys [run doc] :as c} (case-by-id id)
        opts (merge {:denies-nil-policy? true} opts)]
    (if-let [reason (skip-reason c opts)]
      (do (log/info "await conformance: skipping" id "-" reason)
          {:skipped id :reason reason})
      (do (testing (str (name id) ": " doc)
            (run {:make-source make-source :opts opts}))
          :ran))))

(defn run-conformance
  "Run every case against MAKE-SOURCE with OPTS. Answers
   {:ran [ids] :skipped {id reason}}."
  [make-source opts]
  (reduce (fn [acc id]
            (let [r (run-case make-source opts id)]
              (if (= :ran r)
                (update acc :ran conj id)
                (assoc-in acc [:skipped id] (:reason r)))))
          {:ran [] :skipped {}}
          case-ids))

(defmacro defconformance
  "Emit one clojure.test deftest per case, named PREFIX-<case-id>, each
   running that case against MAKE-SOURCE with OPTS."
  [prefix make-source opts]
  `(do ~@(for [id case-ids]
           `(t/deftest ~(symbol (str (name prefix) "-" (name id)))
              (run-case ~make-source ~opts ~id)))))

;;; ============================================================================
;;; Contracts
;;; ============================================================================

(def CaseId
  "An await conformance case id."
  (into [:enum] case-ids))

(def MakeSource
  "A zero-arg fn answering a fresh IProbeSource."
  [:fn {:error/message "must be a zero-arg fn"} ifn?])

(def RunOpts
  "The OPTS the runners read."
  [:map
   [:subscription :map]
   [:policy {:optional true} [:maybe :map]]
   [:ctx {:optional true} [:maybe :map]]
   [:match-subscription {:optional true} :map]
   [:miss-subscription {:optional true} :map]
   [:error-subscription {:optional true} :map]
   [:timeout-subscription {:optional true} :map]
   [:timeout-ms {:optional true} pos-int?]
   [:malformed-subscription {:optional true} :map]
   [:secret-canary {:optional true} :string]
   [:denies-nil-policy? {:optional true} :boolean]
   [:skip {:optional true} [:maybe [:map-of :keyword :string]]]])

(m/=> nested-paths [:=> [:cat ports/Shape] [:vector [:vector :keyword]]])
(tc/ann-op nested-paths)
(m/=> leaf-keys [:=> [:cat ports/Shape] [:vector [:vector :keyword]]])
(tc/ann-op leaf-keys)
(m/=> skip-reason [:=> [:cat [:map [:id CaseId] [:requires-opt {:optional true} :keyword]]
                           [:maybe :map]]
                   [:maybe :string]])
(tc/ann-op skip-reason)
(m/=> run-case [:=> [:cat MakeSource RunOpts CaseId]
                [:or [:= :ran] [:map [:skipped CaseId] [:reason :string]]]])
(tc/ann-op run-case)
(m/=> run-conformance [:=> [:cat MakeSource RunOpts]
                       [:map [:ran [:vector CaseId]] [:skipped [:map-of CaseId :string]]]])
(tc/ann-op run-conformance)
