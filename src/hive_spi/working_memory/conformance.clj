(ns hive-spi.working-memory.conformance
  "Conformance suite for hive-spi.working-memory.ports/IWorkingMemoryStore.

   The suite is data: `cases` is a vector of {:id :doc :run}, each :run a
   (fn [ctx]) that asserts with clojure.test/is. Two projections run it:

     (defconformance my-store make-store)
       emits one deftest per case, named my-store-<case-id>.

     (run-conformance make-store)
       runs every case inside the current clojure.test context and returns
       {:ran [...] :skipped {...}}.

   MAKE-STORE is a zero-arg fn answering a FRESH, empty store.

   The cases pin the three things the port exists for: a per-key REVISION a
   compare-and-set is judged against, a delta that RE-DELIVERS an edited key
   (which a timestamp or append-only cursor would drop), and workspace
   isolation. The test-only reference implementation
   (test/hive_spi/working_memory/reference_store.clj) passes it."
  (:require [clojure.test :as t :refer [is testing]]
            [hive-spi.memory.ids :as ids]
            [hive-spi.working-memory.entry :as entry]
            [hive-spi.working-memory.ports :as ports]
            [hive-spi.working-memory.lifecycle :as lifecycle]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ============================================================================
;;; Helpers
;;; ============================================================================

(defn sample-entry
  "A conformant WorkingEntry for WORKSPACE-ID / KEY with CLAIM, owned by
   OWNER (default {:agent \"conformance\"}), at STATUS (default :draft), with
   no :revision (the store stamps it). Pure."
  ([workspace-id key claim]
   (sample-entry workspace-id key claim {}))
  ([workspace-id key claim {:keys [owner status sha branch supersedes provenance]}]
   (let [t (ids/iso-timestamp)]
     (cond-> {:id (ids/generate-id)
              :workspace-id workspace-id
              :key key
              :claim claim
              :owner (or owner {:agent "conformance"})
              :status (or status :draft)
              :revision 0
              :created t
              :updated t}
       sha (assoc :sha sha)
       branch (assoc :branch branch)
       supersedes (assoc :supersedes supersedes)
       provenance (assoc :provenance provenance)))))

(defn- stored
  "The entry a successful put answered, or a failed assertion."
  [r]
  (is (true? (:ok? r)) (pr-str r))
  (:entry r))

;;; ============================================================================
;;; Cases
;;; ============================================================================

(def cases
  [{:id :create-stamps-revision-one
    :doc "put-cas! with a nil expected revision creates the key at revision 1"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 e (sample-entry "w" "k" "first")
                 r (ports/put-cas! s e nil)
                 got (stored r)]
             (is (= 1 (:revision got)))
             (is (m/validate ports/WorkingEntry got) (pr-str (m/explain ports/WorkingEntry got)))
             (is (= (:id e) (:id got)) "the entry id is preserved across a revision")))}

   {:id :cas-bumps-revision
    :doc "put-cas! with the current revision succeeds and bumps the revision"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r1 (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 rev1 (:revision (stored r1))
                 r2 (ports/put-cas! s (sample-entry "w" "k" "two") rev1)]
             (is (true? (:ok? r2)))
             (is (= (inc rev1) (:revision (:entry r2))))))}

   {:id :cas-conflict-refuses
    :doc "put-cas! with a stale revision refuses :wm/revision-conflict and names the winner"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 ;; a second writer read revision nil (key must not exist) but it does
                 r (ports/put-cas! s (sample-entry "w" "k" "sneak") nil)]
             (is (false? (:ok? r)))
             (is (= :wm/revision-conflict (:error r)))
             (is (= "one" (:claim (:current r))) "the winner is reported for reconciliation")))}

   {:id :cas-create-on-existing-refuses
    :doc "put-cas! with a nil expected revision on an existing key refuses"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 r (ports/put-cas! s (sample-entry "w" "k" "again") nil)]
             (is (= :wm/revision-conflict (:error r)))))}

   {:id :get-round-trips
    :doc "get-entry answers the entry put-cas! stored, at its revision"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r (ports/put-cas! s (sample-entry "w" "k" "hello") nil)
                 got (ports/get-entry s "w" "k")]
             (is (= (:entry r) got))))}

   {:id :absent-key-is-nil
    :doc "get-entry on an absent key answers nil, never a throw"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)]
             (is (nil? (ports/get-entry s "w" "nope")))))}

   {:id :invalid-entry-refused
    :doc "put-cas! throws :wm/invalid-entry for a malformed entry"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 bad {:workspace-id "w" :key "k" :claim "" :status :draft}] ; no owner, blank claim
             (is (= :wm/invalid-entry
                    (try (ports/put-cas! s bad nil) nil
                         (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))}

   {:id :watch-fires-on-write
    :doc "watch! calls back with the stored entry on the next write"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 seen (atom nil)
                 cancel (ports/watch! s "w" "k" (fn [e] (reset! seen e)))]
             (try
               (is (fn? cancel) "watch! answers a cancel fn")
               (ports/put-cas! s (sample-entry "w" "k" "watched") nil)
               (is (some? @seen) "the watch fired")
               (is (= "watched" (:claim @seen)))
               (is (m/validate ports/WorkingEntry @seen))
               (finally (cancel)))))}

   {:id :watch-cancel-stops-it
    :doc "calling the cancel fn stops the watch"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 n (atom 0)
                 cancel (ports/watch! s "w" "k" (fn [_] (swap! n inc)))]
             (ports/put-cas! s (sample-entry "w" "k" "a") nil)
             (cancel)
             (ports/put-cas! s (sample-entry "w" "k" "b") 1)
             (is (= 1 @n) "only the write before the cancel fired")))}

   {:id :delta-empty-when-caught-up
    :doc "delta-since with a cursor holding every current revision answers no entries"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 d1 (ports/delta-since s "w" nil)
                 d2 (ports/delta-since s "w" (:cursor d1))]
             (is (= 1 (count (:entries d1))))
             (is (empty? (:entries d2)))))}

   {:id :delta-delivers-new-key
    :doc "a key absent from the cursor is delivered in full"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "a" "one") nil)
                 d1 (ports/delta-since s "w" nil)
                 _ (ports/put-cas! s (sample-entry "w" "b" "two") nil)
                 d2 (ports/delta-since s "w" (:cursor d1))]
             (is (= ["b"] (mapv :key (:entries d2))))))}

   {:id :delta-re-delivers-an-edited-key
    :doc "an EDITED key is re-delivered, which an append-only cursor would miss"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 d1 (ports/delta-since s "w" nil)
                 _ (ports/put-cas! s (sample-entry "w" "k" "two") 1)
                 d2 (ports/delta-since s "w" (:cursor d1))]
             (is (= ["k"] (mapv :key (:entries d2)))
                 "the mutated key re-delivers because its revision advanced")
             (is (= "two" (:claim (first (:entries d2)))))))}

   {:id :delta-cursor-carries-forward
    :doc "the answered cursor advances so a caught-up reader stays caught up"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 d1 (ports/delta-since s "w" nil)]
             (is (= 1 (get (:cursor d1) "k")))
             (is (empty? (:entries (ports/delta-since s "w" (:cursor d1)))))))}

   {:id :workspaces-are-isolated
    :doc "delta-since and get-entry are scoped to one workspace"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w1" "k" "in-w1") nil)
                 _ (ports/put-cas! s (sample-entry "w2" "k" "in-w2") nil)]
             (is (= "in-w1" (:claim (ports/get-entry s "w1" "k"))))
             (is (= "in-w2" (:claim (ports/get-entry s "w2" "k"))))
             (is (= ["in-w1"] (mapv :claim (:entries (ports/delta-since s "w1" nil)))))
             (is (= ["in-w2"] (mapv :claim (:entries (ports/delta-since s "w2" nil)))))))}

   {:id :terminal-status-immutable
    :doc "a settled entry cannot move again through the lifecycle table"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 e (sample-entry "w" "k" "done" {:status :working})
                 _ (ports/put-cas! s e nil)
                 settled (lifecycle/settle (ports/get-entry s "w" "k"))
                 r (ports/put-cas! s settled 1)]
             (is (true? (:ok? r)))
             (is (= :settled (:status (:entry r))))
             (is (empty? (lifecycle/allowed-transitions (:entry r)))
                 "a terminal entry has no outgoing transition")))}

   {:id :concurrent-writers-one-winner
    :doc "of two writers with the same expected revision, exactly one wins"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "k" "base") nil)
                 r1 (ports/put-cas! s (sample-entry "w" "k" "a") 1)
                 r2 (ports/put-cas! s (sample-entry "w" "k" "b") 1)]
             (is (= 1 (count (filter :ok? [r1 r2]))) "exactly one write succeeded")
             (is (= :wm/revision-conflict (:error (first (remove :ok? [r1 r2])))))))}])

