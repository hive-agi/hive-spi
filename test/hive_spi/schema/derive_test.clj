(ns hive-spi.schema.derive-test
  (:require [clojure.test :refer [deftest testing is]]
            [hive-spi.schema.registry :as reg]
            [hive-spi.schema.derive :as der]
            [hive-dsl.result :as r]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def args-schema
  [:map {:closed false}
   [:query :string]
   [:limit {:optional true} [:int {:min 1}]]
   [:scope {:optional true} :keyword]])

(deftest input-schema-is-mcp-ready
  (testing "root is a bare object (no top-level $ref), required is derived"
    (let [js (der/input-schema args-schema)]
      (is (= "object" (:type js)))
      (is (nil? (:$ref js)))
      (is (= #{:query :limit :scope} (set (keys (:properties js)))))
      (is (= [:query] (:required js))))))

(deftest coercion
  (let [op (der/compile-op args-schema)]
    (testing "JSON scalars coerce to EDN (string->int, string->keyword)"
      (is (= {:query "m" :limit 9 :scope :hive}
             ((:coerce op) {:query "m" :limit "9" :scope "hive"}))))
    (testing "coerce throws fail-loud on non-conformance"
      (is (= :schema/invalid
             (try ((:coerce op) {:limit 5}) :no-throw
                  (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))))

(deftest coerce-to-result
  (let [op (der/compile-op args-schema)]
    (testing "ok branch returns hive Result ok"
      (let [res ((:coerce->result op) {:query "x"})]
        (is (r/ok? res))
        (is (= {:query "x"} (:ok res)))))
    (testing "err branch bridges to dsl :parse/schema-violation category"
      (let [res ((:coerce->result op) {:limit 5})]
        (is (r/err? res))
        (is (= :parse/schema-violation (:error res)))
        (is (contains? (:explanation res) :query))))))

(deftest resolves-registry-refs
  (testing "derivation resolves :hive/* named refs through the registry"
    (reg/register! :test/carrier [:map [:v :hive/result]])
    (let [op (der/compile-op :test/carrier)]
      (is ((:validate op) {:v {:ok 1}}))
      (is (not ((:validate op) {:v {:nope 1}}))))))

(deftest bundle-includes-typed-clojure-type
  (testing ":type is the Typed Clojure validator-type of the schema"
    (let [op (der/compile-op args-schema)]
      (is (contains? op :type))
      (is (= '(typed.clojure/HMap
               :mandatory {:query typed.clojure/Str}
               :optional {:limit typed.clojure/AnyInteger :scope typed.clojure/Kw})
             (:type op)))))
  (testing ":type resolves registry refs (Result sum) like the other artifacts"
    (is (= '(typed.clojure/U
             (typed.clojure/HMap :mandatory {:ok typed.clojure/Any})
             (typed.clojure/HMap :mandatory {:error typed.clojure/Kw}))
           (:type (der/compile-op :hive/result))))))
