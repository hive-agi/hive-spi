(ns hive-spi.guard.fn-contract-test
  "Contract facets for the guard leaf's public fns (cov2): each predicate is
   total and agrees with the definition it names, the seam-key helpers
   round-trip, and the constructors honour their m/=> contracts under
   malli instrumentation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-schemas.test :as hst]
            [hive-spi.guard.decision :as d]
            [hive-spi.guard.event :as e]
            [hive-spi.guard.ports :as p]
            [hive-spi.guard.rule :as r]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.instrument :as mi]))

;; SPDX-License-Identifier: MIT

;;; ---------------------------------------------------------------------------
;;; Trifectas — predicates agree with their definitions for every value
;;; ---------------------------------------------------------------------------

(hst/deftrifecta-from-schema event-phase?-contract
  hive-spi.guard.event/phase?
  {:in        [:or (into [:enum] (sort e/phase-keys)) :any]
   :out       :boolean
   :rel       (fn [x out] (= out (contains? e/phase-keys x)))
   :num-tests 100})

(hst/deftrifecta-from-schema event-tool-phase?-contract
  hive-spi.guard.event/tool-phase?
  {:in        [:or e/Phase :any]
   :out       :boolean
   :rel       (fn [x out] (= out (contains? e/tool-phase-keys x)))
   :num-tests 100})

(hst/deftrifecta-from-schema event-valid?-contract
  hive-spi.guard.event/valid?
  {:in        [:or e/GuardEvent :any]
   :out       :boolean
   :rel       (fn [x out] (= out (m/validate e/GuardEvent x)))
   :num-tests 100})

(hst/deftrifecta-from-schema decision-valid?-contract
  hive-spi.guard.decision/valid?
  {:in        [:or d/GuardDecision :any]
   :out       :boolean
   :rel       (fn [x out] (= out (m/validate d/GuardDecision x)))
   :num-tests 100})

(hst/deftrifecta-from-schema decision-rank-contract
  hive-spi.guard.decision/rank
  {:in        [:maybe d/GuardDecision]
   :out       [:int {:min 0 :max 3}]
   :rel       (fn [x out] (= out (get d/verdict-rank (:guard/verdict x) 0)))
   :num-tests 100})

(hst/deftrifecta-from-schema rule-valid?-contract
  hive-spi.guard.rule/valid?
  {:in        [:or r/GuardRule :any]
   :out       :boolean
   :rel       (fn [x out] (= out (m/validate r/GuardRule x)))
   :num-tests 50})

(hst/deftrifecta-from-schema projection-ext-key?-contract
  hive-spi.guard.ports/projection-ext-key?
  {:in        [:or :qualified-keyword :keyword :any]
   :out       :boolean
   :rel       (fn [x out] (= out (and (keyword? x)
                                      (= p/projection-ext-ns (namespace x)))))
   :num-tests 100})

(hst/deftrifecta-from-schema harness-of-contract
  hive-spi.guard.ports/harness-of
  {:in        [:or :qualified-keyword :any]
   :out       [:maybe simple-keyword?]
   :rel       (fn [x out] (= (some? out) (p/projection-ext-key? x)))
   :num-tests 100})

(defspec projection-key-round-trips 100
  (prop/for-all [h (gen/fmap keyword (gen/not-empty gen/string-alphanumeric))]
                (let [k (p/projection-ext-key h)]
                  (and (qualified-keyword? k)
                       (p/projection-ext-key? k)
                       (= h (p/harness-of k))))))

;;; ---------------------------------------------------------------------------
;;; Instrumented — the constructors and the fold honour their m/=> contracts
;;; ---------------------------------------------------------------------------

(def ^:private guard-nses
  '#{hive-spi.guard.decision hive-spi.guard.event
     hive-spi.guard.rule hive-spi.guard.ports})

(defn- with-instrumented [f]
  (mi/instrument! {:filters [(mi/-filter-ns 'hive-spi.guard.decision
                                            'hive-spi.guard.event
                                            'hive-spi.guard.rule
                                            'hive-spi.guard.ports)]})
  (try (f) (finally (mi/unstrument! {:filters [(mi/-filter-ns 'hive-spi.guard.decision
                                                               'hive-spi.guard.event
                                                               'hive-spi.guard.rule
                                                               'hive-spi.guard.ports)]}))))

(deftest every-public-guard-fn-has-a-contract
  (doseq [ns-sym guard-nses
          :let [_ (require ns-sym)
                fns (->> (ns-publics ns-sym) vals
                         (filter #(fn? @%))
                         (remove #(:macro (meta %)))
                         ;; protocol methods are the port's surface, contracted
                         ;; by their docstrings, not by m/=>
                         (remove #(:protocol (meta %)))
                         (map #(-> % meta :name)))
                contracted (set (keys (get (m/function-schemas) ns-sym)))]
          f fns]
    (is (contains? contracted f) (str ns-sym "/" f " has no m/=> contract"))))

(deftest constructors-conform-under-instrumentation
  (with-instrumented
    (fn []
      (testing "every constructor returns a conformant decision"
        (is (d/valid? (d/allow)))
        (is (= :allow (:guard/verdict (d/allow {:guard/rule-id :x}))))
        (is (d/valid? (d/warn "w")))
        (is (d/valid? (d/ask "a" {:guard/rule-id :r})))
        (is (d/valid? (d/deny "x" {:guard/citations ["20260704183422-5333b2fb"]}))))
      (testing "a blank reason is refused at the contract boundary"
        (doseq [ctor [d/warn d/ask d/deny]]
          (is (thrown? Exception (ctor "")))))
      (testing "the fold and the predicates stay total"
        (is (= :allow (:guard/verdict (d/strongest nil))))
        (is (= :deny (:guard/verdict (d/strongest [nil (d/warn "w") (d/deny "d")]))))
        (is (false? (d/asks? nil)))
        (is (true? (d/blocking? (d/ask "a"))))
        (is (false? (d/denied? {}))))
      (testing "guard-event and guard-rule return conformant values or throw"
        (is (e/valid? (e/guard-event {:guard/phase :stop :guard/harness :eca})))
        (is (thrown? clojure.lang.ExceptionInfo (e/guard-event {})))
        (let [rule (r/guard-rule {:rule/id :x :rule/phases #{:stop} :rule/match {}
                                  :rule/verdict :warn :rule/reason "r"})]
          (is (r/valid? rule))
          (is (true? (r/enabled? rule)))
          (is (true? (r/applies-to-phase? rule :stop)))
          (is (true? (r/applies-to-harness? rule :eca)))
          (is (true? (r/candidate? rule {:guard/phase :stop :guard/harness :eca}))))
        (is (thrown? clojure.lang.ExceptionInfo (r/guard-rule {:rule/id :x}))))
      (testing "seam keys"
        (is (= :guard-projection/eca (p/projection-ext-key :eca)))
        (is (= :guard-projection/eca (p/projection-ext-key "eca")))
        (is (nil? (p/harness-of :guard/decide)))))))

(defspec generated-decisions-fold-conformantly-under-instrumentation 50
  (prop/for-all [ds (gen/vector (mg/generator d/GuardDecision) 0 5)]
                (with-instrumented #(d/valid? (d/strongest ds)))))

(defspec generated-rules-are-candidates-for-their-own-phases 50
  (prop/for-all [rule (mg/generator r/GuardRule)
                 h    (mg/generator :keyword)]
                (let [rule (assoc rule :rule/enabled? true :rule/harnesses nil)]
                  (every? #(r/candidate? rule {:guard/phase % :guard/harness h})
                          (:rule/phases rule)))))
