(ns hive-spi.working-memory.entry-test
  "The pure entry helpers, driven by their contracts: keying, the CAS
   predicate and revision stamping by property, the delta by golden."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-schemas.test :as hst]
            [hive-spi.memory.ids :as ids]
            [hive-spi.working-memory.entry :as e]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema next-revision-is-one-past
  hive-spi.working-memory.entry/next-revision
  {:in  [:cat [:maybe [:map [:revision nat-int?]]]]
   :out pos-int?
   :rel (fn [[cur] out]
          (= out (if (nil? cur) 1 (inc (:revision cur)))))})

(hst/deftrifecta-from-schema cas-ok-judges-the-revision
  hive-spi.working-memory.entry/cas-ok?
  {:in  [:cat [:maybe [:map [:revision nat-int?]]] [:maybe nat-int?]]
   :out :boolean
   :rel (fn [[cur exp] out]
          (= out (if (nil? exp)
                   (nil? cur)
                   (and (some? cur) (= (:revision cur) exp)))))})

(hst/deftrifecta-from-schema changed-since-is-a-revision-gap
  hive-spi.working-memory.entry/changed-since?
  {:in  [:cat [:map [:revision nat-int?]] [:maybe nat-int?]]
   :out :boolean
   :rel (fn [[entry rev] out]
          (= out (or (nil? rev) (> (:revision entry) rev))))})

(deftest keying-round-trips
  (is (= ["w" "k"] (e/workspace-key "w" "k")))
  (is (= "w" (e/workspace-of (e/workspace-key "w" "k"))))
  (is (= "k" (e/key-of (e/workspace-key "w" "k")))))

(deftest normalize-fills-the-store-defaults
  (let [n (e/normalize {:workspace-id "w" :key "k" :claim "c" :owner {:agent "a"}})]
    (is (string? (:id n)))
    (is (= :draft (:status n)))
    (is (= 0 (:revision n)))
    (is (string? (:created n)))
    (is (string? (:updated n)))
    (is (e/valid? n) "a normalized entry conforms")))

(deftest stamp-advances-and-refreshes
  (let [base (e/normalize {:workspace-id "w" :key "k" :claim "c" :owner {:agent "a"}})
        first (e/stamp base nil)
        second (e/stamp base first)]
    (is (= 1 (:revision first)))
    (is (= 2 (:revision second)))))

(deftest stamp-keeps-identity
  (let [base (e/normalize {:workspace-id "w" :key "k" :claim "c" :owner {:agent "a"}})
        stored (e/stamp base nil)
        ;; a later write supplies a DIFFERENT id; the store keeps the key's
        newer (e/stamp (assoc base :id (ids/generate-id)) stored)]
    (is (= (:id stored) (:id newer)) "the key's id is stable across revisions")
    (is (= (:created stored) (:created newer)) "and so is :created")))

(deftest entry-size-is-the-rendered-length
  (is (= (count (pr-str {:a 1})) (e/entry-size {:a 1}))))

(deftest delta-of-honours-a-size-budget
  (let [es [{:key "a" :revision 1} {:key "b" :revision 1} {:key "c" :revision 1}]]
    (testing "a :max-entries cap answers :truncated? and advances only over what it delivered"
      (let [d (e/delta-of "w" es nil {:max-entries 2})]
        (is (= ["a" "b"] (mapv :key (:entries d))))
        (is (true? (:truncated? d)))
        (is (= {"a" 1 "b" 1} (:cursor d)) "c is not in the cursor, so it re-delivers")))
    (testing "a follow-up from the capped cursor delivers the rest"
      (let [d1 (e/delta-of "w" es nil {:max-entries 2})
            d2 (e/delta-of "w" es (:cursor d1) {:max-entries 2})]
        (is (= ["c"] (mapv :key (:entries d2))))
        (is (not (:truncated? d2)) "nothing left to truncate")))
    (testing "an unbounded delta is not truncated"
      (let [d (e/delta-of "w" es nil)]
        (is (= 3 (count (:entries d))))
        (is (not (:truncated? d)))))))

(deftest supersedes-names-the-older-id
  (is (true? (e/supersedes? {:supersedes "old"} {:id "old"})))
  (is (false? (e/supersedes? {:supersedes "x"} {:id "old"})))
  (is (false? (e/supersedes? {:supersedes "old"} nil))))

(deftest delta-of-delivers-new-and-edited-keys
  (testing "a nil cursor delivers every entry"
    (let [d (e/delta-of "w" [{:key "a" :revision 1} {:key "b" :revision 1}] nil)]
      (is (= ["a" "b"] (mapv :key (:entries d))))
      (is (= {"a" 1 "b" 1} (:cursor d)))))
  (testing "a caught-up cursor delivers nothing"
    (is (empty? (:entries (e/delta-of "w" [{:key "a" :revision 1}] {"a" 1})))))
  (testing "an edited key (revision advanced) re-delivers"
    (let [d (e/delta-of "w" [{:key "a" :revision 2} {:key "b" :revision 1}] {"a" 1 "b" 1})]
      (is (= ["a"] (mapv :key (:entries d))))))
  (testing "the cursor carries unknown keys forward"
    (is (= {"b" 2 "a" 3} (:cursor (e/delta-of "w" [{:key "a" :revision 3}] {"b" 2}))))))
