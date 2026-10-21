(ns hive-spi.schema.typed.contract-test
  "Typed Clojure type syntax derived from malli, driven by the contracts:
   parser types wrap validator types, op fn-types are [Arg :-> Result]."
  (:require [hive-schemas.test :as hst]
            [hive-spi.schema.registry :as reg]
            [hive-spi.schema.typed :as typed]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema op-fn-type-pairs-arg-and-result
  hive-spi.schema.typed/op-fn-type
  {:in  [:cat reg/SchemaRef reg/SchemaRef]
   :out typed/FnType
   :rel (fn [[a r] out] (= out [(typed/schema->type a) :-> (typed/schema->type r)]))})

(hst/deftrifecta-from-schema defalias-form-sources-the-validator-type
  hive-spi.schema.typed/defalias-form
  {:in  [:cat [:enum 'A 'B] reg/SchemaRef]
   :out [:cat [:= 'typed.clojure/defalias] :symbol :any]
   :rel (fn [[sym s] out] (= out (list 'typed.clojure/defalias sym (typed/schema->type s))))})

(deftest-golden type-renderings
  "test/golden/hive_spi/schema/typed_contract.edn"
  ;; Type syntax holds lists and quoted vectors: the golden stores their
  ;; printed form so it stays plain canonical EDN.
  (update-vals
   {:scalars (mapv typed/schema->type [:string :int :boolean :keyword [:maybe :int] [:enum :a :b]])
    :map     (typed/schema->type [:map [:a :int] [:b {:optional true} :string]])
    :result  (typed/schema->type :hive/result)
    :parser  (typed/schema->parser-type [:orn [:i :int] [:s :string]])
    :ann     (typed/ann-form 'handler [:map [:q :string]])
    :=>      (typed/=>-form 'handler [:cat :int])
    :types?  (contains? (typed/registered-types) :hive/result)}
   pr-str))
