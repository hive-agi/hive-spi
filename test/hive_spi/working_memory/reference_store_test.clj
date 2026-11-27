(ns hive-spi.working-memory.reference-store-test
  "The test-only reference store is held to the whole conformance suite, and
   the suite is shown to catch a store that drops an EDITED key from its
   delta (the append-only-cursor defect)."
  (:require [clojure.test :refer [deftest is]]
            [hive-spi.working-memory.conformance :as conformance]
            [hive-spi.working-memory.entry :as entry]
            [hive-spi.working-memory.ports :as ports]
            [hive-spi.working-memory.reference-store :as rs]))

;; SPDX-License-Identifier: MIT

(conformance/defconformance reference-store rs/make-store {})

(deftest run-conformance-runs-every-case
  (let [r (conformance/run-conformance rs/make-store {})]
    (is (= conformance/case-ids (:ran r)))
    (is (empty? (:skipped r)))))

(deftest run-conformance-reports-skips-by-name
  (let [r (conformance/run-conformance rs/make-store
                                       {:skip {:watch-fires-on-write "exercised elsewhere"}})]
    (is (= "exercised elsewhere" (get-in r [:skipped :watch-fires-on-write])))
    (is (not (some #{:watch-fires-on-write} (:ran r))))))

;; A store that answers its delta from an append-only log: a key delivered once
;; is never delivered again, so an EDITED key is missed. The defect the
;; re-delivery case exists to catch.
(defrecord AppendOnlyStore [state]
  ports/IWorkingMemoryStore
  (get-entry [_ w k] (get-in @state [(entry/workspace-key w k)]))
  (put-cas! [_ e expected]
    (let [n (entry/normalize e)
          ck (entry/workspace-key (:workspace-id n) (:key n))
          cur (get-in @state [ck :entry])]
      (if (entry/cas-ok? cur expected)
        (let [stored (entry/stamp n cur)]
          (swap! state assoc ck {:entry stored :first-revision (or (:first-revision cur) 1)})
          {:ok? true :entry stored})
        {:ok? false :error :wm/revision-conflict :current cur})))
  (watch! [_ _ _ _] (fn [] nil))
  (delta-since [_ w cursor _opts]
    ;; BUG: reports each key at its FIRST revision forever, so an edit never
    ;; advances the cursor and the reader is never re-sent the key.
    (let [rows (for [[[ws _] {:keys [entry first-revision]}] @state :when (= ws w)]
                 (assoc entry :revision first-revision))]
      {:workspace-id w
       :entries (vec (filter #(entry/changed-since? % (get (or cursor {}) (:key %))) rows))
       :cursor (into (or cursor {}) (map (fn [e] [(:key e) (:revision e)])) rows)})))

(deftest the-suite-catches-an-append-only-cursor
  (let [failures (atom [])]
    (with-redefs [clojure.test/do-report
                  (fn [m] (when (#{:fail :error} (:type m)) (swap! failures conj m)))]
      (conformance/run-case (fn [] (->AppendOnlyStore (atom {}))) {}
                            :delta-re-delivers-an-edited-key))
    (is (seq @failures) "a store that never re-delivers an edited key fails the case")))

(deftest reference-store-is-a-store
  (is (rs/store? (rs/make-store)))
  (is (satisfies? ports/IWorkingMemoryStore (rs/make-store))))
