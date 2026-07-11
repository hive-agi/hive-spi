(ns hive-spi.schema.typed-test
  (:require [clojure.test :refer [deftest testing is]]
            [hive-spi.schema.registry :as reg]
            [hive-spi.schema.typed :as typed]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(deftest scalars->types
  (is (= 'typed.clojure/Str (typed/schema->type :string)))
  (is (= 'typed.clojure/AnyInteger (typed/schema->type :int)))
  (is (= 'typed.clojure/Bool (typed/schema->type :boolean)))
  (is (= 'typed.clojure/Kw (typed/schema->type :qualified-keyword)))
  (is (= 'typed.clojure/UUID (typed/schema->type :uuid))))

(deftest maybe->nilable
  (is (= '(typed.clojure/Nilable typed.clojure/Str)
         (typed/schema->type [:maybe :string]))))

(deftest enum->union-of-vals
  (is (= '(typed.clojure/U (typed.clojure/Val "clj") (typed.clojure/Val "cljs"))
         (typed/schema->type [:enum "clj" "cljs"]))))

(deftest map->hmap
  (testing "required-only -> :mandatory"
    (is (= '(typed.clojure/HMap :mandatory {:a typed.clojure/Str})
           (typed/schema->type [:map [:a :string]]))))
  (testing "optional field -> :optional"
    (is (= '(typed.clojure/HMap :optional {:a typed.clojure/Str})
           (typed/schema->type [:map [:a {:optional true} :string]])))))

(deftest result-sum->union-of-hmaps
  (testing ":hive/result validator-type is the Ok|Err union"
    (is (= '(typed.clojure/U
             (typed.clojure/HMap :mandatory {:ok typed.clojure/Any})
             (typed.clojure/HMap :mandatory {:error typed.clojure/Kw}))
           (typed/schema->type :hive/result))))
  (testing ":and narrows to first member (known-error-category -> Kw)"
    (is (= 'typed.clojure/Kw (typed/schema->type :hive/known-error-category)))))

(deftest registry-refs-deref
  (testing "named :hive/* refs resolve through the registry"
    (is (= 'typed.clojure/Kw (typed/schema->type :hive/error-category)))))

(deftest op-fn-and-form-emitters
  (testing "op-fn-type: [ArgT :-> ResultT] (a vector — checker fn-type syntax)"
    (let [ft (typed/op-fn-type [:map [:x :int]])]
      (is (vector? ft))
      (is (= '(typed.clojure/HMap :mandatory {:x typed.clojure/AnyInteger}) (first ft)))
      (is (= :-> (second ft)))
      (is (= (typed/schema->type :hive/result) (nth ft 2)))))
  (testing "ann-form emits (t/ann sym [ArgT :-> ResultT])"
    (is (= '(typed.clojure/ann my/handler
              [(typed.clojure/HMap :mandatory {:x typed.clojure/AnyInteger})
               :-> (typed.clojure/U (typed.clojure/HMap :mandatory {:ok typed.clojure/Any})
                                    (typed.clojure/HMap :mandatory {:error typed.clojure/Kw}))])
           (typed/ann-form 'my/handler [:map [:x :int]]))))
  (testing "defalias-form"
    (is (= '(typed.clojure/defalias Foo typed.clojure/Str)
           (typed/defalias-form 'Foo :string))))
  (testing "=>-form bridges to malli.core/=>"
    (is (= '(malli.core/=> f [:=> :hive/ok :hive/result])
           (typed/=>-form 'f :hive/ok)))))

(deftest total-over-registry
  (testing "every registered schema yields a non-nil type — no unmapped nodes"
    (let [types (typed/registered-types)]
      (is (= (count (reg/registered)) (count types)))
      (is (empty? (keep (fn [[k t]] (when (nil? t) k)) types))))))
