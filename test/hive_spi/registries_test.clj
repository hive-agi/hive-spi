(ns hive-spi.registries-test
  "The memory and lifecycle registries are the runtime seams a host uses to
   inject implementations, so a registry that accepts a non-conforming impl
   or mis-orders shutdown hooks fails at exactly the wrong moment."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-spi.lifecycle.ports :as lports]
            [hive-spi.lifecycle.registry :as lreg]
            [hive-spi.memory.ids :as ids]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.registry :as mreg]))

;; =============================================================================
;; Doubles
;; =============================================================================

(defrecord FakeStore [state]
  ports/IMemoryStore
  (connect! [_ config] (swap! state assoc :config config :connected true) {:ok true})
  (disconnect! [_] (swap! state assoc :connected false) nil)
  (connected? [_] (boolean (:connected @state)))
  (health-check [_] {:status :ok})
  (add-entry! [_ e] (swap! state update :entries (fnil conj []) e) e)
  (get-entry [_ id] (first (filter #(= id (:id %)) (:entries @state))))
  (update-entry! [_ _id _updates] nil)
  (delete-entry! [_ _id] nil)
  (query-entries [_ _opts] (:entries @state))
  (search-similar [_ _query-text _opts] [])
  (supports-semantic-search? [_] false)
  (cleanup-expired! [_] 0)
  (entries-expiring-soon [_ _days _opts] [])
  (find-duplicate [_ _type _content-hash _opts] nil)
  (store-status [_] {:entries (count (:entries @state))})
  (reset-store! [_] (reset! state {}) nil))

(defn- fake-store [] (->FakeStore (atom {})))

(defrecord FakeHook [priority nm log]
  lports/IShutdownHook
  (shutdown-priority [_] priority)
  (shutdown-name [_] nm)
  (shutdown! [_ _] (swap! log conj nm) nil))

(defrecord FakeSweeper [nm]
  lports/ISweepable
  (sweep-interval-s [_] 30)
  (sweep-name [_] nm)
  (sweep! [_ _] {:swept 0 :errors []}))

(defrecord FakeOwner [id]
  lports/IResourceOwner
  (owner-id [_] id)
  (owned-resources [_] {:channels 0})
  (release-all! [_] nil))

(use-fixtures :each (fn [f] (mreg/reset-registry!) (lreg/reset-all!) (f)
                      (mreg/reset-registry!) (lreg/reset-all!)))

;; =============================================================================
;; Memory registry
;; =============================================================================

(deftest set-store-installs-under-default-test
  (let [s (fake-store)]
    (is (identical? s (mreg/set-store! s)))
    (is (identical? s (mreg/get-store)))
    (is (true? (mreg/store-set?)))))

(deftest an-empty-registry-has-no-default-store-test
  (is (false? (mreg/store-set?)) "store-set? never throws")
  (is (thrown? clojure.lang.ExceptionInfo (mreg/get-store))
      "reaching for an unregistered default is a wiring bug, not a nil")
  (is (thrown? clojure.lang.ExceptionInfo (mreg/get-store :never-registered))))

(deftest the-absent-store-error-names-what-is-available-test
  (mreg/register-store! :a (fake-store))
  (let [data (try (mreg/get-store :missing) (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :missing (:store-key data)))
    (is (= [:a] (:available data)) "the message points at what IS registered")))

(deftest a-non-store-is-rejected-test
  (is (thrown? AssertionError (mreg/register-store! :bad {:not "a store"})))
  (is (false? (mreg/store-set?))))

(deftest stores-register-under-independent-keys-test
  (let [a (fake-store) b (fake-store)]
    (mreg/register-store! :a a)
    (mreg/register-store! :b b)
    (is (= #{:a :b} (set (keys (mreg/registered-stores)))))
    (mreg/unregister-store! :a)
    (is (= #{:b} (set (keys (mreg/registered-stores)))))))

(deftest reset-active-store-disconnects-then-unregisters-test
  (let [s (fake-store)]
    (mreg/set-store! s)
    (ports/connect! s {})
    (ports/add-entry! s {:id "1"})
    (mreg/reset-active-store!)
    (is (false? (ports/connected? s)) "the store was disconnected")
    (is (= 1 (:entries (ports/store-status s))) "its data was left intact")
    (is (false? (mreg/store-set?)) "and it is no longer the default")))

(deftest active-store-helpers-are-nil-without-a-store-test
  (is (nil? (mreg/active-store-healthy?)))
  (is (nil? (mreg/active-store-status))))

(deftest active-store-helpers-delegate-to-the-installed-store-test
  (mreg/set-store! (fake-store))
  (is (= {:status :ok} (mreg/active-store-healthy?)))
  (is (= {:entries 0} (mreg/active-store-status))))

;; =============================================================================
;; Lifecycle registry
;; =============================================================================

(deftest shutdown-hooks-come-back-in-priority-order-test
  (let [log (atom [])]
    (lreg/register-shutdown! :late (->FakeHook 400 "late" log))
    (lreg/register-shutdown! :early (->FakeHook 0 "early" log))
    (lreg/register-shutdown! :mid (->FakeHook 200 "mid" log))
    (is (= ["early" "mid" "late"]
           (mapv lports/shutdown-name (lreg/registered-shutdown-hooks))))))

(deftest each-lifecycle-registry-validates-its-own-protocol-test
  (let [log (atom [])]
    (is (thrown? AssertionError (lreg/register-shutdown! :x (->FakeSweeper "s"))))
    (is (thrown? AssertionError (lreg/register-sweep! :x (->FakeHook 0 "h" log))))
    (is (thrown? AssertionError (lreg/register-resource-owner! :x (->FakeSweeper "s"))))))

(deftest snapshot-and-restore-round-trip-test
  (let [log (atom [])]
    (lreg/register-shutdown! :h (->FakeHook 100 "h" log))
    (lreg/register-sweep! :s (->FakeSweeper "s"))
    (lreg/register-resource-owner! :o (->FakeOwner "o"))
    (let [snap (lreg/registry-snapshot)]
      (lreg/reset-all!)
      (is (empty? (lreg/registered-shutdown-hooks)))
      (is (empty? (lreg/registered-sweeps)))
      (is (empty? (lreg/registered-resource-owners)))
      (lreg/restore-all! snap)
      (is (= ["h"] (mapv lports/shutdown-name (lreg/registered-shutdown-hooks))))
      (is (= [:s] (vec (keys (lreg/registered-sweeps)))))
      (is (= "o" (lports/owner-id (lreg/get-resource-owner :o)))))))

(deftest restore-from-an-empty-snapshot-clears-everything-test
  (let [log (atom [])]
    (lreg/register-shutdown! :h (->FakeHook 1 "h" log))
    (lreg/restore-all! {})
    (is (empty? (lreg/registered-shutdown-hooks)))))

;; =============================================================================
;; Identity helpers
;; =============================================================================

(deftest content-hash-ignores-insignificant-whitespace-test
  (is (= (ids/content-hash "a b")
         (ids/content-hash "  a   b  ")
         (ids/content-hash "a\t\tb")))
  (is (not= (ids/content-hash "a b") (ids/content-hash "a c"))))

(deftest content-hash-accepts-non-strings-test
  (is (= (ids/content-hash {:a 1}) (ids/content-hash {:a 1})))
  (is (= 64 (count (ids/content-hash [1 2 3]))) "SHA-256 renders as 64 hex chars"))

(deftest generate-id-is-timestamped-and-unique-test
  (let [ids (repeatedly 200 ids/generate-id)]
    (is (every? #(re-matches #"\d{14}-[0-9a-f]{8}" %) ids))
    (is (> (count (distinct ids)) 190) "collisions must be rare")))

(deftest iso-timestamp-parses-back-test
  (is (some? (java.time.ZonedDateTime/parse (ids/iso-timestamp)))))
