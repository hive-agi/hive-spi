(ns hive-spi.catchup.registry-test
  "The catchup block registry is the seam through which a host composes its
   catchup answer from contributors it never names, so a registry that
   accepts a malformed block, runs blocks out of order, or lets one failing
   block take the others down defeats the composition."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [hive-spi.catchup.registry :as creg]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each (fn [f] (creg/reset-registry!) (f) (creg/reset-registry!)))

(defn- block [id order f]
  {:block/id id :block/fn f :block/order order})

(deftest an-empty-registry-composes-to-nothing-test
  (is (= {:blocks {} :failed {}} (creg/compose {:project-id "p"})))
  (is (= [] (creg/registered-blocks)))
  (is (false? (creg/registered? :kanban))))

(deftest a-malformed-block-is-rejected-test
  (is (thrown? AssertionError (creg/register-block! {:block/id :x})))
  (is (thrown? AssertionError (creg/register-block! {:block/id "x" :block/fn identity :block/order 1})))
  (is (thrown? AssertionError (creg/register-block! {:block/id :x :block/fn identity :block/order "1"})))
  (is (m/validate creg/Block (block :ok 1 identity)))
  (is (empty? (creg/registered-blocks))))

(deftest blocks-run-in-order-against-the-context-test
  (let [seen (atom [])
        run! (fn [id] (fn [ctx] (swap! seen conj id) {:pid (:project-id ctx) :id id}))]
    (creg/register-block! (block :late 30 (run! :late)))
    (creg/register-block! (block :early 10 (run! :early)))
    (creg/register-block! (block :mid 20 (run! :mid)))
    (let [{:keys [blocks failed]} (creg/compose {:project-id "hive"})]
      (is (= [:early :mid :late] @seen))
      (is (= [:early :mid :late] (mapv :block/id (creg/registered-blocks))))
      (is (= {:pid "hive" :id :mid} (:mid blocks)))
      (is (= {} failed)))))

(deftest a-throwing-block-is-isolated-test
  (creg/register-block! (block :boom 10 (fn [_] (throw (ex-info "kaboom" {})))))
  (creg/register-block! (block :fine 20 (fn [_] {:ok true})))
  (let [{:keys [blocks failed]} (creg/compose {})]
    (is (= {:fine {:ok true}} blocks))
    (is (= {:boom "kaboom"} failed))))

(deftest re-registering-an-id-replaces-the-block-test
  (creg/register-block! (block :k 10 (fn [_] :v1)))
  (creg/register-block! (block :k 10 (fn [_] :v2)))
  (is (= 1 (count (creg/registered-blocks))))
  (is (= :v2 (get-in (creg/compose {}) [:blocks :k])))
  (creg/unregister-block! :k)
  (is (false? (creg/registered? :k)))
  (is (nil? (creg/unregister-block! :k)) "unregistering an absent id is a no-op"))

(deftest a-nil-block-value-is-kept-under-its-id-test
  (creg/register-block! (block :empty 1 (fn [_] nil)))
  (let [{:keys [blocks]} (creg/compose {})]
    (is (contains? blocks :empty))
    (is (nil? (:empty blocks)))))
