(ns hive-spi.agent.conformance
  "Conformance suite (TCK) for the run-control and transcript-query ports.

   It ships in src so that a provider outside this repo can put its adapter
   through it.

   The suite is data: `cases` is a vector of {:id :section :doc :run}, and
   each :run is a (fn [harness]) asserting with clojure.test/is. There are two
   ways to run it:

     (defconformance my-adapter make-harness)
       emits one deftest per case, named my-adapter-<case-id>.

     (run-conformance make-harness opts)
       runs every case in the current clojure.test context and returns
       {:ran [...] :skipped {...}}.

   MAKE-HARNESS is a zero-arg fn returning a FRESH harness for each case:
     :ctl          the run-control implementation (IRunSubmit, IRunObserve
                   and IRunAwait are required; IRunCancel, IRunCleanup and
                   IRunContinue are optional, and their cases are reported
                   under :skipped, never under :ran, when the protocol is
                   not satisfied)
     :transcripts  an ITranscriptQuery over the transcripts :ctl writes
     :drive!       (fn [handle]) moves the run on by one turn, writing one
                   transcript entry
     :end!         (fn [handle terminal-state]) makes the run terminate in
                   that state
   :drive! and :end! are test hooks. A real adapter wires them to a scripted
   model or a fake LLM, never to production state.

   OPTS: :skip {case-id \"reason\"}; :await-ms, how long a case waits in
   await-run for a run it has ended or cancelled (default
   `default-await-ms`)."
  (:require [clojure.test :as t :refer [is testing]]
            [hive-dsl.result :as r]
            [hive-spi.agent.run-control :as rc]
            [hive-spi.agent.transcript :as tx]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(def ^:private agent-id "caller-chosen-agent-alpha")

(defn- spec
  ([] (spec agent-id))
  ([aid] {:run/agent-id aid :run/task "conformance task"}))

(defn- submit [{:keys [ctl]} s] (:ok (rc/submit! ctl s)))

(defn- since [{:keys [transcripts]} aid cursor]
  (:ok (tx/transcript-since transcripts {:agent-id aid :cursor cursor})))

(def default-await-ms
  "How long a case waits in await-run for a run it has ended or cancelled,
   unless OPTS carries :await-ms."
  1000)

(defn- await-ms [h] (::await-ms h default-await-ms))

(defn- final-cursor-is-last-entry
  "Assert that :run/final-cursor of OUT is the cursor of the last entry in the
   transcript of handle HD, or the cursor the run started from when it wrote
   none."
  [h hd out]
  (let [{aid :agent-id start :cursor} (:run/transcript-ref hd)]
    (is (= (tx/last-cursor start (since h aid start)) (:run/final-cursor out))
        ":run/final-cursor is the cursor of the last transcript entry")))

(def cases
  "Every conformance case, in run order."
  [;; ---- submit ------------------------------------------------------------
   {:id :submit-preserves-agent-id :section :submit
    :doc "submit! answers a valid RunHandle naming the CALLER's agent-id; a rewritten one would bind the SPI to its host."
    :run (fn [h]
           (let [res (rc/submit! (:ctl h) (spec))]
             (is (r/ok? res) (pr-str res))
             (is (rc/valid-run-handle? (:ok res)) (pr-str res))
             (is (= agent-id (get-in res [:ok :run/transcript-ref :agent-id])))))}

   {:id :submit-echoes-correlation :section :submit
    :doc "submit! echoes :run/task-id and :run/attempt unchanged, so run, attempt and task can be joined without host state."
    :run (fn [h]
           (let [hd (submit h (assoc (spec) :run/task-id "task-42" :run/attempt 3))]
             (is (= {:run/task-id "task-42" :run/attempt 3} (rc/correlation hd)))))}

   {:id :submit-refuses-invalid-spec :section :submit
    :doc "A spec without an agent-id, or with a store where :run/resume-from takes a cursor, is an err, not a run."
    :run (fn [{:keys [ctl]}]
           (is (r/err? (rc/submit! ctl {:run/task "no agent id"})))
           (is (r/err? (rc/submit! ctl (assoc (spec) :run/resume-from {:store :x})))))}

   ;; ---- observe -----------------------------------------------------------
   {:id :observe-reports-progress :section :observe
    :doc "observe-run answers a valid non-terminal RunStatus whose cursor has moved with the driven turn."
    :run (fn [h]
           (let [hd (submit h (spec))
                 _  ((:drive! h) hd)
                 st (rc/observe-run (:ctl h) hd)]
             (is (r/ok? st))
             (is (rc/valid-run-status? (:ok st)) (pr-str st))
             (is (not (rc/terminal? (:ok st))))
             (is (pos? (get-in st [:ok :run/cursor :turn])))))}

   {:id :observe-unknown-is-err :section :observe
    :doc "observe-run of a handle this implementation never issued is an err."
    :run (fn [{:keys [ctl]}]
           (is (r/err? (rc/observe-run ctl {:run/id "never-issued" :run/adapter :x
                                            :run/transcript-ref {:agent-id "x" :cursor {:turn 0}}}))))}

   ;; ---- await -------------------------------------------------------------
   {:id :await-timeout-is-err :section :await
    :doc "A timeout is an err, :run/timeout, not a terminal outcome: the run is still going."
    :run (fn [h]
           (let [hd (submit h (spec))
                 _  ((:drive! h) hd)
                 res (rc/await-run (:ctl h) hd 5)]
             (is (r/err? res))
             (is (= :run/timeout (:error res)))
             (is (not (rc/terminal? (:ok (rc/observe-run (:ctl h) hd)))))))}

   {:id :await-flush-before-terminal :section :await
    :doc "await-run answers a valid terminal RunOutcome whose :run/final-cursor is the last transcript entry's, and transcript-since at it returns nothing new."
    :run (fn [h]
           (let [hd (submit h (spec))]
             ((:drive! h) hd)
             ((:drive! h) hd)
             ((:end! h) hd :succeeded)
             (let [out (:ok (rc/await-run (:ctl h) hd (await-ms h)))]
               (is (rc/valid-run-outcome? out) (pr-str out))
               (is (= :succeeded (:run/state out)))
               (final-cursor-is-last-entry h hd out)
               (is (empty? (since h agent-id (:run/final-cursor out)))
                   "the transcript was closed BEFORE the terminal state was published"))))}

   {:id :await-failed-carries-error :section :await
    :doc "A :failed outcome carries :run/error with an :error/type keyword."
    :run (fn [h]
           (let [hd (submit h (spec))]
             ((:drive! h) hd)
             ((:end! h) hd :failed)
             (let [out (:ok (rc/await-run (:ctl h) hd (await-ms h)))]
               (is (= :failed (:run/state out)))
               (final-cursor-is-last-entry h hd out)
               (is (keyword? (get-in out [:run/error :error/type]))))))}

   ;; ---- transcript --------------------------------------------------------
   {:id :caller-id-reaches-transcript :section :transcript
    :doc "Each driven turn is queryable under the caller's agent-id, in ascending turn order."
    :run (fn [h]
           (let [hd (submit h (spec))]
             ((:drive! h) hd)
             ((:drive! h) hd)
             (let [es (since h agent-id {:turn 0})]
               (is (tx/valid-entries? es) (pr-str es))
               (is (= 2 (count es)))
               (is (every? #(= agent-id (:transcript/agent-id %)) es))
               (is (apply < (map :transcript/turn es))))))}

   {:id :transcript-since-is-strict :section :transcript
    :doc "transcript-since returns only entries strictly after the cursor; from the last cursor it returns nothing."
    :run (fn [h]
           (let [hd (submit h (spec))]
             ((:drive! h) hd)
             ((:drive! h) hd)
             (let [all  (since h agent-id {:turn 0})
                   one  {:turn (:transcript/turn (first all))}
                   after (since h agent-id one)]
               (is (= (vec (next all)) after))
               (is (empty? (since h agent-id (tx/last-cursor {:turn 0} all)))))))}

   {:id :query-by-agent-agrees :section :transcript
    :doc "query-by-agent answers the same entries as transcript-since from {:turn 0}; an unknown agent answers (ok [])."
    :run (fn [h]
           (let [hd (submit h (spec))]
             ((:drive! h) hd)
             (is (= (since h agent-id {:turn 0})
                    (:ok (tx/query-by-agent (:transcripts h) agent-id))))
             (is (= (r/ok []) (tx/query-by-agent (:transcripts h) "agent-with-no-entries")))))}

   ;; ---- optional: cancel --------------------------------------------------
   {:id :cancel-flushes-before-cancelled :section :cancel :requires #'rc/IRunCancel
    :doc "IRunCancel: cancel! of a running run publishes :cancelled, its final cursor is the last transcript entry's and nothing follows it; cancelling again reports the state found."
    :run (fn [h]
           (let [hd (submit h (spec))]
             ((:drive! h) hd)
             (rc/cancel! (:ctl h) hd)
             (let [out (:ok (rc/await-run (:ctl h) hd (await-ms h)))]
               (is (= :cancelled (:run/state out)) (pr-str out))
               (final-cursor-is-last-entry h hd out)
               (is (empty? (since h agent-id (:run/final-cursor out)))))
             (let [again (rc/cancel! (:ctl h) hd)]
               (is (r/ok? again))
               (is (= :cancelled (get-in again [:ok :run/state]))))))}

   ;; ---- optional: cleanup -------------------------------------------------
   {:id :cleanup-keeps-transcript :section :cleanup :requires #'rc/IRunCleanup
    :doc "IRunCleanup: cleanup! answers (ok nil), is idempotent, and leaves the transcript readable."
    :run (fn [h]
           (let [hd (submit h (spec))]
             ((:drive! h) hd)
             ((:end! h) hd :succeeded)
             (is (= (r/ok nil) (rc/cleanup! (:ctl h) hd)))
             (is (= (r/ok nil) (rc/cleanup! (:ctl h) hd)) "idempotent")
             (is (seq (since h agent-id {:turn 0})) "the transcript outlives the run")))}

   ;; ---- optional: continue ------------------------------------------------
   {:id :continue-one-successor :section :continue :requires #'rc/IRunContinue
    :doc "IRunContinue: a completed parent admits one successor under a fresh agent-id; a second continue of the same parent is an err."
    :run (fn [h]
           (let [parent (submit h (spec))]
             ((:drive! h) parent)
             ((:end! h) parent :succeeded)
             (rc/await-run (:ctl h) parent (await-ms h))
             (let [child (rc/continue! (:ctl h) parent (spec "successor-agent"))]
               (is (r/ok? child) (pr-str child))
               (is (= "successor-agent" (get-in child [:ok :run/transcript-ref :agent-id]))))
             (is (r/err? (rc/continue! (:ctl h) parent (spec "second-successor")))
                 "a reserved successor is never replayed")))}])

(def case-ids
  "The case ids, in run order."
  (mapv :id cases))

(defn- case-by-id [id]
  (or (some #(when (= id (:id %)) %) cases)
      (throw (ex-info (str "Unknown conformance case: " id) {:id id :known case-ids}))))

(defn run-case
  "Run case ID against a fresh (MAKE-HARNESS) in the current clojure.test
   context. Answers :ran, or {:skipped id :reason r} when OPTS skips it or
   when the harness's :ctl does not satisfy the optional port the case is
   about; such a case asserts nothing."
  [make-harness opts id]
  (let [{:keys [run doc requires]} (case-by-id id)]
    (if-let [reason (get-in opts [:skip id])]
      {:skipped id :reason reason}
      (let [h (assoc (make-harness) ::await-ms (:await-ms opts default-await-ms))]
        (if (and requires (not (satisfies? @requires (:ctl h))))
          {:skipped id
           :reason (str "optional port " (name (symbol requires)) " is not satisfied")}
          (do (testing (str (name id) ": " doc)
                (run h))
              :ran))))))

(defn run-conformance
  "Run every case against fresh harnesses from MAKE-HARNESS. Answers
   {:ran [ids] :skipped {id reason}}."
  [make-harness opts]
  (reduce (fn [acc id]
            (let [res (run-case make-harness opts id)]
              (if (= :ran res)
                (update acc :ran conj id)
                (assoc-in acc [:skipped id] (:reason res)))))
          {:ran [] :skipped {}}
          case-ids))

(defmacro defconformance
  "Emit one deftest per case, named PREFIX-<case-id>, each running that case
   against a fresh (MAKE-HARNESS) with OPTS."
  ([prefix make-harness] `(defconformance ~prefix ~make-harness {}))
  ([prefix make-harness opts]
   `(do ~@(for [id case-ids]
            `(t/deftest ~(symbol (str (name prefix) "-" (name id)))
               (run-case ~make-harness ~opts ~id))))))

;; =============================================================================
;; Contracts
;; =============================================================================

(def CaseId
  "A conformance case id."
  (into [:enum] case-ids))

(def MakeHarness
  "A zero-arg fn returning a fresh harness. Generates one over the stub."
  [:fn {:error/message "a zero-arg harness constructor"
        :gen/schema    [:enum :stub]
        :gen/fmap      (fn [_] (requiring-resolve 'hive-spi.agent.stub/harness))}
   ifn?])

(def RunOpts
  "What run-case and run-conformance read."
  [:maybe [:map
           [:skip {:optional true} [:map-of CaseId :string]]
           [:await-ms {:optional true} [:int {:min 0}]]]])

(m/=> run-case [:=> [:cat MakeHarness RunOpts CaseId]
                [:or [:= :ran] [:map [:skipped CaseId] [:reason :string]]]])
(m/=> run-conformance [:=> [:cat MakeHarness RunOpts]
                       [:map [:ran [:vector CaseId]] [:skipped [:map-of CaseId :string]]]])
