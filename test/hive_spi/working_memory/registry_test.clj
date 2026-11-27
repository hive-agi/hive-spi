(ns hive-spi.working-memory.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-spi.working-memory.ports :as ports]
            [hive-spi.working-memory.reference-store :as rs]
            [hive-spi.working-memory.registry :as reg]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each (fn [f] (reg/reset-registry!) (try (f) (finally (reg/reset-registry!)))))

(defn- err [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest store-installs-and-reads-back
  (is (false? (reg/store-set?)))
  (let [s (rs/make-store)]
    (is (identical? s (reg/set-store! s)))
    (is (true? (reg/store-set?)))
    (is (identical? s (reg/get-store)))
    (is (nil? (reg/clear-store!)))
    (is (false? (reg/store-set?)))))

(deftest get-store-throws-when-absent
  (is (= :wm/store-unavailable (:error (err #(reg/get-store))))))

(deftest a-non-store-is-refused-at-install
  (is (thrown? AssertionError (reg/set-store! {:not "a store"}))))

(deftest consolidation-and-activation-are-optional
  (is (nil? (reg/get-consolidator)) "no consolidator reads nil, not a throw")
  (is (nil? (reg/get-activator)))
  (is (false? (reg/consolidator-set?)))
  (let [c (reify ports/IConsolidation
            (consolidate! [_ _] {:ok? true :long-term-id "lt" :edges []}))]
    (reg/set-consolidator! c)
    (is (true? (reg/consolidator-set?)))
    (is (identical? c (reg/get-consolidator))))
  (let [a (reify ports/IActivation
            (activate [_ ws _] {:workspace-id ws :entries []}))]
    (reg/set-activator! a)
    (is (true? (reg/activator-set?)))
    (is (identical? a (reg/get-activator)))))

(deftest a-wrong-protocol-is-refused-at-install
  (let [not-a-consolidator (rs/make-store)]
    (is (thrown? AssertionError (reg/set-consolidator! not-a-consolidator))))
  (let [not-an-activator (rs/make-store)]
    (is (thrown? AssertionError (reg/set-activator! not-an-activator)))))

(deftest registry-status-reports-what-is-installed
  (is (= {:store false :consolidation false :activation false} (reg/registry-status)))
  (reg/set-store! (rs/make-store))
  (is (= {:store true :consolidation false :activation false} (reg/registry-status))))
