(ns hive-spi.schema.derive.contract-test
  "compile-op's bundle driven by its contract: the bundle validator is the
   registry's, coercion turns JSON scalars into EDN, and the Result leg
   reports a violation as data."
  (:require [hive-schemas.test :as hst]
            [hive-spi.schema.derive :as der]
            [hive-spi.schema.registry :as reg]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema compile-op-validates-like-the-registry
  hive-spi.schema.derive/compile-op
  {:in  [:cat reg/SchemaRef]
   :out der/OpBundle
   :rel (fn [[s] b]
          (every? #(= ((:validate b) %) (reg/validate s %))
                  [1 "x" {:ok 1} {:error :a/b} [1 2] nil :a {:qn "q"}]))})

(hst/deftrifecta-from-schema input-schema-is-a-json-schema-root
  hive-spi.schema.derive/input-schema
  {:in  [:cat reg/SchemaRef]
   :out :map
   :rel (fn [_ js] (not (contains? js :$ref)))})

(def ^:private Args [:map [:limit :int] [:mode [:enum :fast :slow]]])

(deftest-golden op-bundle-artifacts
  "test/golden/hive_spi/schema/derive_contract.edn"
  (let [b (der/compile-op Args)]
    {:input-schema (der/input-schema Args)
     :coerced      ((:coerce b) {:limit "30" :mode "fast"})
     :ok           ((:coerce->result b) {:limit "3" :mode "slow"})
     :violation    (:error ((:coerce->result b) {:limit "x" :mode "fast"}))
     :thrown       (try ((:coerce b) {:limit "x"}) (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))
     :type         (:type b)
     :keys         (vec (sort (remove #{:projection-errors} (keys b))))}))
