(ns hive-spi.agent.stub
  "In-memory reference implementation of the run-control and transcript-query
   ports. It satisfies every protocol in `hive-spi.agent.run-control` and
   `hive-spi.agent.transcript`, and passes `hive-spi.agent.conformance` (see
   its test). It drives nothing by itself: a test moves a run forward with
   `advance!` and ends it with `finish!`, so each invariant is checked at a
   point the test chooses. Use it as a fake behind the ports, never through
   with-redefs.

   Terminating always closes the run's transcript first and publishes the
   terminal state second, all in one swap, so no observer can see a terminal
   state while entries are still being written."
  (:require [hive-dsl.result :as r]
            [hive-spi.agent.run-control :as rc]
            [hive-spi.agent.transcript :as tx]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defn- run-of [state handle] (get-in @state [:runs (:run/id handle)]))

(defn- unknown [handle] (r/err :run/unknown {:run/id (:run/id handle)}))

(defn- status-of [run]
  {:run/state (:state run) :run/cursor {:turn (:turn run)}})

(defn- new-handle [state run-spec]
  (let [id (str "stub-run-" (count (:runs @state)))
        h  (merge (rc/correlation run-spec)
                  {:run/id id
                   :run/adapter (:run/adapter run-spec :stub)
                   :run/transcript-ref {:agent-id (:run/agent-id run-spec)
                                        :cursor (:run/resume-from run-spec {:turn 0})}})]
    (swap! state assoc-in [:runs id]
           {:handle h :state :queued :closed? false
            :turn (get-in h [:run/transcript-ref :cursor :turn])
            :agent-id (:run/agent-id run-spec)
            :host {:resources :held}})
    h))

(defn- entries-of [state agent-id]
  (->> (:entries @state)
       (filter #(= agent-id (:transcript/agent-id %)))
       (sort-by :transcript/turn)
       vec))

(defrecord StubRunControl [state]
  rc/IRunSubmit
  (submit! [_ run-spec]
    (if-not (rc/valid-run-spec? run-spec)
      (r/err :run/invalid-spec {:spec run-spec})
      (r/ok (new-handle state run-spec))))

  rc/IRunContinue
  (continue! [_ parent-handle run-spec]
    (let [parent (run-of state parent-handle)]
      (cond
        (not (rc/valid-run-spec? run-spec))
        (r/err :run/invalid-spec {:spec run-spec})

        (nil? parent)
        (unknown parent-handle)

        (not= :succeeded (:state parent))
        (r/err :run/parent-not-completed {:run/id (:run/id parent-handle)
                                          :run/state (:state parent)})

        (= (:agent-id parent) (:run/agent-id run-spec))
        (r/err :run/transcript-reused {:agent-id (:run/agent-id run-spec)})

        :else
        (let [reserved? (atom false)]
          (swap! state update-in [:runs (:run/id parent-handle)]
                 (fn [p] (if (:successor p)
                           (do (reset! reserved? true) p)
                           (assoc p :successor true))))
          (if @reserved?
            (r/err :run/successor-reserved {:run/id (:run/id parent-handle)})
            (r/ok (new-handle state run-spec)))))))

  rc/IRunObserve
  (observe-run [_ handle]
    (if-let [run (run-of state handle)]
      (r/ok (status-of run))
      (unknown handle)))

  rc/IRunAwait
  (await-run [_ handle timeout-ms]
    (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
      (loop []
        (let [run (run-of state handle)]
          (cond
            (nil? run) (unknown handle)

            (rc/terminal? {:run/state (:state run)})
            (r/ok (cond-> (assoc (status-of run) :run/final-cursor {:turn (:turn run)})
                    (= :failed (:state run))
                    (assoc :run/error {:error/type (:error-type run :run/stub-failure)})))

            (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep 1) (recur))

            :else
            (r/err :run/timeout {:run/id (:run/id handle) :timeout-ms timeout-ms}))))))

  rc/IRunCancel
  (cancel! [this handle]
    (if-not (run-of state handle)
      (unknown handle)
      (do (swap! state update-in [:runs (:run/id handle)]
                 (fn [run]
                   (if (rc/terminal? {:run/state (:state run)})
                     run
                     (assoc run :closed? true :state :cancelled))))
          (rc/observe-run this handle))))

  rc/IRunCleanup
  (cleanup! [_ handle]
    (swap! state (fn [st]
                   (cond-> (update st :cleaned (fnil conj #{}) (:run/id handle))
                     (get-in st [:runs (:run/id handle)])
                     (update-in [:runs (:run/id handle)] dissoc :host))))
    (r/ok nil))

  tx/ITranscriptQuery
  (transcript-since [_ transcript-ref]
    (let [from (get-in transcript-ref [:cursor :turn] 0)]
      (r/ok (filterv #(> (:transcript/turn %) from)
                     (entries-of state (:agent-id transcript-ref))))))

  (query-by-agent [_ agent-id]
    (r/ok (entries-of state agent-id))))

;; =============================================================================
;; Construction and driving
;; =============================================================================

(defn create
  "A fresh StubRunControl with no runs and no transcript entries. OPTS is
   reserved and ignored."
  ([] (create {}))
  ([_opts] (->StubRunControl (atom {:runs {} :entries [] :cleaned #{}}))))

(defn advance!
  "Move the run of HANDLE forward one turn: write one transcript entry under
   the run's agent-id and mark the run :running. A closed run is left alone.
   Returns STUB."
  [stub handle]
  (swap! (:state stub)
         (fn [st]
           (let [id  (:run/id handle)
                 run (get-in st [:runs id])]
             (if (or (nil? run) (:closed? run))
               st
               (let [turn (inc (:turn run))]
                 (-> st
                     (update :entries conj {:transcript/agent-id (:agent-id run)
                                            :transcript/turn turn
                                            :transcript/role :assistant
                                            :transcript/content (str "turn " turn)})
                     (assoc-in [:runs id] (assoc run :turn turn :state :running))))))))
  stub)

(defn finish!
  "Terminate the run of HANDLE in TERMINAL, one of `rc/terminal-states`:
   close its transcript, then publish the state, in one swap. Returns STUB."
  [stub handle terminal]
  (swap! (:state stub) update-in [:runs (:run/id handle)]
         (fn [run] (when run (assoc run :closed? true :state terminal))))
  stub)

(defn harness
  "A conformance harness over a fresh stub, in the shape
   `hive-spi.agent.conformance` takes."
  []
  (let [s (create)]
    {:ctl         s
     :transcripts s
     :drive!      (fn [h] (advance! s h))
     :end!        (fn [h terminal] (finish! s h terminal))}))

;; =============================================================================
;; Contracts
;; =============================================================================

(def Stub
  "A StubRunControl."
  [:fn {:error/message "must be a StubRunControl"
        :gen/schema    [:enum :stub]
        :gen/fmap      (fn [_] (create))}
   #(instance? StubRunControl %)])

(def TerminalState
  "A state no transcript entry follows."
  (into [:enum] rc/terminal-states))

(m/=> create [:function
              [:=> [:cat] Stub]
              [:=> [:cat [:maybe :map]] Stub]])
(m/=> advance! [:=> [:cat Stub [:map [:run/id :string]]] Stub])
(m/=> finish! [:=> [:cat Stub [:map [:run/id :string]] TerminalState] Stub])
(m/=> harness [:=> [:cat] [:map [:ctl Stub] [:transcripts Stub] [:drive! ifn?] [:end! ifn?]]])
