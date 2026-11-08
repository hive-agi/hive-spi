(ns hive-spi.catchup.registry.compose-test
  "The catchup block registry, driven by its contracts: blocks run in order,
   a throwing block lands in :failed without aborting the rest."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.catchup.registry :as reg]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f] (let [prior (reg/registered-blocks)]
            (reg/reset-registry!)
            (try (f) (finally (reg/reset-registry!) (run! reg/register-block! prior))))))

(hst/deftrifecta-from-schema register-block!-installs-under-its-id
  hive-spi.catchup.registry/register-block!
  {:in  [:cat reg/GenBlock]
   :out reg/GenBlock
   :rel (fn [[b] out] (and (= b out) (reg/registered? (:block/id b))))})

(hst/deftrifecta-from-schema block?-is-the-block-shape
  hive-spi.catchup.registry/block?
  {:in  [:cat [:or reg/GenBlock [:map [:block/id :string]] :nil]]
   :out :boolean
   :rel (fn [[x] out] (= out (and (map? x) (keyword? (:block/id x)) (fn? (:block/fn x))
                                  (integer? (:block/order x)))))})

(deftest-golden composed-blocks
  "test/golden/hive_spi/catchup/compose.edn"
  (do (reg/register-block! {:block/id :b :block/order 2 :block/fn (fn [ctx] {:dir (:dir ctx)})})
      (reg/register-block! {:block/id :a :block/order 1 :block/fn (fn [_] nil)})
      (reg/register-block! {:block/id :boom :block/order 3 :block/fn (fn [_] (throw (ex-info "kaput" {})))})
      (let [order (mapv :block/id (reg/registered-blocks))
            out   (reg/compose {:dir "/p"})]
        (reg/unregister-block! :boom)
        {:order order :out out :after (mapv :block/id (reg/registered-blocks))})))
