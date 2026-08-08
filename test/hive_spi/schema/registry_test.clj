(ns hive-spi.schema.registry-test
  (:require [clojure.test :refer [deftest testing is]]
            [hive-spi.schema.registry :as reg]))

;; SPDX-License-Identifier: MIT
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

(deftest registration-is-idempotent-not-layered
  (testing "re-registering a bundle neither grows the registry nor deepens lookup"
    (let [before (count (reg/registered))]
      (dotimes [_ 200]
        (reg/register-all! {::probe-leaf [:int {:min 1}]
                            ::probe-ref  [:vector ::probe-leaf]}))
      (is (= 2 (- (count (reg/registered)) before))
          "200 registrations of 2 keys add exactly 2 keys")
      (is (reg/validate ::probe-ref [1 2 3])
          "a ref through a repeatedly-registered key still resolves")
      (is (not (reg/validate ::probe-ref [0])))
      (reg/deregister-all! [::probe-leaf ::probe-ref])))
  (testing "the composite registry is built ONCE, not per registration"
    (let [r reg/registry]
      (reg/register-all! {::probe-after [:int]})
      (is (identical? r reg/registry)
          "a per-call composite layer here is what makes lookup depth grow
           until malli.core/schema overflows the stack on reload")
      (is (reg/validate ::probe-after 1)
          "a key registered AFTER the registry value was built still resolves —
           the mutable registry is read through, not snapshotted")
      (reg/deregister-all! [::probe-after])))
  (testing "re-registering a key with a DIFFERENT schema replaces it"
    (reg/register! ::probe-swap [:int])
    (is (reg/validate ::probe-swap 1))
    (reg/register! ::probe-swap :string)
    (is (not (reg/validate ::probe-swap 1)))
    (is (reg/validate ::probe-swap "x"))
    (reg/deregister-all! [::probe-swap])))
