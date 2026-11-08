(ns hive-spi.memory.seal-readiness-test
  "seal-readiness answers, as data, whether the seal layer can wrap a store;
   the opt-in :seal-ready conformance case FAILS a store that is not ready,
   where the plain :embed-text and :scan cases pass it vacuously."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [hive-spi.memory.conformance :as conformance]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.stub :as stub]))

;; SPDX-License-Identifier: MIT

(defn- bare-store
  "A store that implements only what the :seal-ready case calls, declaring
   CAPABILITIES and satisfying IMemoryStoreScan when SCAN? is true."
  [capabilities scan?]
  (if scan?
    (reify
      ports/IMemoryStore
      (store-status [_] {:backend "bare" :capabilities capabilities})
      (reset-store! [_] true)
      ports/IMemoryStoreScan
      (scan-ids [_ _opts] []))
    (reify
      ports/IMemoryStore
      (store-status [_] {:backend "bare" :capabilities capabilities})
      (reset-store! [_] true))))

(defn- reported
  "Run F with clojure.test reporting captured; answers the :pass, :fail and
   :error events it raised, so a case that must fail can be asserted on
   without failing this suite."
  [f]
  (let [events (atom [])]
    (binding [t/report               #(swap! events conj %)
              t/*report-counters*    (ref t/*initial-report-counters*)
              t/*testing-contexts*   (list)]
      (f))
    (group-by :type @events)))

;;; ---- seal-readiness ------------------------------------------------------

(deftest reference-stub-is-seal-ready
  (is (= {:seal-ready? true :missing #{} :reasons {}}
         (conformance/seal-readiness (stub/create-store {})))))

(deftest store-declaring-nothing-misses-both
  (let [r (conformance/seal-readiness (bare-store [] false))]
    (is (false? (:seal-ready? r)))
    (is (= #{:embed-text :scan} (:missing r)))
    (is (= #{:embed-text :scan} (set (keys (:reasons r)))))
    (is (every? string? (vals (:reasons r))))))

(deftest each-requirement-is-reported-alone
  (testing "capability declared, no scan"
    (is (= #{:scan} (:missing (conformance/seal-readiness (bare-store [:embed-text] false))))))
  (testing "scan, capability not declared"
    (is (= #{:embed-text} (:missing (conformance/seal-readiness (bare-store [] true))))))
  (testing "capability named as a string still counts"
    (is (:seal-ready? (conformance/seal-readiness (bare-store ["embed-text"] true))))))

(deftest two-arity-reads-the-status-given
  (testing "a status in hand overrides what the store would report"
    (is (= #{:embed-text}
           (:missing (conformance/seal-readiness (stub/create-store {}) {:capabilities []}))))))

(deftest report-covers-every-named-store
  (let [r (conformance/seal-readiness-report {"stub" (stub/create-store {})
                                              "bare" (bare-store [] false)})]
    (is (= ["bare" "stub"] (keys r)))
    (is (true? (get-in r ["stub" :seal-ready?])))
    (is (= #{:embed-text :scan} (get-in r ["bare" :missing])))))

;;; ---- the opt-in conformance case -----------------------------------------

(deftest seal-ready-case-is-opt-in
  (let [r (conformance/run-conformance #(bare-store [] false)
                                       {:skip (zipmap (remove #{:seal-ready} conformance/case-ids)
                                                      (repeat "bare store"))})]
    (is (contains? (:skipped r) :seal-ready) "skipped without {:seal-ready? true}")
    (is (empty? (:ran r)))))

(deftest seal-ready-case-fails-a-store-that-declares-nothing
  (let [ev (reported #(conformance/run-case (fn [] (bare-store [] false))
                                            {:seal-ready? true} :seal-ready))]
    (is (= 1 (count (:fail ev))))
    (is (re-find #"missing #\{:embed-text :scan\}" (str (:message (first (:fail ev))))))
    (is (empty? (:error ev)))))

(deftest seal-ready-case-fails-a-half-ready-store
  (let [ev (reported #(conformance/run-case (fn [] (bare-store [:embed-text] false))
                                            {:seal-ready? true} :seal-ready))]
    (is (= 1 (count (:fail ev))))
    (is (re-find #":scan" (str (:message (first (:fail ev))))))))

(deftest seal-ready-case-passes-a-ready-store
  (let [ev (reported #(conformance/run-case (fn [] (bare-store [:embed-text] true))
                                            {:seal-ready? true} :seal-ready))]
    (is (empty? (:fail ev)))
    (is (empty? (:error ev)))
    (is (= 1 (count (:pass ev))))))

(deftest assert-seal-ready-answers-the-readiness-map
  (let [box (atom nil)
        ev  (reported #(reset! box (conformance/assert-seal-ready (bare-store [] true))))]
    (is (= #{:embed-text} (:missing @box)))
    (is (= 1 (count (:fail ev))))))
