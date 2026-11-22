(ns hive-spi.schema.typed-contract-test
  "tc/ann-op derives the typed rung from the m/=> contract: the projection
   covers every contract hive-spi registers, the macro refuses a var with no
   contract, and it emits nothing unless a type-checking run opts in."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [hive-spi.schema.typed :as typed]
            [hive-spi.schema.typed-contract :as tc]
            [hive-spi.slot]))

;; SPDX-License-Identifier: MIT

(deftest optional-and-variadic-params-become-arities
  (testing "[:? T] forks the arity"
    (is (= '(typed.clojure/IFn [typed.clojure/AnyInteger :-> typed.clojure/Bool]
                               [typed.clojure/AnyInteger typed.clojure/Str :-> typed.clojure/Bool])
           (typed/schema->type [:=> [:cat :int [:? :string]] :boolean]))))
  (testing "a trailing [:* T] is a rest arg"
    (is (= '[typed.clojure/AnyInteger typed.clojure/Str :* :-> typed.clojure/Bool]
           (typed/schema->type [:=> [:cat :int [:* :string]] :boolean]))))
  (testing "a trailing [:+ T] is one T then a rest of T"
    (is (= '[typed.clojure/Str typed.clojure/Str :* :-> typed.clojure/Bool]
           (typed/schema->type [:=> [:cat [:+ :string]] :boolean]))))
  (testing ":function flattens the arities of every :=> it holds"
    (is (= '(typed.clojure/IFn [:-> typed.clojure/Bool]
                               [typed.clojure/AnyInteger :-> typed.clojure/Bool]
                               [typed.clojure/AnyInteger typed.clojure/Str :-> typed.clojure/Bool])
           (typed/schema->type [:function
                                [:=> [:cat] :boolean]
                                [:=> [:cat :int [:? :string]] :boolean]])))))

(deftest contract-ann-form-reads-the-registered-contract
  (is (= (list 'typed.clojure/ann 'hive-spi.slot/single-slot
               (typed/schema->type (typed/contract-schema 'hive-spi.slot/single-slot)))
         (typed/contract-ann-form 'hive-spi.slot/single-slot)))
  (is (nil? (typed/contract-ann-form 'no.such.ns/f)))
  (is (nil? (typed/contract-ann-form 'unqualified))))

(defn- hive-spi-contracts
  "[qsym schema] for every m/=> contract a hive-spi namespace registered."
  []
  (for [[nsym fns] (m/function-schemas)
        :when (.startsWith (str nsym) "hive-spi.")
        [fsym {:keys [schema]}] fns]
    [(symbol (str nsym) (str fsym)) schema]))

(deftest every-hive-spi-contract-projects-to-a-fn-type
  (testing "the projection is total over the contracts it annotates"
    (let [rows (hive-spi-contracts)]
      (is (seq rows))
      (doseq [[qsym _] rows
              :let [[head sym t] (typed/contract-ann-form qsym)]]
        (is (= 'typed.clojure/ann head) (str qsym))
        (is (= qsym sym))
        (is (or (vector? t) (and (seq? t) (= 'typed.clojure/IFn (first t))))
            (str qsym " -> " (pr-str t)))))))

(deftest ann-op-emits-nothing-unless-opted-in
  (is (false? (tc/emit?)))
  (is (nil? (macroexpand '(hive-spi.schema.typed-contract/ann-op hive-spi.slot/single-slot))))
  (is (nil? (macroexpand '(hive-spi.schema.typed-contract/ann-op f [:map [:a :int]])))))

(deftest ann-op-refuses-a-var-without-a-contract
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no m/=> contract is registered"
        (macroexpand '(hive-spi.schema.typed-contract/ann-op no.such.ns/f)))))

(deftest ann-op-emits-the-derived-annotation-when-opted-in
  (with-redefs [tc/emit? (constantly true)]
    (is (= (typed/contract-ann-form 'hive-spi.slot/single-slot)
           (macroexpand-1 '(hive-spi.schema.typed-contract/ann-op hive-spi.slot/single-slot))))))
