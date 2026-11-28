(ns hive-spi.working-memory.lifecycle-test
  "The transition table is closed and the write is pure."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [hive-spi.working-memory.entry :as entry]
            [hive-spi.working-memory.lifecycle :as lc]
            [hive-spi.working-memory.ports :as ports]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(def all-statuses (set (m/children ports/Status)))

(deftest the-table-is-closed
  (testing "every status is a key, and only a live status has an outgoing edge"
    (is (= all-statuses (set (keys lc/transitions))))
    (is (every? #(empty? (get lc/transitions %)) lc/terminal-statuses))
    (is (every? #(seq (get lc/transitions %)) lc/live-statuses)))
  (testing "every :to in the table is itself a status with a row"
    (is (every? #(contains? lc/transitions (:to %)) lc/transition-list))))

(deftest terminal-and-live-are-disjoint
  (is (empty? (set/intersection lc/terminal-statuses lc/live-statuses)))
  (is (= all-statuses (set/union lc/terminal-statuses lc/live-statuses))))

(deftest allowed-reads-the-table
  (is (true? (lc/allowed? :draft :working)))
  (is (true? (lc/allowed? :working :settled)))
  (is (false? (lc/allowed? :settled :working)))
  (is (false? (lc/allowed? :abandoned :draft)))
  (is (false? (lc/allowed? :draft :draft)) "a no-op move is not in the table"))

(deftest apply-transition-moves-and-refreshes
  (let [e {:status :draft :updated "2026-01-01T00:00:00Z"}
        moved (lc/apply-transition e :working)]
    (is (= :working (:status moved)))
    (is (not= (:updated e) (:updated moved)) "updated is refreshed")))

(deftest apply-transition-refuses-an-illegal-move
  (let [e {:status :settled :updated "2026-01-01T00:00:00Z"}
        err (try (lc/apply-transition e :working) nil
                 (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
    (is (= :wm/invalid-transition (:error err)))
    (is (= :settled (:status err)))))

(deftest apply-transition-refuses-a-no-op
  (let [e {:status :working :updated "2026-01-01T00:00:00Z"}
        err (try (lc/apply-transition e :working) nil
                 (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
    (is (= :wm/invalid-transition (:error err)))))

(deftest named-moves-are-the-table
  (let [base {:status :draft :updated "2026-01-01T00:00:00Z"}]
    (is (= :working (:status (lc/claim base))))
    (is (= :settled (:status (lc/settle base))))
    (is (= :abandoned (:status (lc/abandon base))))))

(deftest every-legal-move-applies
  (doseq [{:keys [from to]} lc/transition-list]
    (is (= to (:status (lc/apply-transition {:status from :updated "2026-01-01T00:00:00Z"} to)))
        (str from " -> " to))))

(deftest terminal-and-entry-agree
  ;; entry/terminal? re-states the terminal set to stay a leaf; this pins the
  ;; two definitions equal, so a drift between them fails loudly here.
  (doseq [s (m/children ports/Status)]
    (is (= (lc/terminal? s) (entry/terminal? {:status s}))
        (str s " agrees between lifecycle and entry"))))
