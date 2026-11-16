;; MIT License - Copyright (c) 2026 Pedro Gomes Branquinho (BuddhiLW)

(ns hive-spi.guard.rule-test
  "GuardRule constructor and the structural pre-filter, driven by the
   schemas' generators. Behavioural cases live in
   hive-spi.guard.contract-test."
  (:require [hive-schemas.test :as hst]
            [hive-spi.guard.event :as e]
            [hive-spi.guard.rule :as r]
            [hive-test.golden :refer [deftest-golden]]))

(hst/deftrifecta-from-schema guard-rule-is-identity-plus-defaults
  hive-spi.guard.rule/guard-rule
  {:in  [:cat r/GuardRule]
   :out r/GuardRule
   :rel (fn [[rule] out] (= (merge r/default-rule rule) out))})

(hst/deftrifecta-from-schema enabled?-is-false-only-when-disabled
  hive-spi.guard.rule/enabled?
  {:in  [:cat r/GuardRule]
   :out :boolean
   :rel (fn [[rule] out] (= out (not (false? (:rule/enabled? rule)))))})

(def ^:private Harness [:enum :claude-code :eca :mcp :hive-agent])

(hst/deftrifecta-from-schema applies-to-harness?-honours-the-narrowing
  hive-spi.guard.rule/applies-to-harness?
  {:in  [:cat [:map [:rule/harnesses [:maybe [:set Harness]]]] Harness]
   :out :boolean
   :rel (fn [[rule h] out]
          (= out (let [hs (:rule/harnesses rule)]
                   (or (empty? hs) (contains? hs h)))))})

(hst/deftrifecta-from-schema candidate?-conjoins-the-three-filters
  hive-spi.guard.rule/candidate?
  {:in  [:cat r/GuardRule e/GuardEvent]
   :out :boolean
   :rel (fn [[rule ev] out]
          (= out (and (not (false? (:rule/enabled? rule)))
                      (contains? (:rule/phases rule) (:guard/phase ev))
                      (let [hs (:rule/harnesses rule)]
                        (or (empty? hs) (contains? hs (:guard/harness ev)))))))})

(def ^:private rule
  (r/guard-rule {:rule/id :guard/x :rule/phases #{:pre-tool :stop}
                 :rule/match {} :rule/verdict :warn :rule/reason "r"
                 :rule/harnesses #{:eca}}))

(deftest-golden candidate-table
  "test/golden/hive_spi/guard/rule.edn"
  (vec (for [phase [:pre-tool :stop :prompt-submit]
             harness [:eca :mcp]
             enabled [true false]]
         [phase harness enabled
          (r/candidate? (assoc rule :rule/enabled? enabled)
                        {:guard/phase phase :guard/harness harness})
          (r/applies-to-phase? rule phase)])))
