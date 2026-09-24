(ns hive-spi.memory.decorate-test
  "The registry runs every store through the decorator chain, whatever the
   order stores and decorators arrive in, and never wraps twice."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-spi.memory.decorate :as decorate]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.registry :as registry]
            [hive-spi.memory.stub :as stub]))

(defrecord Tagged [inner tag]
  decorate/IStoreDecorator
  (inner-store [_] inner)

  ports/IMemoryStore
  (store-status [_] (update (ports/store-status inner) :tags (fnil conj []) tag)))

(defn- tagger [id order]
  {:id id :order order :decorate (fn [_ s] (->Tagged s id))})

(defn- tags-of [s] (:tags (ports/store-status s)))

(use-fixtures :each
  (fn [t]
    (registry/reset-registry!)
    (decorate/reset-chain!)
    (try (t)
         (finally
           (registry/reset-registry!)
           (decorate/reset-chain!)))))

(deftest a-registered-store-is-decorated
  (registry/add-decorator! (tagger :a 10))
  (let [base (stub/create-store)]
    (registry/register-store! :default base)
    (is (= [:a] (tags-of (registry/get-store))))
    (is (identical? base (decorate/base-store (registry/get-store))))))

(deftest a-decorator-added-later-reaches-stores-already-registered
  (let [base (stub/create-store)]
    (registry/register-store! :default base)
    (registry/register-store! :other (stub/create-store))
    (registry/add-decorator! (tagger :a 10))
    (is (= [:a] (tags-of (registry/get-store :default))))
    (is (= [:a] (tags-of (registry/get-store :other))))
    (is (identical? base (decorate/base-store (registry/get-store))))))

(deftest the-chain-never-wraps-twice
  (registry/add-decorator! (tagger :a 10))
  (registry/register-store! :default (stub/create-store))
  (testing "re-registering the decorated store, re-adding the decorator, redecorating"
    (registry/register-store! :default (registry/get-store))
    (registry/add-decorator! (tagger :a 10))
    (registry/redecorate!)
    (is (= [:a] (tags-of (registry/get-store))))))

(deftest lower-order-wraps-first
  (registry/add-decorator! (tagger :outer 20))
  (registry/add-decorator! (tagger :inner 10))
  (registry/register-store! :default (stub/create-store))
  (is (= [:inner :outer] (tags-of (registry/get-store))))
  (is (= :outer (:tag (registry/get-store)))))

(deftest removing-a-decorator-unwraps-the-registered-stores
  (registry/add-decorator! (tagger :a 10))
  (registry/register-store! :default (stub/create-store))
  (registry/remove-decorator! :a)
  (is (not (decorate/decorator? (registry/get-store)))))

(deftest a-throwing-decorator-never-installs-a-bare-store
  (let [boom {:id :boom :order 1 :decorate (fn [_ _] (throw (ex-info "no" {})))}]
    (testing "register-store! refuses and leaves the slot empty"
      (decorate/add! boom)
      (is (thrown? clojure.lang.ExceptionInfo
                   (registry/register-store! :default (stub/create-store))))
      (is (not (registry/store-set?))))
    (testing "add-decorator! over a registered store keeps the store it had"
      (decorate/reset-chain!)
      (registry/add-decorator! (tagger :a 10))
      (registry/register-store! :default (stub/create-store))
      (is (thrown? clojure.lang.ExceptionInfo (registry/add-decorator! boom)))
      (is (= [:a] (tags-of (registry/get-store)))))))

(deftest an-invalid-decorator-is-refused
  (is (thrown? clojure.lang.ExceptionInfo (decorate/add! {:id :x :order 1})))
  (is (thrown? clojure.lang.ExceptionInfo (decorate/add! {:id "x" :order 1 :decorate identity}))))

(deftest the-stub-declares-embed-text
  (is (decorate/embed-text-capable? (ports/store-status (stub/create-store)))))
