(ns hive-spi.working-memory.ports-test
  "The entry schema generates and validates: a generated WorkingEntry passes,
   and the schema rejects the shapes the contract forbids (a missing owner, a
   blank claim, an unknown status, a revision that is not a nat-int)."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.memory.ids :as ids]
            [hive-spi.working-memory.ports :as ports]
            [malli.core :as m]
            [malli.generator :as mg]))

;; SPDX-License-Identifier: MIT

(defn- base []
  (let [t (ids/iso-timestamp)]
    {:id (ids/generate-id) :workspace-id "w" :key "k" :claim "c"
     :owner {:agent "a"} :status :draft :revision 0
     :created t :updated t}))

(deftest generated-entries-validate
  (dotimes [i 25]
    (let [e (mg/generate ports/WorkingEntry {:seed (+ 42 i) :size 10})]
      (is (m/validate ports/WorkingEntry e) (pr-str (m/explain ports/WorkingEntry e)))))
  (is (some? (mg/generator ports/WorkingEntry))))

(deftest sample-statuses-are-the-four
  (is (= #{:draft :working :settled :abandoned}
         (set (mg/sample ports/Status {:size 20})))))

(deftest valid-entry-accepts-and-refuses
  (is (true? (ports/valid-entry? (base))))
  (testing "a missing owner is refused (an entry nobody owns)"
    (is (false? (ports/valid-entry? (dissoc (base) :owner)))))
  (testing "a blank claim is refused"
    (is (false? (ports/valid-entry? (assoc (base) :claim "")))))
  (testing "an unknown status is refused"
    (is (false? (ports/valid-entry? (assoc (base) :status :done)))))
  (testing "a negative revision is refused"
    (is (false? (ports/valid-entry? (assoc (base) :revision -1)))))
  (testing "a closed map rejects an extra key"
    (is (false? (ports/valid-entry? (assoc (base) :zzz 1)))))
  (testing "an optional field may be present"
    (is (true? (ports/valid-entry? (assoc (base) :sha "deadbeef" :branch "feat/x"
                                          :supersedes (ids/generate-id)))))))

(deftest refusal-is-an-ex-info-naming-keys
  (let [e (ports/refusal :wm/revision-conflict "conflict" {:key "k"})]
    (is (= :wm/revision-conflict (:error (ex-data e))))
    (is (= "k" (:key (ex-data e))))
    (is (re-find #"working-memory:" (ex-message e)))))

(deftest error-keywords-are-the-published-set
  (is (every? #(m/validate ports/ErrorKeyword %)
              [:wm/invalid-entry :wm/invalid-transition :wm/terminal
               :wm/revision-conflict :wm/not-found :wm/store-unavailable
               :wm/no-consolidator :wm/no-activator])))
