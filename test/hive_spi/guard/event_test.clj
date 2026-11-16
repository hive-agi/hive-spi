;; MIT License - Copyright (c) 2026 Pedro Gomes Branquinho (BuddhiLW)

(ns hive-spi.guard.event-test
  "GuardEvent gates over the schema's own generator: every generated event
   passes `guard-event` unchanged, and the membership predicates agree with
   the closed vocabularies they name. The behavioural cases live in
   hive-spi.guard.contract-test."
  (:require [clojure.test :refer [deftest is]]
            [hive-schemas.test :as hst]
            [hive-spi.guard.event :as e]
            [hive-test.golden :refer [deftest-golden]]))

(hst/deftrifecta-from-schema guard-event-passes-a-conformant-event-through
  hive-spi.guard.event/guard-event
  {:in  [:cat e/GuardEvent]
   :out e/GuardEvent
   :rel (fn [[ev] out] (= ev out))})

(def ^:private Candidate
  "Phase keywords, near misses and non-keywords, so both answers are reached."
  [:enum :pre-tool :post-tool :session-start :subagent-start :prompt-submit :stop
   :pre-Tool :on-compact "pre-tool" nil 42])

(hst/deftrifecta-from-schema phase?-is-membership-in-phase-keys
  hive-spi.guard.event/phase?
  {:in  [:cat Candidate]
   :out :boolean
   :rel (fn [[x] out] (= out (contains? e/phase-keys x)))})

(hst/deftrifecta-from-schema tool-phase?-is-membership-in-tool-phase-keys
  hive-spi.guard.event/tool-phase?
  {:in  [:cat Candidate]
   :out :boolean
   :rel (fn [[x] out] (= out (contains? e/tool-phase-keys x)))})

(hst/deftrifecta-from-schema valid?-accepts-every-generated-event
  hive-spi.guard.event/valid?
  {:in  [:cat e/GuardEvent]
   :out :boolean
   :rel (fn [_ out] (true? out))})

(deftest explain-is-nil-exactly-when-valid
  (is (nil? (e/explain {:guard/phase :stop :guard/harness :mcp})))
  (is (map? (e/explain {:guard/phase :stop}))))

(deftest-golden validity-across-the-phases
  "test/golden/hive_spi/guard/event.edn"
  (mapv (fn [ev] [ev (e/valid? ev)])
        [{:guard/phase :pre-tool :guard/harness :eca :tool/name "Bash"}
         {:guard/phase :pre-tool :guard/harness :eca}
         {:guard/phase :prompt-submit :guard/harness :claude-code :prompt "go"}
         {:guard/phase :prompt-submit :guard/harness :claude-code}
         {:guard/phase :stop :guard/harness :mcp}
         {:guard/phase :stop :guard/harness "mcp"}
         {:guard/phase :on-compact :guard/harness :mcp}]))
