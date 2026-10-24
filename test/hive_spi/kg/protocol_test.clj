(ns hive-spi.kg.protocol-test
  "The KG storage SPI: the three capability predicates agree with
   `satisfies?` for every value, and the noop store is a total, inert
   IKGStore that is neither persistent nor temporal."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [hive-schemas.test :as hst]
            [hive-spi.kg.protocol :as kg]))

;; SPDX-License-Identifier: MIT

(defrecord FullStore []
  kg/IKGStore
  (ensure-conn! [_] :conn)
  (transact! [_ tx] {:tx-data tx})
  (query [_ _q] #{[1]})
  (query [_ _q _in] #{[2]})
  (entity [_ eid] {:db/id eid})
  (entid [_ _] 1)
  (pull-entity [_ _ eid] {:db/id eid})
  (eids-by-attr [_ _] '(1 2))
  (db-snapshot [_] :db)
  (reset-conn! [_] nil)
  (close! [_] nil)
  kg/IPersistentKGStore
  (delete-database! [_ confirm]
    (if (= :i-mean-it confirm) :deleted (throw (ex-info "refused" {}))))
  kg/ITemporalKGStore
  (history-db [_] :history)
  (as-of-db [_ t] [:as-of t])
  (since-db [_ t] [:since t])
  (query-history [_ _q] #{})
  (query-history [_ _q _in] #{})
  (query-as-of [_ _t _q] #{})
  (query-as-of [_ _t _q _in] #{}))

(hst/deftrifecta-from-schema kg-store-predicate-contract
  hive-spi.kg.protocol/kg-store?
  {:in        :any
   :out       :boolean
   :rel       (fn [x out] (= out (satisfies? kg/IKGStore x)))
   :num-tests 100})

(hst/deftrifecta-from-schema persistent-store-predicate-contract
  hive-spi.kg.protocol/persistent-store?
  {:in        :any
   :out       :boolean
   :rel       (fn [x out] (= out (satisfies? kg/IPersistentKGStore x)))
   :num-tests 100})

(hst/deftrifecta-from-schema temporal-store-predicate-contract
  hive-spi.kg.protocol/temporal-store?
  {:in        :any
   :out       :boolean
   :rel       (fn [x out] (= out (satisfies? kg/ITemporalKGStore x)))
   :num-tests 100})

(deftest predicates-separate-the-capability-tiers-test
  (let [noop (kg/noop-store) full (->FullStore)]
    (testing "the noop store is a base store only"
      (is (true? (kg/kg-store? noop)))
      (is (false? (kg/persistent-store? noop)))
      (is (false? (kg/temporal-store? noop))))
    (testing "a full backend satisfies every tier"
      (is (true? (kg/kg-store? full)))
      (is (true? (kg/persistent-store? full)))
      (is (true? (kg/temporal-store? full))))
    (testing "plain data satisfies none"
      (is (every? false? ((juxt kg/kg-store? kg/persistent-store? kg/temporal-store?) {}))))))

(deftest noop-store-is-inert-and-total-test
  (let [s (kg/noop-store)]
    (is (m/validate kg/KGStore s))
    (is (not (identical? s (kg/noop-store))) "a fresh store per call")
    (is (= (kg/noop-store) s) "but value-equal: it carries no state")
    (is (nil? (kg/ensure-conn! s)))
    (is (nil? (kg/transact! s [{:a 1}])))
    (is (= #{} (kg/query s '[:find ?e :where [?e :a]])))
    (is (= #{} (kg/query s '[:find ?e :in $ ?v :where [?e :a ?v]] [1])))
    (is (nil? (kg/entity s 1)))
    (is (nil? (kg/entid s [:a 1])))
    (is (nil? (kg/pull-entity s '[*] 1)))
    (is (= () (kg/eids-by-attr s :a)))
    (is (nil? (kg/db-snapshot s)))
    (is (nil? (kg/reset-conn! s)))
    (is (nil? (kg/close! s)))))

(deftest kg-store-schema-rejects-non-stores-test
  (is (not (m/validate kg/KGStore {})))
  (is (not (m/validate kg/KGStore nil)))
  (is (m/validate kg/KGStore (->FullStore))))
