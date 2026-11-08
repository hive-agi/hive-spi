(ns hive-spi.registry-contract-test
  "Contract facets for the memory and lifecycle registries (cov2): every
   public fn carries an m/=> contract, and the registries honour those
   contracts under malli instrumentation — puts return the impl, removes and
   resets return nil, snapshots are {key -> impl} maps of the right protocol,
   and a snapshot restores to an equal registry."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-spi.lifecycle.ports :as lports]
            [hive-spi.lifecycle.registry :as lreg]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.registry :as mreg]
            [malli.core :as m]
            [malli.instrument :as mi]))

;; SPDX-License-Identifier: MIT

(defrecord Store [state]
  ports/IMemoryStore
  (connect! [_ config] (swap! state assoc :connected true :config config) {:success? true})
  (disconnect! [_] (swap! state assoc :connected false) nil)
  (connected? [_] (boolean (:connected @state)))
  (health-check [_] {:healthy? true})
  (add-entry! [_ e] (:id e))
  (get-entry [_ _id] nil)
  (update-entry! [_ _id _updates] nil)
  (delete-entry! [_ _id] nil)
  (query-entries [_ _opts] [])
  (search-similar [_ _q _opts] [])
  (supports-semantic-search? [_] false)
  (cleanup-expired! [_] 0)
  (entries-expiring-soon [_ _days _opts] [])
  (find-duplicate [_ _type _hash _opts] nil)
  (store-status [_] {:backend "contract-test"})
  (reset-store! [_] true))

(defrecord Hook [priority nm]
  lports/IShutdownHook
  (shutdown-priority [_] priority)
  (shutdown-name [_] nm)
  (shutdown! [_ _] nil))

(defrecord Sweeper [nm]
  lports/ISweepable
  (sweep-interval-s [_] 30)
  (sweep-name [_] nm)
  (sweep! [_ _] {:swept 0 :errors []}))

(defrecord Owner [id]
  lports/IResourceOwner
  (owner-id [_] id)
  (owned-resources [_] {})
  (release-all! [_] nil))

(def ^:private nses ['hive-spi.memory.registry 'hive-spi.lifecycle.registry])

(defn- instrumented [f]
  (mi/instrument! {:filters [(apply mi/-filter-ns nses)]})
  (try (f) (finally (mi/unstrument! {:filters [(apply mi/-filter-ns nses)]}))))

(use-fixtures :each
  (fn [f]
    (mreg/reset-registry!) (lreg/reset-all!)
    (try (instrumented f)
         (finally (mreg/reset-registry!) (lreg/reset-all!)))))

(deftest every-public-registry-fn-has-a-contract
  (doseq [ns-sym nses
          :let [fns (->> (ns-publics ns-sym) vals
                         (filter #(fn? @%))
                         (map #(-> % meta :name)))
                contracted (set (keys (get (m/function-schemas) ns-sym)))]
          f fns]
    (is (contains? contracted f) (str ns-sym "/" f " has no m/=> contract"))))

;;; ---------------------------------------------------------------------------
;;; Memory registry
;;; ---------------------------------------------------------------------------

(deftest memory-registry-honours-its-contracts
  (let [s (->Store (atom {}))]
    (testing "an empty registry"
      (is (false? (mreg/store-set?)))
      (is (nil? (mreg/active-store-healthy?)))
      (is (nil? (mreg/active-store-status)))
      (is (nil? (mreg/reset-active-store!)))
      (is (thrown? clojure.lang.ExceptionInfo (mreg/get-store))))
    (testing "set / get / status"
      (is (identical? s (mreg/set-store! s)))
      (is (identical? s (mreg/get-store)))
      (is (identical? s (mreg/get-store :default)))
      (is (true? (mreg/store-set?)))
      (is (= {:success? true} (mreg/connect-active-store! {:x 1})))
      (is (= {:healthy? true} (mreg/active-store-healthy?)))
      (is (= {:backend "contract-test"} (mreg/active-store-status))))
    (testing "a non-store is refused at the contract boundary"
      (is (thrown? Exception (mreg/register-store! :bad {:not :a-store}))))
    (testing "keyed stores and removal"
      (is (identical? s (mreg/register-store! :other s)))
      (is (= #{:default :other} (set (keys (mreg/registered-stores)))))
      (is (nil? (mreg/unregister-store! :other)))
      (is (nil? (mreg/unregister-store! :never-there))))
    (testing "decorators"
      (let [seen (atom [])]
        (is (nil? (mreg/add-decorator! {:id ::spy :order 0
                                        :decorate (fn [k st] (swap! seen conj k) st)})))
        (is (= [:default] @seen))
        (is (nil? (mreg/redecorate!)))
        (is (nil? (mreg/remove-decorator! ::spy)))
        (is (thrown? Exception (mreg/add-decorator! {:id "not-a-keyword"})))))
    (testing "reset"
      (is (nil? (mreg/reset-active-store!)))
      (is (false? (ports/connected? s)))
      (is (nil? (mreg/reset-registry!)))
      (is (empty? (mreg/registered-stores))))))

;;; ---------------------------------------------------------------------------
;;; Lifecycle registry
;;; ---------------------------------------------------------------------------

(deftest lifecycle-registry-honours-its-contracts
  (let [h (->Hook 10 "h") sw (->Sweeper "s") o (->Owner "o")]
    (is (identical? h (lreg/register-shutdown! :h h)))
    (is (identical? sw (lreg/register-sweep! :s sw)))
    (is (identical? o (lreg/register-resource-owner! :o o)))
    (is (= [h] (lreg/registered-shutdown-hooks)))
    (is (= {:s sw} (lreg/registered-sweeps)))
    (is (= {:o o} (lreg/registered-resource-owners)))
    (is (identical? o (lreg/get-resource-owner :o)))
    (is (nil? (lreg/get-resource-owner :absent)))
    (testing "the wrong protocol is refused at the contract boundary"
      (is (thrown? Exception (lreg/register-shutdown! :x sw)))
      (is (thrown? Exception (lreg/register-sweep! :x o)))
      (is (thrown? Exception (lreg/register-resource-owner! :x h))))
    (is (nil? (lreg/unregister-shutdown! :h)))
    (is (nil? (lreg/unregister-sweep! :s)))
    (is (nil? (lreg/unregister-resource-owner! :o)))
    (is (nil? (lreg/restore-all! nil)))
    (is (nil? (lreg/reset-all!)))))

(def ^:private gen-key (gen/elements [:a :b :c :d :e :f]))

(defspec shutdown-hooks-come-back-sorted-and-snapshots-restore 50
  (prop/for-all [hooks (gen/map gen-key (gen/choose 0 500))
                 sweeps (gen/set gen-key)
                 owners (gen/set gen-key)]
                (instrumented
                 (fn []
                   (lreg/reset-all!)
                   (doseq [[k p] hooks] (lreg/register-shutdown! k (->Hook p (name k))))
                   (doseq [k sweeps] (lreg/register-sweep! k (->Sweeper (name k))))
                   (doseq [k owners] (lreg/register-resource-owner! k (->Owner (name k))))
                   (let [ordered (mapv lports/shutdown-priority (lreg/registered-shutdown-hooks))
                         snap    (lreg/registry-snapshot)]
                     (lreg/reset-all!)
                     (lreg/restore-all! snap)
                     (and (= ordered (sort (vals hooks)))
                          (= snap (lreg/registry-snapshot))
                          (= (set sweeps) (set (keys (lreg/registered-sweeps))))
                          (= (set owners) (set (keys (lreg/registered-resource-owners))))))))))
