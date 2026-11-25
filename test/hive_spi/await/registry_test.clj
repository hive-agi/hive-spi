(ns hive-spi.await.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-spi.await.catalog :as catalog]
            [hive-spi.await.ports :as ports]
            [hive-spi.await.registry :as reg]
            [hive-spi.await.stub :as stub]
            [malli.instrument :as mi]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (reg/reset-registry!)
    (mi/instrument! {:filters [(mi/-filter-ns 'hive-spi.await.registry 'hive-spi.await.catalog)]})
    (try (f)
         (finally (mi/unstrument! {:filters [(mi/-filter-ns 'hive-spi.await.registry
                                                            'hive-spi.await.catalog)]})
                  (reg/reset-registry!)))))

(defn- err [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest sources-register-under-their-descriptor-type
  (let [s (stub/make-source)]
    (is (identical? s (reg/register-source! s)))
    (is (identical? s (reg/source :stub-probe)))
    (is (= [:stub-probe] (reg/source-types)))
    (is (= :stub-probe (:type (get (reg/descriptors) :stub-probe))))
    (is (nil? (reg/unregister-source! :stub-probe)))
    (is (nil? (reg/source :stub-probe)) "an unregistered type resolves to nil")
    (is (nil? (reg/unregister-source! :stub-probe)) "unregister is idempotent")))

(deftest schemes-register-under-their-name
  (let [s (stub/make-scheme {"a" "b"})]
    (reg/register-scheme! s)
    (is (identical? s (reg/scheme "test")))
    (is (= ["test"] (reg/scheme-names)))
    (reg/unregister-scheme! "test")
    (is (= [] (reg/scheme-names)))))

(deftest register-checks-the-port-protocol
  (testing "a scheme is not a source, nor a map either"
    (is (= :await/invalid-provider
           (:error (err #(reg/register! :source (stub/make-scheme {}))))))
    (is (= :await/invalid-provider (:error (err #(reg/key-of :scheme {}))))))
  (testing "an unknown port is refused, naming the known ones"
    (let [e (err #(reg/register! :nope (stub/make-source)))]
      (is (some? e))))
  (testing "a malformed descriptor is a wiring error"
    (let [bad (reify ports/IProbeSource
                (descriptor [_] {:type "not-a-keyword"})
                (parse-spec [_ _ _] {})
                (probe! [_ _ _] {})
                (judge [_ _ _] {:match? false}))]
      (is (= :await/invalid-provider (:error (err #(reg/key-of :source bad)))))))
  (testing "a malformed scheme name is a wiring error"
    (is (= :await/invalid-provider
           (:error (err #(reg/key-of :scheme (stub/make-scheme "Bad Name" {})))))))
  (is (= [] (reg/source-types)) "nothing was installed by a refused register"))

(deftest catalog-names-the-addon-or-the-known-types
  (let [e (catalog/missing-source :sql-predicate [:stub-probe])]
    (is (= :await/source-missing (:error (ex-data e))))
    (is (= "hive.await-sql" (:addon (ex-data e))))
    (is (re-find #"needs addon hive\.await-sql" (ex-message e))))
  (let [e (catalog/missing-source :no-such [:stub-probe])]
    (is (nil? (:addon (ex-data e))))
    (is (re-find #"known: :stub-probe" (ex-message e))))
  (let [e (catalog/missing-scheme "vault" [])]
    (is (= :await/scheme-missing (:error (ex-data e))))
    (is (= "hive.cloak" (:addon (ex-data e)))))
  (is (re-find #"known: none" (ex-message (catalog/missing-scheme "zz" [])))))

(deftest unknown-port-on-unregister
  (is (= :await/unknown-port (:error (err #(reg/unregister! :nope :x))))))
