(ns hive-spi.schema.gen-test
  "Tests the malli-owned generation lever (hive-spi.schema.gen)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-spi.schema.gen :as g]
            [hive-spi.schema.registry :as reg]
            [hive-dsl.result :as r]
            [hive-dsl.result.taxonomy :as tax]
            [hive-spi.schema.derive :as der]))

(defspec generator-yields-registry-valid-results 200
  (prop/for-all [x (g/generator :hive/result)]
    (reg/validate :hive/result x)))

(defspec generated-results-are-ok-or-err 200
  (prop/for-all [x (g/generator :hive/result)]
    (or (r/ok? x) (r/err? x))))

(defspec known-category-generator-draws-from-taxonomy 200
  ;; honours the :gen/elements property on :hive/known-error-category
  (prop/for-all [c (g/generator :hive/known-error-category)]
    (and (tax/known-error? c)
         (reg/validate :hive/known-error-category c))))

(deftest generate-is-seed-reproducible
  (testing "same seed -> same value (needed for shrinking / repro oracles)"
    (is (= (g/generate :hive/result {:seed 42})
           (g/generate :hive/result {:seed 42})))))

(deftest sample-returns-conforming-collection
  (testing "sample yields a non-empty collection of conforming values"
    (let [xs (g/sample :hive/err)]
      (is (seq xs))
      (is (every? #(reg/validate :hive/err %) xs)))))

(deftest generator-accepts-inline-malli-form
  (testing "resolves registry refs AND plain malli forms"
    (let [x (g/generate [:map [:a :int] [:b :string]] {:seed 7})]
      (is (integer? (:a x)))
      (is (string? (:b x))))))

(deftest seeded-cases-are-deterministic
  (testing "same seed + n -> identical labelled cases (repro oracles)"
    (is (= (g/seeded-cases :hive/result 42 8)
           (g/seeded-cases :hive/result 42 8))))
  (testing "labels are :case-0..:case-(n-1), sorted"
    (is (= [:case-0 :case-1 :case-2]
           (keys (g/seeded-cases :hive/err 0 3))))))

(deftest loading-gen-registers-the-test-projection
  (testing "requiring this ns wires :generator/:cases into the derivation lever"
    (is (contains? (der/projections) :generator))
    (is (contains? (der/projections) :cases))))