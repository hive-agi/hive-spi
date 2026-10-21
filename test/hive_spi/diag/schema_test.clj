(ns hive-spi.diag.schema-test
  "Conformance tests for the hive-spi.diag.schema value objects: each smart
   constructor accepts a conformant map and FAILS LOUD (ex-info {:error
   :diag/invalid …}) on a non-conformant one, and `human-bytes` renders the
   canonical byte unit. These are the domain invariants every diag adapter
   relies on when it returns a value object."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.diag.schema :as s]
            [hive-schemas.test :as hst]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(deftest valid?-and-explain
  (testing "valid? is a pure boolean gate"
    (is (true?  (s/valid? s/HeapSnapshot {:diag/used-bytes 1 :diag/committed-bytes 2 :diag/max-bytes 3})))
    (is (false? (s/valid? s/HeapSnapshot {:diag/used-bytes -1 :diag/committed-bytes 2 :diag/max-bytes 3}))))
  (testing "explain is nil on conformance, a map on failure"
    (is (nil? (s/explain s/HeapSnapshot {:diag/used-bytes 1 :diag/committed-bytes 2 :diag/max-bytes 3})))
    (is (some? (s/explain s/HeapSnapshot {:diag/used-bytes "no"})))))

(deftest smart-ctors-happy-path
  (testing "each ->ctor returns the map it was given when conformant"
    (is (map? (s/->heap-snapshot   {:diag/used-bytes 1 :diag/committed-bytes 2 :diag/max-bytes 3})))
    (is (map? (s/->class-histogram {:diag/entries [{:diag/rank 1 :diag/class "X" :diag/instances 5 :diag/bytes 40}]})))
    (is (map? (s/->allocation      {:diag/duration-ms 3000 :diag/samples-bps [1 2] :diag/mean-bps 1 :diag/peak-bps 2})))
    (is (map? (s/->flamegraph      {:diag/event :cpu :diag/path "/tmp/f.html"})))
    (is (map? (s/->cache-occupancy {:diag/cache-id :x :diag/stores []})))
    (is (map? (s/->reclamation     {:diag/used-before-bytes 9 :diag/used-after-bytes 4 :diag/reclaimed-bytes 5})))
    (is (map? (s/->retainer-report {:diag/roots [{:diag/label "a" :diag/bytes 1}]})))
    (is (map? (s/->diagnosis       {:diag/snapshot  {:diag/used-bytes 1 :diag/committed-bytes 2 :diag/max-bytes 3}
                                    :diag/histogram {:diag/entries []}
                                    :diag/caches    []})))))

(deftest smart-ctors-fail-loud
  (testing "a non-conformant map throws ex-info {:error :diag/invalid} with an explanation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"heap-snapshot"
          (s/->heap-snapshot {:diag/used-bytes -1 :diag/committed-bytes 2 :diag/max-bytes 3})))
    (let [ex (try (s/->reclamation {:diag/used-before-bytes 1}) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :diag/invalid (:error (ex-data ex))))
      (is (some? (:explanation (ex-data ex))))))
  (testing "reclaimed-bytes may be negative (heap grew) — that is information, not error"
    (is (map? (s/->reclamation {:diag/used-before-bytes 4 :diag/used-after-bytes 9 :diag/reclaimed-bytes -5}))))
  (testing "flamegraph event is a closed enum"
    (is (thrown? clojure.lang.ExceptionInfo
          (s/->flamegraph {:diag/event :bogus :diag/path "/x"})))))

(deftest human-bytes-rendering
  (testing "nil renders n/a"
    (is (= "n/a" (s/human-bytes nil))))
  (testing "sub-KiB stays in bytes"
    (is (= "512.0 B" (s/human-bytes 512))))
  (testing "scales up through the unit ladder"
    (is (= "1.0 KiB" (s/human-bytes 1024)))
    (is (= "1.0 MiB" (s/human-bytes (* 1024 1024))))
    (is (= "1.0 GiB" (s/human-bytes (* 1024 1024 1024)))))
  (testing "rounds to 2 decimals (11.08 GiB from the leak fingerprint)"
    (is (= "11.08 GiB" (s/human-bytes 11901239296)))))

;; ---------------------------------------------------------------------------
;; Facets: every smart constructor over its own schema's generator
;; ---------------------------------------------------------------------------

(hst/deftrifecta-from-schema heap-snapshot-ctor-is-identity-on-conformant
  hive-spi.diag.schema/->heap-snapshot
  {:in [:cat s/HeapSnapshot] :out s/HeapSnapshot :rel (fn [[x] out] (= x out))})

(hst/deftrifecta-from-schema class-histogram-ctor-is-identity-on-conformant
  hive-spi.diag.schema/->class-histogram
  {:in [:cat s/ClassHistogram] :out s/ClassHistogram :rel (fn [[x] out] (= x out))})

(hst/deftrifecta-from-schema reclamation-ctor-is-identity-on-conformant
  hive-spi.diag.schema/->reclamation
  {:in [:cat s/Reclamation] :out s/Reclamation :rel (fn [[x] out] (= x out))})

;; ->diagnosis is not a trifecta: DiagnosisReport nests three levels of
;; vectors of maps and its generator stalls at test.check's default size.
;; smart-ctors-happy-path covers it; the allocation ctor stands in here.
(hst/deftrifecta-from-schema allocation-ctor-is-identity-on-conformant
  hive-spi.diag.schema/->allocation
  {:in [:cat s/AllocationProfile] :out s/AllocationProfile :rel (fn [[x] out] (= x out))})

(hst/deftrifecta-from-schema human-bytes-renders-a-unit
  hive-spi.diag.schema/human-bytes
  {:in  [:cat [:maybe [:int {:min 0}]]]
   :out :string
   :rel (fn [[n] out]
          (if (nil? n)
            (= "n/a" out)
            (boolean (re-matches #"\d+\.\d+ (B|KiB|MiB|GiB|TiB)" out))))})

(deftest-golden human-bytes-ladder-and-edges
  "test/golden/hive_spi/diag/schema.edn"
  (mapv (juxt identity s/human-bytes)
        [nil 0 1 1023 1024 1536 (* 1024 1024) 11901239296
         (* 1024 1024 1024 1024) (* 5000 1024 1024 1024 1024)]))
