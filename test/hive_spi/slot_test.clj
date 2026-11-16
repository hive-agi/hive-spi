(ns hive-spi.slot-test
  "Slots are the injection point for every port, so a slot that accepts an
   invalid implementation or loses one silently breaks dependency inversion
   for the whole ecosystem."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.slot :as slot]
            [hive-schemas.test :as hst]
            [hive-test.golden :refer [deftest-golden]]))

;; =============================================================================
;; single-slot
;; =============================================================================

(deftest install-returns-the-implementation-test
  (let [s (slot/single-slot {})]
    (is (= :impl (slot/install! s :impl)))
    (is (= :impl (slot/current s)))
    (is (true? (slot/present? s)))))

(deftest an-empty-slot-is-not-present-test
  (let [s (slot/single-slot {})]
    (is (false? (slot/present? s)))
    (is (nil? (slot/current s)))))

(deftest on-empty-supplies-a-value-without-making-the-slot-present-test
  (let [s (slot/single-slot {:on-empty (constantly :fallback)})]
    (is (= :fallback (slot/current s)))
    (is (false? (slot/present? s))
        "the empty-policy value must not read as an installed implementation")))

(deftest validate-rejects-a-bad-implementation-test
  (let [s (slot/single-slot {:validate keyword?})]
    (is (thrown? AssertionError (slot/install! s "not-a-keyword")))
    (is (false? (slot/present? s)) "a rejected install leaves the slot empty")))

(deftest clear-runs-teardown-once-and-empties-the-slot-test
  (let [torn (atom [])
        s (slot/single-slot {:teardown #(swap! torn conj %)})]
    (slot/install! s :impl)
    (is (nil? (slot/clear! s)))
    (is (= [:impl] @torn))
    (is (false? (slot/present? s)))
    (slot/clear! s)
    (is (= [:impl] @torn) "clearing an empty slot does not re-run teardown")))

(deftest a-throwing-teardown-still-clears-the-slot-test
  (let [s (slot/single-slot {:teardown (fn [_] (throw (ex-info "boom" {})))})]
    (slot/install! s :impl)
    (is (nil? (slot/clear! s)))
    (is (false? (slot/present? s)))))

(deftest initial-seeds-the-slot-test
  (let [s (slot/single-slot {:initial :seeded})]
    (is (= :seeded (slot/current s)))
    (is (true? (slot/present? s)))))

(deftest watch-fires-on-transition-test
  (let [seen (atom [])
        s (slot/single-slot {})]
    (slot/watch-slot! s ::k (fn [old new] (swap! seen conj [old new])))
    (slot/install! s :a)
    (slot/install! s :b)
    (slot/unwatch-slot! s ::k)
    (slot/install! s :c)
    (is (= [[nil :a] [:a :b]] @seen)
        "no event after unwatch")))

;; =============================================================================
;; multi-slot
;; =============================================================================

(deftest put-get-remove-round-trip-test
  (let [r (slot/multi-slot {})]
    (is (= :impl (slot/reg-put! r :k :impl)))
    (is (= :impl (slot/reg-get r :k)))
    (is (= {:k :impl} (slot/reg-snapshot r)))
    (is (nil? (slot/reg-remove! r :k)))
    (is (nil? (slot/reg-get r :k)))))

(deftest on-missing-receives-key-and-snapshot-test
  (let [seen (atom nil)
        r (slot/multi-slot {:on-missing (fn [k snap] (reset! seen [k snap]) :fallback)})]
    (slot/reg-put! r :present :x)
    (is (= :fallback (slot/reg-get r :absent)))
    (is (= [:absent {:present :x}] @seen))))

(deftest merge-validates-every-value-test
  (let [r (slot/multi-slot {:validate keyword?})]
    (is (thrown? AssertionError (slot/reg-merge! r {:a :ok :b "bad"})))
    (is (= [:a :b] (vec (slot/reg-merge! r {:a :ok :b :also-ok}))))
    (is (= {:a :ok :b :also-ok} (slot/reg-snapshot r)))))

(deftest clear-empties-the-registry-test
  (let [r (slot/multi-slot {:initial {:a 1 :b 2}})]
    (is (= {:a 1 :b 2} (slot/reg-snapshot r)))
    (is (nil? (slot/reg-clear! r)))
    (is (= {} (slot/reg-snapshot r)))))

(deftest removing-an-absent-key-is-a-no-op-test
  (let [r (slot/multi-slot {:initial {:a 1}})]
    (is (nil? (slot/reg-remove! r :missing)))
    (is (= {:a 1} (slot/reg-snapshot r)))))

;; =============================================================================
;; The property that makes a slot a DIP seam
;; =============================================================================

(deftest a-slot-swaps-implementations-without-consumer-changes-test
  (testing "the consumer reads `current`; installing a new impl changes the answer"
    (let [s (slot/single-slot {})
          consumer #(slot/current s)]
      (slot/install! s {:name :first})
      (is (= :first (:name (consumer))))
      (slot/install! s {:name :second})
      (is (= :second (:name (consumer)))))))

;; =============================================================================
;; Trifectas over the constructor contracts (cov-fleet)
;; =============================================================================
;; No :golden-path on these two: the output is a record holding an atom, which
;; does not print as readable EDN. The golden below snapshots its projection.

(hst/deftrifecta-from-schema multi-slot-contract
  hive-spi.slot/multi-slot
  {:in  [:cat slot/MultiSlotConfig]
   :out slot/Slot
   :rel (fn [[cfg] out]
          (and (= (or (:initial cfg) {}) (slot/reg-snapshot out))
               (= (:validate cfg) (:validate out))))
   :num-tests 200})

(hst/deftrifecta-from-schema single-slot-contract
  hive-spi.slot/single-slot
  {:in  [:cat slot/SingleSlotConfig]
   :out slot/Slot
   :rel (fn [[cfg] out]
          (and (= (some? (:initial cfg)) (slot/present? out))
               (= (:teardown cfg) (:teardown out))))
   :num-tests 200})

(deftest-golden slot-lifecycle-golden
  "test/golden/hive_spi/slot/lifecycle.edn"
  (let [single (slot/single-slot {:on-empty (constantly :fallback)})
        multi  (slot/multi-slot {:initial {:a 1} :on-missing (fn [k _] [:missing k])})
        seen   (atom [])]
    (slot/watch-slot! single ::w (fn [o n] (swap! seen conj [o n])))
    {:single [(slot/current single)
              (slot/present? single)
              (slot/install! single :impl)
              (slot/current single)
              (slot/clear! single)
              (slot/current single)]
     :watch  (do (slot/unwatch-slot! single ::w) @seen)
     :multi  [(slot/reg-get multi :a)
              (slot/reg-get multi :b)
              (vec (sort (slot/reg-merge! multi {:b 2 :c 3})))
              (slot/reg-snapshot multi)
              (slot/reg-remove! multi :a)
              (slot/reg-snapshot multi)]}))
