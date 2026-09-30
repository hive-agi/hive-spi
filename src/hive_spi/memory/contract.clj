(ns hive-spi.memory.contract
  "Return contracts of the IMemoryStore write verbs, as malli schemas, and
   the classifier a caller reads any write's outcome through.

   Every write answers one of four outcomes:

     :landed   the write is durable now
                 add-entry!        the id string
                 update-entry!     the merged entry map, carrying its :id
                 update-metadata!  the merged entry map, carrying its :id
                 delete-entry!     true
     :absent   update-entry! / update-metadata! of an unknown id: nil
     :queued   accepted for replay, not yet durable: a map with :queued? true
     :failed   refused: a map with :success? false, or with a non-nil :error

   hive-spi.memory.conformance holds every registered store to these shapes."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; Outcome predicates
;;; ============================================================================

(defn failure?
  "True when X is a write that was refused: a map whose :success? is false
   or whose :error is non-nil."
  [x]
  (and (map? x)
       (or (false? (:success? x))
           (some? (:error x)))))

(defn queued?
  "True when X acknowledges a write accepted for replay: a map with
   :queued? true that is not a failure."
  [x]
  (and (map? x) (true? (:queued? x)) (not (failure? x))))

(defn entry?
  "True when X is an entry map: it carries a string :id and is neither a
   failure nor a queue acknowledgement."
  [x]
  (and (map? x)
       (string? (:id x))
       (not (failure? x))
       (not (queued? x))))

;;; ============================================================================
;;; Schemas
;;; ============================================================================

(def Id
  "An entry id."
  [:string {:min 1}])

(def Entry
  "An entry map as a write returns it."
  [:and [:map [:id Id]] [:fn {:error/message "a failure or queue ack, not an entry"} entry?]])

(def Failure
  "A refused write."
  [:and :map [:fn {:error/message "not a failure map"} failure?]])

(def Queued
  "A write accepted for replay."
  [:and :map [:fn {:error/message "not a queue acknowledgement"} queued?]])

(def AddResult
  "add-entry!: the id on success."
  [:or Id Queued Failure])

(def UpdateResult
  "update-entry! and update-metadata!: the merged entry on success, nil for
   an unknown id."
  [:or :nil Entry Queued Failure])

(def DeleteResult
  "delete-entry!: true on success, an unknown id included."
  [:or [:= true] Queued Failure])

(def write-results
  "Write verb -> the schema its return value must satisfy."
  {:add-entry!       AddResult
   :update-entry!    UpdateResult
   :update-metadata! UpdateResult
   :delete-entry!    DeleteResult})

;;; ============================================================================
;;; Classification
;;; ============================================================================

(defn outcome
  "The outcome of write VERB (a key of `write-results`) that answered R:
   :landed, :absent, :queued or :failed. A value outside VERB's contract
   answers :violation."
  [verb r]
  (let [schema (or (write-results verb)
                   (throw (ex-info (str "Unknown write verb: " verb)
                                   {:verb verb :known (keys write-results)})))]
    (cond
      (not (m/validate schema r)) :violation
      (failure? r)                :failed
      (queued? r)                 :queued
      (nil? r)                    :absent
      :else                       :landed)))

(m/=> outcome [:=> [:cat [:enum :add-entry! :update-entry! :update-metadata! :delete-entry!] :any]
               [:enum :landed :absent :queued :failed :violation]])

(defn landed?
  "True when write VERB answered R and the write is durable now."
  [verb r]
  (= :landed (outcome verb r)))

(defn explain
  "Why R breaks write VERB's contract, as a malli explanation trimmed to
   :path :in :value :type per error; nil when it holds."
  [verb r]
  (some-> (m/explain (write-results verb) r)
          (update :errors (partial mapv #(select-keys % [:path :in :value :type])))))
