(ns hive-spi.swarm.dispatch-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-dsl.result :as r]
            [hive-spi.swarm.dispatch :as d]
            [hive-spi.swarm.dispatch.conformance :as conf]
            [hive-spi.swarm.dispatch.stub :as stub]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each (fn [f] (d/reset-dispatchers!) (try (f) (finally (d/reset-dispatchers!)))))

(conf/defconformance stub-dispatcher stub/harness)

(deftest registry-orders-by-priority-then-id
  (d/register-dispatcher! :b (stub/->stub) 20)
  (d/register-dispatcher! :a (stub/->stub) 20)
  (d/register-dispatcher! :z (stub/->stub) 10)
  (is (= [:z :a :b] (mapv :id (d/registered)))))

(deftest register-replaces-and-unregister-is-idempotent
  (let [one (stub/->stub) two (stub/->stub)]
    (d/register-dispatcher! :x one 5)
    (d/register-dispatcher! :x two 5)
    (is (identical? two (d/dispatcher :x)))
    (is (= 1 (count (d/registered))))
    (d/unregister-dispatcher! :x)
    (d/unregister-dispatcher! :x)
    (is (nil? (d/dispatcher :x)))
    (is (not (d/registered? :x)))))

(deftest register-refuses-wiring-errors
  (testing "not a dispatcher"
    (is (thrown? clojure.lang.ExceptionInfo (d/register-dispatcher! :x {} 1))))
  (testing "id and priority types"
    (is (thrown? clojure.lang.ExceptionInfo (d/register-dispatcher! "x" (stub/->stub) 1)))
    (is (thrown? clojure.lang.ExceptionInfo (d/register-dispatcher! :x (stub/->stub) :hi)))))

(deftest health-defaults-to-ok-without-a-probe
  (is (r/ok? (d/dispatcher-health (stub/->stub)))))

(deftest health-of-a-throwing-probe-is-unavailable
  (let [bad (reify d/ITaskDispatcher
              (dispatch-task! [_ _ _] (r/ok {:agent-id "a" :run-id 1}))
              (capabilities [_] #{})
              d/IDispatcherHealth
              (health [_] (throw (ex-info "down" {}))))
        res (d/dispatcher-health bad)]
    (is (r/err? res))
    (is (= :unavailable (:error/kind res)))))

(deftest stub-records-calls
  (let [s (stub/->stub #{:budget})]
    (d/dispatch-task! s {:task-id "t1"} {:run-id "r"})
    (is (= [{:task {:task-id "t1"} :opts {:run-id "r"}}] (stub/calls s)))
    (is (= #{:budget} (d/capabilities s)))))
