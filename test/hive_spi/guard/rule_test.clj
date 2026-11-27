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

(def ^:private ProjectId [:enum "hive" "hive-guard" "funeraria" "funeraria-libs"])
(def ^:private AgentKind [:enum :coordinator :ling :human-session])

(hst/deftrifecta-from-schema applies-to-projects?-needs-a-shared-id-when-scoped
  hive-spi.guard.rule/applies-to-projects?
  {:in  [:cat [:map [:rule/projects [:maybe [:set ProjectId]]]]
         [:maybe [:set ProjectId]]]
   :out :boolean
   :rel (fn [[rule ids] out]
          (= out (let [ps (:rule/projects rule)]
                   (or (empty? ps)
                       (boolean (seq (filter ps ids)))))))})

(hst/deftrifecta-from-schema applies-to-agent-kind?-honours-the-narrowing
  hive-spi.guard.rule/applies-to-agent-kind?
  {:in  [:cat [:map [:rule/agent-kinds [:maybe [:set AgentKind]]]]
         [:maybe AgentKind]]
   :out :boolean
   :rel (fn [[rule kind] out]
          (= out (let [ks (:rule/agent-kinds rule)]
                   (or (empty? ks) (contains? ks kind)))))})

(hst/deftrifecta-from-schema candidate?-conjoins-the-five-filters
  hive-spi.guard.rule/candidate?
  {:in  [:cat r/GuardRule e/GuardEvent]
   :out :boolean
   :rel (fn [[rule ev] out]
          (= out (and (not (false? (:rule/enabled? rule)))
                      (contains? (:rule/phases rule) (:guard/phase ev))
                      (let [hs (:rule/harnesses rule)]
                        (or (empty? hs) (contains? hs (:guard/harness ev))))
                      (let [ps (:rule/projects rule)]
                        (or (empty? ps)
                            (boolean (seq (filter ps (:project/ids ev))))))
                      (let [ks (:rule/agent-kinds rule)]
                        (or (empty? ks) (contains? ks (:agent/kind ev)))))))})

(def ^:private scoped
  (r/guard-rule {:rule/id :guard/scoped :rule/phases #{:pre-tool :stop}
                 :rule/match {} :rule/verdict :warn :rule/reason "r"
                 :rule/projects #{"funeraria"}
                 :rule/agent-kinds #{:coordinator}}))

;; A rule scoped to a parent project holds in a descendant because the host
;; puts the ancestors in :project/ids; an unresolved scope never fires it.
(deftest-golden scope-table
  "test/golden/hive_spi/guard/rule_scope.edn"
  (vec (for [ids  [nil #{} #{"hive"} #{"funeraria"} #{"funeraria-libs" "funeraria"}]
             kind [nil :coordinator :ling :human-session]]
         [ids kind
          (r/candidate? scoped {:guard/phase :stop :guard/harness :claude-code
                                :project/ids ids :agent/kind kind})])))

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
