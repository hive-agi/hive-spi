(ns hive-spi.kg.factory-test
  "Backend resolution: registration, construction, and the absent-artifact path."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-spi.kg.factory :as factory]
            [hive-spi.kg.protocol :as kg]))

(defrecord StubStore [opts]
  kg/IKGStore
  (ensure-conn! [_] :stub-conn)
  (transact! [_ _] nil)
  (query [_ _] nil)
  (query [_ _ _] nil)
  (entity [_ _] nil)
  (entid [_ _] nil)
  (pull-entity [_ _ _] nil)
  (eids-by-attr [_ _] nil)
  (db-snapshot [_] nil)
  (reset-conn! [_] nil)
  (close! [_] nil))

(defn- with-backend
  "Register `backend` for the duration of (f), then restore the method table."
  [backend construct f]
  (try
    (defmethod factory/backend->store backend [_ opts] (construct opts))
    (f)
    (finally
      (remove-method factory/backend->store backend))))

(deftest make-store-returns-the-constructed-store
  (with-backend ::stub ->StubStore
    (fn []
      (let [res (factory/make-store ::stub {:db-path "/tmp/irrelevant"})]
        (testing "construction succeeds and opts reach the backend"
          (is (r/ok? res))
          (is (satisfies? kg/IKGStore (:ok res)))
          (is (= {:db-path "/tmp/irrelevant"} (:opts (:ok res))))))
      (testing "opts default to {} in the 1-arity form"
        (is (= {} (:opts (:ok (factory/make-store ::stub)))))))))

(deftest an-unregistered-backend-is-an-error-not-an-exception
  (let [res (factory/make-store ::never-registered)]
    (is (r/err? res))
    (is (= :kg.factory/backend-unavailable (:error res)))
    (is (= ::never-registered (:backend res)))))

(deftest a-backend-whose-artifact-is-absent-is-an-error
  (testing "a registered backend that cannot construct reports unavailable"
    (with-backend ::absent (constantly nil)
      (fn []
        (let [res (factory/make-store ::absent)]
          (is (r/err? res))
          (is (= :kg.factory/backend-unavailable (:error res))))))))

(deftest resolve-sym-is-nil-for-an-absent-namespace
  (testing "an absent artifact is a configuration fact, never a throw"
    (is (nil? (factory/resolve-sym 'no.such.artifact.kg.store/create-store)))))

(deftest the-shipped-backends-are-registered
  (testing "datahike and datalevin resolve without being required here"
    (is (contains? (factory/supported-backends) :datahike))
    (is (contains? (factory/supported-backends) :datalevin)))
  (testing ":default is a fallback, not an advertised backend"
    (is (not (contains? (factory/supported-backends) :default)))))

(deftest the-late-bound-factory-delegates-to-make-store
  (with-backend ::stub ->StubStore
    (fn []
      (let [res (factory/create (factory/late-bound-factory) ::stub {:db-path "x"})]
        (is (r/ok? res))
        (is (= {:db-path "x"} (:opts (:ok res))))))))
