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
   (test/hive_spi/working_memory/reference_store.clj) passes it.

   What the suite does NOT assume, so a native-KV adapter can pass it:
     - a revision is OPAQUE: strictly increasing per key, not contiguous and
       not starting at 1. Every case reads the revision from the previous
       result and passes it back; none hard-codes 1 or `inc`.
     - a watch is ASYNCHRONOUS: :watch-fires-on-write and
       :watch-cancel-stops-it POLL with a deadline, never assert right after
       the write.
     - contention is REAL: :concurrent-writers-one-winner runs N futures
       behind a latch, so a read-judge-write split across transactions fails
       it.
     - terminal immutability and the transition table are enforced by the
       STORE (:terminal-immutable-refuses, :terminal-supersession-allowed,
       :invalid-transition-refuses), not merely checked on the stored value.
     - a bounded delta (:delta-truncated-then-complete,
       :delta-max-chars-truncates) advances its cursor only over what it
       delivered, so following it to exhaustion delivers every key once."
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

(defn- valid!
  "Assert V validates against SCHEMA. One flat form, so a case body needs no
   nested explain."
  [schema v]
  (is (m/validate schema v) (pr-str (m/explain schema v))))

(defn- poll-until
  "True once PRED is true, polling every 5ms up to a 2s deadline. A watch is
   ASYNCHRONOUS, so a case must never assert synchronously after a write."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 5) (recur))))))

(defn- bounded-delta-keys
  "Every key delivered by repeatedly calling delta-since on S for WS with OPTS
   from a nil cursor until a delta is not truncated, concatenated in order. A
   key delivered twice would appear twice, so the case can assert exactly-once."
  [s ws opts]
  (loop [cursor nil out []]
    (let [d (ports/delta-since s ws cursor opts)
          out (into out (map :key (:entries d)))]
      (if (:truncated? d)
        (recur (:cursor d) out)
        out))))

;;; ============================================================================
;;; Cases
;;; ============================================================================

(def cases
  "Every conformance case, in run order."
  [{:id :create-stamps-a-revision
    :doc "put-cas! with a nil expected revision creates the key, stamps an opaque revision and keeps the writer's id"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 e (sample-entry "w" "k" "first")
                 r (ports/put-cas! s e nil)
                 got (stored r)]
             (is (true? (:ok? r)))
             (is (nat-int? (:revision got)))
             (is (pos? (:revision got)))
             (is (= (:id e) (:id got)) "the key's id is the writer's on create")
             (valid! ports/WorkingEntry got)
             (valid! ports/PutResult r)))}

   {:id :revision-strictly-increases
    :doc "each accepted write stamps a revision STRICTLY greater than the one it read (not necessarily contiguous, not necessarily 1 then 2)"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r1 (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 rev1 (:revision (stored r1))
                 r2 (ports/put-cas! s (sample-entry "w" "k" "two") rev1)
                 rev2 (:revision (stored r2))
                 r3 (ports/put-cas! s (sample-entry "w" "k" "three") rev2)
                 rev3 (:revision (stored r3))]
             (is (true? (:ok? r2)))
             (is (> rev2 rev1) "the second write's revision is strictly greater")
             (is (> rev3 rev2) "and so is the third's")
             (is (= rev3 (get (:cursor (ports/delta-since* s "w" nil)) "k"))
                 "the delta cursor carries the store's own revision, not a guess")))}

   {:id :cas-conflict-refuses
    :doc "put-cas! with a nil expected revision on an existing key refuses :wm/revision-conflict and names the winner"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 r (ports/put-cas! s (sample-entry "w" "k" "sneak") nil)]
             (is (false? (:ok? r)))
             (is (= :wm/revision-conflict (:error r)))
             (is (= "one" (:claim (:current r))) "the winner is reported for reconciliation")
             (valid! ports/PutResult r)))}

   {:id :cas-stale-integer-refuses
    :doc "a stale INTEGER revision (one that was current before a later write) refuses with the winner"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r1 (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 rev1 (:revision (stored r1))
                 _ (ports/put-cas! s (sample-entry "w" "k" "two") rev1)
                 r (ports/put-cas! s (sample-entry "w" "k" "three") rev1)]
             (is (= :wm/revision-conflict (:error r)))
             (is (= "two" (:claim (:current r))) "the current winner, not the stale one")))}

   {:id :cas-integer-on-absent-refuses
    :doc "an integer expected revision on an ABSENT key conflicts with :current nil"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r (ports/put-cas! s (sample-entry "w" "k" "one") 1)]
             (is (false? (:ok? r)))
             (is (= :wm/revision-conflict (:error r)))
             (is (nil? (:current r)))
             (is (nil? (ports/get-entry s "w" "k")) "nothing was created")))}

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
                 bad {:workspace-id "w" :key "k" :claim "" :status :draft}]
             (is (= :wm/invalid-entry
                    (try (ports/put-cas! s bad nil) nil
                         (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))}

   {:id :watch-fires-on-write
    :doc "watch! calls back with the stored entry on the next write; the callback is ASYNC so the case POLLS"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 seen (atom nil)
                 cancel (ports/watch! s "w" "k" (fn [e] (reset! seen e)))]
             (try
               (is (fn? cancel) "watch! answers a cancel fn")
               (ports/put-cas! s (sample-entry "w" "k" "watched") nil)
               (is (poll-until #(some? @seen)) "the watch fired within the deadline")
               (is (= "watched" (:claim @seen)))
               (valid! ports/WorkingEntry @seen)
               (finally (cancel)))))}

   {:id :watch-cancel-stops-it
    :doc "calling the cancel fn stops the watch"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 n (atom 0)
                 cancel (ports/watch! s "w" "k" (fn [_] (swap! n inc)))
                 r1 (ports/put-cas! s (sample-entry "w" "k" "a") nil)]
             (is (poll-until #(= 1 @n)) "the first write fired before the cancel")
             (cancel)
             (ports/put-cas! s (sample-entry "w" "k" "b") (:revision (stored r1)))
             (Thread/sleep 50)
             (is (= 1 @n) "only the write before the cancel fired")))}

   {:id :delta-empty-when-caught-up
    :doc "delta-since with a cursor holding every current revision answers no entries"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 d1 (ports/delta-since* s "w" nil)
                 d2 (ports/delta-since* s "w" (:cursor d1))]
             (is (= 1 (count (:entries d1))))
             (is (empty? (:entries d2)))))}

   {:id :delta-delivers-new-key
    :doc "a key absent from the cursor is delivered in full"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w" "a" "one") nil)
                 d1 (ports/delta-since* s "w" nil)
                 _ (ports/put-cas! s (sample-entry "w" "b" "two") nil)
                 d2 (ports/delta-since* s "w" (:cursor d1))]
             (is (= ["b"] (mapv :key (:entries d2))))))}

   {:id :delta-re-delivers-an-edited-key
    :doc "an EDITED key is re-delivered, which an append-only cursor would miss"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r1 (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 d1 (ports/delta-since* s "w" nil)
                 _ (ports/put-cas! s (sample-entry "w" "k" "two") (:revision (stored r1)))
                 d2 (ports/delta-since* s "w" (:cursor d1))]
             (is (= ["k"] (mapv :key (:entries d2)))
                 "the mutated key re-delivers because its revision advanced")
             (is (= "two" (:claim (first (:entries d2)))))))}

   {:id :delta-cursor-carries-forward
    :doc "the answered cursor advances so a caught-up reader stays caught up"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 rev (:revision (stored r))
                 d1 (ports/delta-since* s "w" nil)]
             (is (= rev (get (:cursor d1) "k")))
             (is (empty? (:entries (ports/delta-since* s "w" (:cursor d1)))))))}

   {:id :delta-truncated-then-complete
    :doc "a :max-entries capped delta answers :truncated? and its follow-up delivers every remaining key exactly once"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)]
             (doseq [k ["a" "b" "c" "d"]]
               (ports/put-cas! s (sample-entry "w" k (str "claim " k)) nil))
             (let [d1 (ports/delta-since s "w" nil {:max-entries 2})]
               (is (= 2 (count (:entries d1))))
               (is (true? (:truncated? d1)))
               (valid! ports/Delta d1))
             (let [ks (bounded-delta-keys s "w" {:max-entries 2})]
               (is (= #{"a" "b" "c" "d"} (set ks)) "every key is delivered")
               (is (= 4 (count ks)) "exactly once, no duplicates"))))}

   {:id :delta-max-chars-truncates
    :doc "a :max-chars cap bounds the delta and following it to exhaustion delivers every key exactly once"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)]
             (doseq [k ["a" "b" "c"]]
               (ports/put-cas! s (sample-entry "w" k (str "claim " k)) nil))
             (let [cap (entry/entry-size (first (:entries (ports/delta-since* s "w" nil))))
                   d1 (ports/delta-since s "w" nil {:max-chars cap})]
               (is (= 1 (count (:entries d1))) "the cap fits exactly one entry")
               (is (true? (:truncated? d1))))
             (let [cap (entry/entry-size (first (:entries (ports/delta-since* s "w" nil))))
                   ks (bounded-delta-keys s "w" {:max-chars cap})]
               (is (= #{"a" "b" "c"} (set ks)))
               (is (= 3 (count ks)) "exactly once, no duplicates"))))}

   {:id :workspaces-are-isolated
    :doc "delta-since and get-entry are scoped to one workspace"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 _ (ports/put-cas! s (sample-entry "w1" "k" "in-w1") nil)
                 _ (ports/put-cas! s (sample-entry "w2" "k" "in-w2") nil)]
             (is (= "in-w1" (:claim (ports/get-entry s "w1" "k"))))
             (is (= "in-w2" (:claim (ports/get-entry s "w2" "k"))))
             (is (= ["in-w1"] (mapv :claim (:entries (ports/delta-since* s "w1" nil)))))
             (is (= ["in-w2"] (mapv :claim (:entries (ports/delta-since* s "w2" nil)))))))}

   {:id :terminal-immutable-refuses
    :doc "a write over a TERMINAL entry is refused :wm/terminal and the store is unchanged"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r1 (ports/put-cas! s (sample-entry "w" "k" "done" {:status :working}) nil)
                 rev1 (:revision (stored r1))
                 settled (lifecycle/settle (ports/get-entry s "w" "k"))
                 r2 (ports/put-cas! s settled rev1)
                 rev2 (:revision (stored r2))
                 back (ports/put-cas! s (assoc (sample-entry "w" "k" "reopen") :status :draft) rev2)]
             (is (true? (:ok? r2)))
             (is (= :settled (:status (:entry r2))))
             (is (false? (:ok? back)))
             (is (= :wm/terminal (:error back)))
             (is (= :settled (:status (:current back))))
             (is (= :settled (:status (ports/get-entry s "w" "k"))) "the store did not change")))}

   {:id :terminal-supersession-allowed
    :doc "a NEW entry at its OWN key naming the terminal id with :supersedes is the one legal way past a terminal entry"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r1 (ports/put-cas! s (sample-entry "w" "old" "first" {:status :working}) nil)
                 old-id (:id (stored r1))
                 rev1 (:revision (stored r1))
                 settled (lifecycle/settle (ports/get-entry s "w" "old"))
                 r2 (ports/put-cas! s settled rev1)
                 _ (is (true? (:ok? r2)))
                 sup (sample-entry "w" "new" "replacement" {:status :draft :supersedes old-id})
                 r3 (ports/put-cas! s sup nil)]
             (is (true? (:ok? r3)))
             (is (= old-id (:supersedes (:entry r3))))
             (is (= :settled (:status (ports/get-entry s "w" "old"))) "the superseded entry is untouched")))}

   {:id :invalid-transition-refuses
    :doc "a supersession that ALSO makes an illegal status move is refused :wm/invalid-transition"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r1 (ports/put-cas! s (sample-entry "w" "k" "x" {:status :working}) nil)
                 rev1 (:revision (stored r1))
                 settled (lifecycle/settle (ports/get-entry s "w" "k"))
                 r2 (ports/put-cas! s settled rev1)
                 cur (:entry r2)
                 rev2 (:revision cur)
                 ;; names the current id (a supersession, so past the terminal
                 ;; gate) but moves :settled -> :draft, which the table forbids
                 reopen (assoc cur :status :draft :supersedes (:id cur))
                 r3 (ports/put-cas! s reopen rev2)]
             (is (false? (:ok? r3)))
             (is (= :wm/invalid-transition (:error r3)))
             (is (= :settled (:status (ports/get-entry s "w" "k"))))))}

   {:id :concurrent-writers-one-winner
    :doc "16 writers behind a latch with the same expected revision: exactly one wins, the rest conflict"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r0 (ports/put-cas! s (sample-entry "w" "k" "base") nil)
                 rev (:revision (stored r0))
                 n 16
                 start (java.util.concurrent.CountDownLatch. 1)
                 results (atom [])
                 fs (doall
                     (for [i (range n)]
                       (future
                         (.await start)
                         (let [r (ports/put-cas! s (sample-entry "w" "k" (str "writer-" i)) rev)]
                           (swap! results conj r)
                           r))))]
             (.countDown start)
             (doseq [f fs] @f)
             (let [oks (filter :ok? @results)
                   refusals (remove :ok? @results)]
               (is (= n (count @results)))
               (is (= 1 (count oks)) "exactly one writer with the same expected revision wins")
               (is (every? #(= :wm/revision-conflict (:error %)) refusals))
               (is (= (:revision (:entry (first oks)))
                      (:revision (ports/get-entry s "w" "k")))
                   "the winner's entry is the one on the key"))))}

   {:id :put-result-and-delta-validate
    :doc "every PutResult and Delta answer validates against its schema"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 ok (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 refused (ports/put-cas! s (sample-entry "w" "k" "two") nil)
                 d (ports/delta-since* s "w" nil)]
             (valid! ports/PutResult ok)
             (valid! ports/PutResult refused)
             (valid! ports/Delta d)))}

   {:id :get-entry-after-refusal
    :doc "after a refused write, get-entry still answers the winner"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r1 (ports/put-cas! s (sample-entry "w" "k" "winner") nil)
                 _ (ports/put-cas! s (sample-entry "w" "k" "loser") nil)
                 got (ports/get-entry s "w" "k")]
             (is (= (:entry r1) got))
             (is (= "winner" (:claim got)))))}

   {:id :store-keeps-identity
    :doc "a write keeps the key's :id and :created, whatever id the writer supplied"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 r1 (ports/put-cas! s (sample-entry "w" "k" "one") nil)
                 first-entry (stored r1)
                 _ (ports/put-cas! s (sample-entry "w" "k" "two") (:revision first-entry))
                 got (ports/get-entry s "w" "k")]
             (is (= (:id first-entry) (:id got)) "the id is stable across revisions")
             (is (= (:created first-entry) (:created got)) "and so is :created")))}

   {:id :terminal-status-immutable
    :doc "a settled entry cannot move again through the lifecycle table"
    :run (fn [{:keys [make-store]}]
           (let [s (make-store)
                 e (sample-entry "w" "k" "done" {:status :working})
                 r1 (ports/put-cas! s e nil)
                 settled (lifecycle/settle (ports/get-entry s "w" "k"))
                 r (ports/put-cas! s settled (:revision (stored r1)))]
             (is (true? (:ok? r)))
             (is (= :settled (:status (:entry r))))
             (is (empty? (lifecycle/allowed-transitions (:entry r)))
                 "a terminal entry has no outgoing transition")))}])

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
