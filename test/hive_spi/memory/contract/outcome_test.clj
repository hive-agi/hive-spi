(ns hive-spi.memory.contract.outcome-test
  "The write-outcome classifier, driven by its contract: every verb's
   answer lands in exactly one outcome, and the predicates agree with it."
  (:require [hive-schemas.test :as hst]
            [hive-spi.memory.contract :as c]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(def ^:private Answer
  [:or :nil :boolean [:string {:min 1 :max 4}]
   [:map [:id {:optional true} [:string {:min 1 :max 4}]]
    [:queued? {:optional true} :boolean]
    [:success? {:optional true} :boolean]
    [:error {:optional true} [:maybe :keyword]]]])

(hst/deftrifecta-from-schema failure?-reads-success-and-error
  hive-spi.memory.contract/failure?
  {:in  [:cat Answer]
   :out :boolean
   :rel (fn [[x] out]
          (= out (boolean (and (map? x) (or (false? (:success? x)) (some? (:error x)))))))})

(hst/deftrifecta-from-schema entry?-excludes-failures-and-acks
  hive-spi.memory.contract/entry?
  {:in  [:cat Answer]
   :out :boolean
   :rel (fn [[x] out]
          (= out (boolean (and (map? x) (string? (:id x))
                               (not (c/failure? x)) (not (c/queued? x))))))})

(hst/deftrifecta-from-schema landed?-is-the-landed-outcome
  hive-spi.memory.contract/landed?
  {:in  [:cat c/WriteVerb Answer]
   :out :boolean
   :rel (fn [[verb r] out] (= out (= :landed (c/outcome verb r))))})

(deftest-golden outcome-table
  "test/golden/hive_spi/memory/contract_outcome.edn"
  (into (sorted-map)
        (for [verb [:add-entry! :update-entry! :delete-entry!]
              [k r] {:id "x" :nil nil :true true :entry {:id "x"}
                     :queued {:queued? true} :failed {:success? false}}]
          [[verb k] {:outcome (c/outcome verb r)
                     :landed? (c/landed? verb r)
                     :explain (some-> (c/explain verb r) :errors count)}])))
