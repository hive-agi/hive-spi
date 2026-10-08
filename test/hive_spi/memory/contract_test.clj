(ns hive-spi.memory.contract-test
  (:require [clojure.test :refer [deftest is testing are]]
            [hive-spi.memory.contract :as contract]))

(def ^:private entry {:id "e1" :type :note :content "c"})
(def ^:private queued {:success? true :queued? true :depth 1})
(def ^:private circuit-open {:success? false :error :circuit-open})
(def ^:private pipeline-err {:error :boundary/upsert-failed :id "e1" :detail {}})

(deftest outcome-per-verb
  (are [verb r expected] (= expected (contract/outcome verb r))
    :add-entry!       "e1"          :landed
    :add-entry!       queued        :queued
    :add-entry!       circuit-open  :failed
    :add-entry!       entry         :violation
    :add-entry!       nil           :violation

    :update-entry!    entry         :landed
    :update-entry!    nil           :absent
    :update-entry!    queued        :queued
    :update-entry!    pipeline-err  :failed
    :update-entry!    "e1"          :violation
    :update-entry!    true          :violation
    :update-entry!    {:content "no id"} :violation

    :update-metadata! entry         :landed
    :update-metadata! "e1"          :violation

    :delete-entry!    true          :landed
    :delete-entry!    circuit-open  :failed
    :delete-entry!    "e1"          :violation
    :delete-entry!    {:success? true :id "e1"} :violation))

(deftest a-failure-carrying-an-id-is-not-an-entry
  (testing "an :error map with an :id is a failure, never a landed update"
    (is (not (contract/entry? pipeline-err)))
    (is (not (contract/landed? :update-entry! pipeline-err)))))

(deftest explain-names-the-break
  (is (nil? (contract/explain :update-entry! entry)))
  (is (seq (:errors (contract/explain :update-entry! "e1")))))

(deftest unknown-verb-throws
  (is (thrown? clojure.lang.ExceptionInfo (contract/outcome :upsert! "e1"))))
