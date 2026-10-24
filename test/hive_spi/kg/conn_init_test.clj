(ns hive-spi.kg.conn-init-test
  "atom-conn-init: open-fn runs at most once, even under contention; snapshot
   reads without opening; clear! returns to uninitialized so the next
   open-once! opens again."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [hive-spi.kg.conn-init :as ci]))

;; SPDX-License-Identifier: MIT

(deftest both-arities-return-a-conn-init-test
  (is (m/validate ci/ConnInit (ci/atom-conn-init)))
  (is (m/validate ci/ConnInit (ci/atom-conn-init (atom nil))))
  (is (not (m/validate ci/ConnInit {})))
  (is (not (m/validate ci/StateAtom {:not :an-atom}))))

(deftest lifecycle-test
  (let [calls (atom 0)
        open  (fn [] (swap! calls inc) {:conn @calls})
        c     (ci/atom-conn-init)]
    (testing "uninitialized reads nil and opens nothing"
      (is (nil? (ci/snapshot c)))
      (is (zero? @calls)))
    (testing "the first open runs open-fn, later opens return the cached value"
      (is (= {:conn 1} (ci/open-once! c open)))
      (is (= {:conn 1} (ci/open-once! c open)))
      (is (= 1 @calls))
      (is (= {:conn 1} (ci/snapshot c))))
    (testing "clear! resets, and the next open runs open-fn again"
      (ci/clear! c)
      (is (nil? (ci/snapshot c)))
      (is (= {:conn 2} (ci/open-once! c open)))
      (is (= 2 @calls)))))

(deftest caller-supplied-atom-is-the-state-test
  (let [state (atom :pre-opened)
        c     (ci/atom-conn-init state)]
    (is (= :pre-opened (ci/open-once! c #(throw (ex-info "must not open" {})))))
    (ci/clear! c)
    (is (nil? @state))
    (ci/open-once! c (constantly :fresh))
    (is (= :fresh @state))))

(defspec open-fn-runs-at-most-once-under-contention 20
  (prop/for-all [n (gen/choose 2 16)]
    (let [calls (atom 0)
          c     (ci/atom-conn-init)
          latch (promise)
          futs  (doall (repeatedly n #(future @latch
                                              (ci/open-once! c (fn []
                                                                 (Thread/sleep 1)
                                                                 (swap! calls inc))))))]
      (deliver latch true)
      (let [results (mapv deref futs)]
        (and (= 1 @calls)
             (apply = results))))))
