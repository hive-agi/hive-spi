(ns hive-spi.memory.stub.create-test
  "The reference store's constructor: connected and empty, and semantic
   search on exactly when an embedder is configured."
  (:require [hive-schemas.test :as hst]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.stub :as stub]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema create-store-is-connected-and-empty
  hive-spi.memory.stub/create-store
  {:in  [:cat stub/StubConfig]
   :out stub/StubStore
   :rel (fn [[config] s]
          (and (ports/connected? s)
               (= [] (ports/query-entries s {}))
               (= (boolean (:embedder config)) (ports/supports-semantic-search? s))))})

(defn- embed [text] [(double (count text)) 1.0])

(deftest-golden stub-round-trip
  "test/golden/hive_spi/memory/stub_create.edn"
  (let [plain (stub/create-store)
        sem   (stub/create-store {:embedder embed})
        id    (ports/add-entry! sem {:id "e1" :content "hello" :created "2026-01-01T00:00:00Z"
                                     :updated "2026-01-01T00:00:00Z"})]
    (ports/add-entry! sem {:id "e2" :content "hello world" :created "2026-01-02T00:00:00Z"
                           :updated "2026-01-02T00:00:00Z"})
    {:id         id
     :plain-hits (ports/search-similar plain "x" {})
     :plain-why  (meta (ports/search-similar plain "x" {}))
     :sem-ids    (mapv :id (ports/search-similar sem "hello" {:limit 5}))
     :batch      (mapv :id (ports/get-entries sem ["e2" "e1" "e2" "zz"]))
     :status     (ports/store-status sem)}))
