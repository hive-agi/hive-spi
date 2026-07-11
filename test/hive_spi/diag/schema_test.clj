(ns hive-spi.diag.schema-test
  "Conformance tests for the hive-spi.diag.schema value objects: each smart
   constructor accepts a conformant map and FAILS LOUD (ex-info {:error
   :diag/invalid …}) on a non-conformant one, and `human-bytes` renders the
   canonical byte unit. These are the domain invariants every diag adapter
   relies on when it returns a value object."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.diag.schema :as s]))

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
