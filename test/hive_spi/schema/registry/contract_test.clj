(ns hive-spi.schema.registry.contract-test
  "The hive registry wrappers driven by their contracts: validate agrees with
   explain, register-all! round-trips through registered."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.schema.registry :as reg]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f] (try (f) (finally (reg/deregister-all! [:covf/a :covf/b])))))

(hst/deftrifecta-from-schema validate-agrees-with-explain
  hive-spi.schema.registry/validate
  {:in  [:cat reg/SchemaRef [:or :int :string [:map [:ok :int]] [:map [:error :qualified-keyword]]]]
   :out :boolean
   :rel (fn [[s x] out] (= out (nil? (reg/explain s x))))})

(hst/deftrifecta-from-schema register-all!-answers-its-keys
  hive-spi.schema.registry/register-all!
  {:in  [:cat [:map-of [:enum :covf/a :covf/b] [:enum :int :string :keyword]]]
   :out [:vector :qualified-keyword]
   :rel (fn [[m] out] (and (= (set out) (set (keys m)))
                           (every? #(= (m %) (get (reg/registered) %)) out)))})

(deftest-golden seeded-result-schemas
  "test/golden/hive_spi/schema/registry_contract.edn"
  {:result   (mapv #(reg/validate :hive/result %)
                   [{:ok 1} {:error :io/x} {:ok 1 :error :io/x} {} 3 {:error "x"}])
   :ok       (reg/validate :hive/ok {:ok nil})
   :register (reg/register! :covf/a [:int {:min 2}])
   :uses-ref (mapv #(reg/validate [:vector :covf/a] %) [[2 3] [1]])
   :removed  (reg/deregister-all! #{:covf/a})
   :explain  (some-> (reg/explain :hive/err {:error 1}) :errors count)})