(def case-ids
  "The case ids, in run order."
  (mapv :id cases))

;;; ============================================================================
;;; Runners
;;; ============================================================================

(defn- case-by-id [id]
  (or (some #(when (= id (:id %)) %) cases)
      (throw (ex-info (str "Unknown working-memory conformance case: " id)
                      {:id id :known case-ids}))))

(defn skip-reason
  "Why case C must not run, or nil. The working-memory suite has no optional
   cases yet, so this only reads an explicit [:skip id]. Kept so a host may
   waive one case by name. Pure."
  [{:keys [id]} opts]
  (get-in opts [:skip id]))

(defn run-case
  "Run case ID against MAKE-STORE inside the current clojure.test context.
   Answers :ran, or {:skipped id :reason ..}."
  [make-store opts id]
  (let [{:keys [run doc] :as c} (case-by-id id)]
    (if-let [reason (skip-reason c opts)]
      {:skipped id :reason reason}
      (do (testing (str (name id) ": " doc)
            (run {:make-store make-store}))
          :ran))))

(defn run-conformance
  "Run every case against MAKE-STORE. Answers {:ran [ids] :skipped {id reason}}."
  [make-store opts]
  (reduce (fn [acc id]
            (let [r (run-case make-store opts id)]
              (if (= :ran r)
                (update acc :ran conj id)
                (assoc-in acc [:skipped id] (:reason r)))))
          {:ran [] :skipped {}}
          case-ids))

(defmacro defconformance
  "Emit one clojure.test deftest per case, named PREFIX-<case-id>, each
   running that case against MAKE-STORE."
  [prefix make-store opts]
  `(do ~@(for [id case-ids]
           `(t/deftest ~(symbol (str (name prefix) "-" (name id)))
              (run-case ~make-store ~opts ~id)))))

;;; ============================================================================
;;; Contracts
;;; ============================================================================

(def CaseId
  "A working-memory conformance case id."
  (into [:enum] case-ids))

(def MakeStore
  "A zero-arg fn answering a fresh IWorkingMemoryStore."
  [:fn {:error/message "must be a zero-arg fn"} ifn?])

(def RunOpts
  "The OPTS the runners read."
  [:map
   [:skip {:optional true} [:maybe [:map-of :keyword :string]]]])

(m/=> sample-entry [:function
                    [:=> [:cat ports/WorkspaceId ports/EntryKey :string] [:map]]
                    [:=> [:cat ports/WorkspaceId ports/EntryKey :string :map] [:map]]])
(m/=> skip-reason [:=> [:cat [:map [:id CaseId]] [:maybe :map]] [:maybe :string]])
(m/=> run-case [:=> [:cat MakeStore RunOpts CaseId]
                [:or [:= :ran] [:map [:skipped CaseId] [:reason :string]]]])
(m/=> run-conformance [:=> [:cat MakeStore RunOpts]
                       [:map [:ran [:vector CaseId]] [:skipped [:map-of CaseId :string]]]])
