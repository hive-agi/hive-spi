(ns hive-spi.vector.memory-test
  "The in-memory store is the reference the vector port is specified
   against, so its ordering and distances are pinned as a golden."
  (:require [hive-schemas.test :as hst]
            [hive-spi.vector.memory :as mem]
            [hive-spi.vector.ports :as vp]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema in-memory-store-starts-empty
  hive-spi.vector.memory/in-memory-store
  {:in  [:cat]
   :out vp/Store
   :rel (fn [_ store]
          (and (nil? (vp/-get-collection store "c"))
               (= "c" (vp/-create-collection store "c" {}))
               (= [] (vp/-get store "c" {}))))})

(defn- query-rows
  "Seed one store, then project the queries that pin the port's ordering."
  []
  (let [s (mem/in-memory-store)]
    (vp/-create-collection s "c" {})
    (vp/-add s "c"
             [{:id "a" :embedding [0.0 0.0] :document "a" :metadata {:k 1}}
              {:id "b" :embedding [3.0 4.0] :document "b" :metadata {:k 2}}
              {:id "c" :embedding [1.0 1.0] :document "c" :metadata {:k 1}}
              {:id "d" :document "no embedding" :metadata {:k 1}}]
             {})
    {:nearest-origin (mapv (juxt :id :distance) (vp/-query s "c" [0.0 0.0] {}))
     :nearest-far    (mapv (juxt :id :distance) (vp/-query s "c" [3.0 4.0] {:n-results 2}))
     :where-k1       (mapv :id (vp/-query s "c" [3.0 4.0] {:where {:k 1}}))
     :limit-1        (count (vp/-get s "c" {:limit 1}))}))

(deftest-golden query-orders-by-ascending-l2-distance
  "test/golden/hive_spi/vector/memory.edn"
  (query-rows))
