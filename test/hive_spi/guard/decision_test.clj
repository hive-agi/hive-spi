;; MIT License - Copyright (c) 2026 Pedro Gomes Branquinho (BuddhiLW)

(ns hive-spi.guard.decision-test
  "GuardDecision constructors and the severity join over schema-generated
   decisions. The algebraic laws of `strongest` are in
   hive-spi.guard.contract-test; here each fn is driven by its contract."
  (:require [clojure.test :refer [deftest is]]
            [hive-schemas.test :as hst]
            [hive-spi.guard.decision :as d]
            [hive-test.golden :refer [deftest-golden]]))

(def ^:private Reason [:string {:min 1 :max 12}])

(hst/deftrifecta-from-schema deny-carries-its-reason
  hive-spi.guard.decision/deny
  {:in  [:cat Reason]
   :out d/GuardDecision
   :rel (fn [[reason] out]
          (and (d/denied? out) (= reason (:guard/reason out))))})

(hst/deftrifecta-from-schema warn-carries-its-reason
  hive-spi.guard.decision/warn
  {:in  [:cat Reason]
   :out d/GuardDecision
   :rel (fn [[reason] out]
          (and (= :warn (:guard/verdict out)) (= reason (:guard/reason out))))})

(hst/deftrifecta-from-schema rank-follows-verdict-order
  hive-spi.guard.decision/rank
  {:in  [:cat d/GuardDecision]
   :out [:int {:min 0 :max 2}]
   :rel (fn [[dec] out]
          (= out (.indexOf ^java.util.List d/verdict-order (:guard/verdict dec))))})

(hst/deftrifecta-from-schema strongest-picks-the-maximum-rank
  hive-spi.guard.decision/strongest
  {:in  [:cat [:vector {:max 5} d/GuardDecision]]
   :out d/GuardDecision
   :rel (fn [[ds] out]
          (= (d/rank out) (reduce max 0 (map d/rank ds))))})

(deftest allow-merges-provenance
  (is (= {:guard/verdict :allow :guard/rule-id :r}
         (d/allow {:guard/rule-id :r}))))

(deftest-golden ranks-and-folds
  "test/golden/hive_spi/guard/decision.edn"
  (let [a (d/allow) w (d/warn "careful") x (d/deny "no")]
    {:ranks       (mapv d/rank [a w x {:guard/verdict :bogus} {}])
     :denied?     (mapv d/denied? [a w x nil])
     :fold-empty  (d/strongest [])
     :fold-mixed  (d/strongest [w nil a x (d/deny "later")])
     :fold-warns  (d/strongest [a (d/warn "first") (d/warn "second")])}))
