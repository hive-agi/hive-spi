(ns hive-spi.memory.decorate.chain-test
  "The decorator chain as data: order, replacement by :id, and the
   embed-text capability read, driven by the contracts."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.memory.decorate :as d]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each (fn [f] (d/reset-chain!) (try (f) (finally (d/reset-chain!)))))

(hst/deftrifecta-from-schema embed-text-capable?-reads-capabilities
  hive-spi.memory.decorate/embed-text-capable?
  {:in  [:cat [:map [:capabilities [:vector [:enum :embed-text "embed-text" :scan :batch]]]]]
   :out :boolean
   :rel (fn [[status] out]
          (= out (boolean (some #{:embed-text "embed-text"} (:capabilities status)))))})

(hst/deftrifecta-from-schema add!-keys-the-chain-by-id
  hive-spi.memory.decorate/add!
  {:in  [:cat d/Decorator]
   :out d/Chain
   :rel (fn [[dec] out] (= (:order dec) (get-in out [(:id dec) :order])))})

(defn- tagging [tag] (fn [_k s] (conj s tag)))

(deftest-golden chain-order
  "test/golden/hive_spi/memory/decorate_chain.edn"
  (do (d/add! {:id :z :order 2 :decorate (tagging :z)})
      (d/add! {:id :a :order 2 :decorate (tagging :a)})
      (d/add! {:id :m :order 1 :decorate (tagging :m)})
      (let [first-order (mapv :id (d/chain))
            built       (d/apply-chain :k [])]
        (d/add! {:id :m :order 3 :decorate (tagging :m2)})
        {:order      first-order
         :built      built
         :replaced   (d/apply-chain :k [])
         :removed    (vec (sort (keys (d/remove! :a))))
         :plain-base (d/base-store {:not :a-decorator})
         :decorator? [(d/decorator? []) (d/decorator? nil)]
         :reset      (d/reset-chain!)
         :empty      (vec (d/chain))})))
