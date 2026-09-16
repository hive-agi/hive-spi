(ns hive-spi.vector.ports-test
  "The vector-collection port, and the in-memory implementation it is
   specified against.

   Two of these assertions are the reason the port exists at all rather than
   being nice-to-have:

     the slot ACCEPTS the reference implementation -- a port whose own
     `set-store!` rejects the store shipped beside it is worse than no port,
     because every caller discovers it at wiring time;

     `-query` orders NEAREST FIRST on an ASCENDING `:distance` -- a backend
     that passed a cosine SIMILARITY through the same key would order every
     result backwards, and a fake that returned insertion order would agree
     with both."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-spi.vector.ports :as vp]
            [hive-spi.vector.memory :as mem]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [prior (vp/get-store)]
      (try (f)
           (finally
             (if prior (vp/set-store! prior) (vp/clear-store!)))))))

(defn- seeded []
  (let [s (mem/in-memory-store)]
    (vp/-create-collection s "c" {:get-or-create? true})
    (vp/-add s "c"
             [{:id "near" :embedding [0.0 0.0] :document "n" :metadata {:kind "p"}}
              {:id "mid"  :embedding [1.0 0.0] :document "m" :metadata {:kind "q"}}
              {:id "far"  :embedding [3.0 4.0] :document "f" :metadata {:kind "p"}}]
             {})
    s))

(deftest the-reference-implementation-satisfies-the-port
  (let [s (mem/in-memory-store)]
    (is (satisfies? vp/IVectorCollectionStore s)
        "a port whose reference implementation does not satisfy it is a wiring trap")
    (testing "and the slot accepts it, which is the thing callers actually do"
      (is (identical? s (vp/set-store! s)))
      (is (identical? s (vp/get-store)))
      (is (true? (vp/store-set?))))))

(deftest query-is-nearest-first-on-an-ascending-distance
  (let [rows (vp/-query (seeded) "c" [0.0 0.0] {:n-results 3})]
    (is (= ["near" "mid" "far"] (mapv :id rows)))
    (is (apply < (mapv :distance rows))
        "a SIMILARITY passed through :distance would reverse this")
    (is (every? #(contains? % :distance) rows))))

(deftest query-respects-n-results-and-where
  (let [s (seeded)]
    (is (= 2 (count (vp/-query s "c" [0.0 0.0] {:n-results 2}))))
    (is (= ["near" "far"] (mapv :id (vp/-query s "c" [0.0 0.0] {:where {:kind "p"}})))
        "the where filter applies BEFORE the nearest-first cut")))

(deftest create-collection-refuses-a-duplicate-unless-asked
  (let [s (mem/in-memory-store)]
    (vp/-create-collection s "c" {})
    (is (thrown? clojure.lang.ExceptionInfo (vp/-create-collection s "c" {}))
        "silently returning the existing collection hides a name collision")
    (is (= "c" (vp/-create-collection s "c" {:get-or-create? true})))))

(deftest a-dimension-mismatch-is-loud
  (is (thrown? clojure.lang.ExceptionInfo (vp/-query (seeded) "c" [0.0] {}))
      "a wrong embedding provider announces itself as a dimension mismatch"))

(deftest get-selects-by-ids-and-where
  (let [s (seeded)]
    (is (= #{"near" "mid"} (set (mapv :id (vp/-get s "c" {:ids ["near" "mid"]})))))
    (is (= ["mid"] (mapv :id (vp/-get s "c" {:where {:kind "q"}}))))
    (is (= 3 (count (vp/-get s "c" {}))) "no selector returns everything")))

(deftest update-merges-and-delete-removes
  (let [s (seeded)]
    (vp/-update s "c" [{:id "mid" :document "changed"}])
    (is (= "changed" (:document (first (vp/-get s "c" {:ids ["mid"]})))))
    (is (= [1.0 0.0] (:embedding (first (vp/-get s "c" {:ids ["mid"]}))))
        "update MERGES: an unnamed key survives")
    (testing "an update to an absent id adds nothing"
      (vp/-update s "c" [{:id "ghost" :document "x"}])
      (is (= 3 (count (vp/-get s "c" {})))))
    (vp/-delete s "c" {:ids ["mid"]})
    (is (= #{"near" "far"} (set (mapv :id (vp/-get s "c" {})))))))

(deftest collection-lifecycle
  (let [s (mem/in-memory-store)]
    (is (nil? (vp/-get-collection s "absent")))
    (vp/-create-collection s "c" {})
    (is (= "c" (vp/-get-collection s "c")))
    (vp/-delete-collection s "c")
    (is (nil? (vp/-get-collection s "c")))))

(deftest stores-are-independent
  (let [a (seeded) b (mem/in-memory-store)]
    (vp/-create-collection b "c" {:get-or-create? true})
    (is (= 3 (count (vp/-get a "c" {}))))
    (is (= 0 (count (vp/-get b "c" {})))
        "each call to in-memory-store gets its own state, so one test cannot poison another")))
