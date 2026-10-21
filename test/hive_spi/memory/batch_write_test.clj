(ns hive-spi.memory.batch-write-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.memory.contract :as contract]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.stub :as stub]))

(deftest add-entries-result-contract
  (testing "one AddResult per entry"
    (is (contract/add-entries-result? 3 ["a" {:success? true :queued? true} {:success? false :error :x}]))
    (is (contract/add-entries-result? 0 [])))
  (testing "count mismatch or non-AddResult element is rejected"
    (is (not (contract/add-entries-result? 2 ["a"])))
    (is (not (contract/add-entries-result? 1 [nil])))
    (is (not (contract/add-entries-result? 1 '("a"))))))

(deftest stub-add-entries-in-input-order
  (let [store (stub/create-store)
        es    [{:id "x" :type :note :content "1"}
               {:type :note :content "2"}
               {:id "y" :type :note :content "3"}]
        r     (ports/add-entries! store es)]
    (is (satisfies? ports/IMemoryStoreBatchWrite store))
    (is (contract/add-entries-result? 3 r))
    (is (= "x" (first r)))
    (is (= "y" (nth r 2)))
    (is (= "2" (:content (ports/get-entry store (second r)))))))
