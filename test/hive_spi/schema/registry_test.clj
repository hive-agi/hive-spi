(ns hive-spi.schema.registry-test
  (:require [clojure.test :refer [deftest testing is]]
            [hive-spi.schema.registry :as reg]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(deftest result-schema
  (testing "ok/err shapes conform; both branches carry extra keys (open)"
    (is (reg/validate :hive/result {:ok 42}))
    (is (reg/validate :hive/result {:ok nil}))
    (is (reg/validate :hive/result {:error :io/timeout}))
    (is (reg/validate :hive/result {:error :io/timeout :detail "x"})))
  (testing "non-Result values are rejected"
    (is (not (reg/validate :hive/result {:nope 1})))
    (is (not (reg/validate :hive/result 42)))
    (is (not (reg/validate :hive/result nil)))
    (is (some? (reg/explain :hive/result {:nope 1})))))

(deftest error-category-bridge
  (testing "error-category is any qualified keyword"
    (is (reg/validate :hive/error-category :io/timeout))
    (is (reg/validate :hive/error-category :whatever/custom))
    (is (not (reg/validate :hive/error-category :unqualified)))
    (is (not (reg/validate :hive/error-category "str"))))
  (testing "known-error-category gates on the dsl taxonomy registry"
    (is (reg/validate :hive/known-error-category :io/timeout))
    (is (reg/validate :hive/known-error-category :multi/cycle))
    (is (not (reg/validate :hive/known-error-category :bogus/nonsense)))))

(deftest register-seam
  (testing "register! adds a resolvable named schema (OCP extension point)"
    (reg/register! :test/positive-int [:int {:min 1}])
    (is (reg/validate :test/positive-int 3))
    (is (not (reg/validate :test/positive-int 0)))
    (is (contains? (reg/registered) :test/positive-int)))
  (testing "registered named schemas compose by ref"
    (reg/register! :test/wrapped [:map [:v :hive/result]])
    (is (reg/validate :test/wrapped {:v {:ok 1}}))
    (is (not (reg/validate :test/wrapped {:v {:nope 1}})))))
