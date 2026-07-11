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

(deftest function-schemas
  (testing ":=> -> [ArgT.. :-> RetT] vector"
    (is (= '[typed.clojure/Str typed.clojure/AnyInteger :-> typed.clojure/Bool]
           (typed/schema->type [:=> [:cat :string :int] :boolean]))))
  (testing ":function multi-arity -> t/IFn of arity vectors"
    (is (= '(typed.clojure/IFn [typed.clojure/AnyInteger :-> typed.clojure/AnyInteger]
                               [typed.clojure/AnyInteger typed.clojure/AnyInteger :-> typed.clojure/AnyInteger])
           (typed/schema->type [:function
                                [:=> [:cat :int] :int]
                                [:=> [:cat :int :int] :int]])))))

(deftest parser-type-mode
  (testing "parser-type wraps in (t/U (t/Val :malli.core/invalid) inner)"
    (is (= '(typed.clojure/U (typed.clojure/Val :malli.core/invalid) typed.clojure/Str)
           (typed/schema->parser-type :string))))
  (testing ":orn tags branches only in parser mode"
    (is (= '(typed.clojure/U typed.clojure/AnyInteger typed.clojure/Str)
           (typed/schema->type [:orn [:i :int] [:s :string]])))
    (is (= '(typed.clojure/U
             (typed.clojure/Val :malli.core/invalid)
             (typed.clojure/U (quote [(typed.clojure/Val :i) typed.clojure/AnyInteger])
                              (quote [(typed.clojure/Val :s) typed.clojure/Str])))
           (typed/schema->parser-type [:orn [:i :int] [:s :string]]))))
  (testing ":* is Seqable (validator) but Vec (parser)"
    (is (= '(typed.clojure/Seqable typed.clojure/Str) (typed/schema->type [:* :string])))
    (is (= '(typed.clojure/U (typed.clojure/Val :malli.core/invalid)
                             (typed.clojure/Vec typed.clojure/Str))
           (typed/schema->parser-type [:* :string])))))

(deftest numeric-refinement-widening
  (testing "comparator schemas widen to t/Num (bounds not expressible)"
    (is (= 'typed.clojure/Num (typed/schema->type [:> 0])))
    (is (= 'typed.clojure/Num (typed/schema->type [:< 1])))
    (is (= 'typed.clojure/Num (typed/schema->type [:>= 0])))
    (is (= 'typed.clojure/Num (typed/schema->type [:<= 1]))))
  (testing ":min/:max props on :int drop to the base integer type"
    (is (= 'typed.clojure/AnyInteger (typed/schema->type [:int {:min 1}])))
    (is (= 'typed.clojure/AnyInteger (typed/schema->type [:int {:min 1 :max 10}]))))
  (testing ":and narrows to first member (int refined by a bound -> AnyInteger)"
    (is (= 'typed.clojure/AnyInteger (typed/schema->type [:and :int [:> 0]]))))
  (testing "integer-guaranteeing predicates -> AnyInteger"
    (is (= 'typed.clojure/AnyInteger (typed/schema->type 'pos-int?)))
    (is (= 'typed.clojure/AnyInteger (typed/schema->type 'nat-int?)))
    (is (= 'typed.clojure/AnyInteger (typed/schema->type 'neg-int?))))
  (testing "non-integer numeric predicates -> t/Num"
    (is (= 'typed.clojure/Num (typed/schema->type 'pos?)))
    (is (= 'typed.clojure/Num (typed/schema->type 'zero?)))
    (is (= 'typed.clojure/Num (typed/schema->type 'number?))))
  (testing ":float -> (t/U Double Float); :double -> Double"
    (is (= '(typed.clojure/U Double Float) (typed/schema->type :float)))
    (is (= 'Double (typed/schema->type :double)))))