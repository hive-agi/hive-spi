(ns hive-spi.memory.stub-conformance-test
  "The reference stub is held to the same conformance suite every real store
   runs: once without an embedder (the degraded semantic branch) and once with
   a deterministic one (the positive branch)."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.memory.conformance :as conformance]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.stub :as stub]))

(defn- bag-embedder
  "Deterministic 8-dimensional bag-of-characters embedding."
  [text]
  (let [v (double-array 8)]
    (doseq [c (str text)]
      (let [i (mod (int c) 8)]
        (aset v i (inc (aget v i)))))
    (vec v)))

(conformance/defconformance stub
  #(stub/create-store {})
  {:connect-config {}})

(conformance/defconformance stub-semantic
  #(stub/create-store {:embedder bag-embedder})
  {:connect-config {}})

(deftest run-conformance-reports-every-case
  (let [r (conformance/run-conformance #(stub/create-store {}) {:connect-config {}})]
    (testing "every case ran, none skipped"
      (is (= conformance/case-ids (:ran r)))
      (is (empty? (:skipped r))))))

(deftest run-conformance-honours-skip-and-requires-opt
  (let [r (conformance/run-conformance #(stub/create-store {})
                                       {:skip {:batch "exercised elsewhere"}})]
    (testing "a skipped case and a case whose opt is absent are reported by name"
      (is (= "exercised elsewhere" (get-in r [:skipped :batch])))
      (is (contains? (:skipped r) :disconnect-then-connect))
      (is (not (some #{:batch :disconnect-then-connect} (:ran r)))))))

(deftest semantic-branches-are-honest
  (testing "no embedder: degraded result carries its reason as metadata"
    (let [s (stub/create-store {})
          r (ports/search-similar s "anything" {:limit 3})]
      (is (false? (ports/supports-semantic-search? s)))
      (is (= [] r))
      (is (= :no-embedder (get-in (meta r) [:hive-spi.memory/degraded :reason])))))
  (testing "embedder: results are ranked by score, best first"
    (let [s (stub/create-store {:embedder bag-embedder})]
      (ports/add-entry! s {:id "aaa" :type :note :content "aaaa"})
      (ports/add-entry! s {:id "zzz" :type :note :content "zzzz"})
      (let [r (ports/search-similar s "aaaa" {:limit 2})]
        (is (true? (ports/supports-semantic-search? s)))
        (is (= "aaa" (:id (first r))))
        (is (>= (:score (first r)) (:score (second r))))))))
