(ns hive-spi.swarm.ports.agent-context-test
  "The agent-context slot: the Noop answers nil for every reader and accepts
   budget calls; an installed stub answers for itself; clearing falls back."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.ports.agent-context :as ac]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [prior (when (ac/agent-context-set?) (ac/get-agent-context))]
      (ac/clear-agent-context!)
      (try (f) (finally (ac/clear-agent-context!) (when prior (ac/set-agent-context! prior)))))))

(defn- stub-context [budgets]
  (reify
    ac/IAgentCallContext
    (current-agent-id [_] "ling-7")
    (current-directory [_] "/work/hive")
    ac/IBudgetGuardrail
    (register-budget! [_ agent-id max-usd opts]
      (let [e {:agent-id agent-id :max-usd max-usd :model (:model opts)}]
        (swap! budgets assoc agent-id e) e))
    (deregister-budget! [_ agent-id]
      (let [e (get @budgets agent-id)] (swap! budgets dissoc agent-id) e))))

(defn- readings [ctx]
  {:agent-id   (ac/current-agent-id ctx)
   :directory  (ac/current-directory ctx)
   :register   (ac/register-budget! ctx "ling-7" 2.0 {:model "m"})
   :deregister (ac/deregister-budget! ctx "ling-7")})

(deftest-golden agent-context-slot-golden
  "test/golden/hive_spi/swarm/ports/agent_context/slot.edn"
  (let [noop-view {:set? (ac/agent-context-set?) :noop? (identical? ac/noop (ac/get-agent-context))
                   :readings (readings (ac/get-agent-context))}
        stub      (stub-context (atom {}))
        installed (identical? stub (ac/set-agent-context! stub))
        stub-view {:set? (ac/agent-context-set?) :readings (readings (ac/get-agent-context))}
        cleared   (ac/clear-agent-context!)]
    {:noop noop-view :installed-returns-impl? installed :stub stub-view
     :cleared [cleared (ac/agent-context-set?) (identical? ac/noop (ac/get-agent-context))]}))

(deftest a-value-that-is-not-a-context-is-refused-test
  ;; The slot's :validate refuses (AssertionError), or the contract first.
  (is (thrown? Throwable (ac/set-agent-context! {})))
  (is (false? (ac/agent-context-set?))))
