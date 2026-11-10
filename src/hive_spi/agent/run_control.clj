(ns hive-spi.agent.run-control
  "Run-control ports: host-neutral submit / continue / await / observe /
   cancel / cleanup for ONE agent run, and the coordinate that joins a run to
   its transcript.

   This is the published, host-neutral copy of the contract hive-agent grew in
   `hive-agent.ports.run-control`: same shapes and same method arities, so an
   IAddon provider can offer agent runs without depending on hive-agent or
   hive-mcp, and hive-agent can adopt these protocols without changing any
   caller. Each capability is its own protocol (ISP), so a provider that cannot
   cancel does not have to pretend it can.

   Port methods return hive-dsl.result Results, `{:ok v}` or
   `{:error kw ...}`. Nothing is thrown across the boundary.

   TWO INVARIANTS bind every implementation:

   1. FLUSH-BEFORE-TERMINAL. The transcript is committed and closed BEFORE a
      terminal state is published, on the cancellation and failure paths too.
      After a caller observes a terminal state, `transcript-since` at
      :run/final-cursor MUST return nothing new.

   2. READ-ONLY AGAINST THE TRANSCRIPT. Run-control writes nothing to the
      transcript store. :run/resume-from is a bare cursor; a caller that wants
      a branch forks first and submits under the new agent-id.

   CORRELATION. :run/task-id and :run/attempt are optional caller coordinates.
   When a RunSpec carries them, the handle echoes them unchanged, so a
   scheduler can join run, attempt and task without host state.

   There is no streaming protocol. Tailing a run is a loop over the two
   coordinates here: observe-run gives a RunStatus, transcript-since at its
   :run/cursor gives the new entries, and the loop stops at `terminal?`.

   The conformance suite for these ports is `hive-spi.agent.conformance`;
   `hive-spi.agent.stub` is the in-memory reference implementation.

   Reload-safety: `defprotocol` is not idempotent, so each declaration is
   guarded. Re-evaluating this namespace does not orphan existing
   implementations."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Coordinates
;; =============================================================================

(def AgentId
  "Transcript identity of a run. The CALLER supplies it, and the transcript
   store files the run's entries under the same value as
   :transcript/agent-id."
  [:string {:min 1}])

(def Cursor
  "The comparable coordinate into a transcript. transcript-since returns the
   entries whose :transcript/turn is strictly greater than :turn."
  [:map [:turn [:int {:min 0}]]])

(def TranscriptRef
  "Where a run's output is read: whose transcript, and from which cursor."
  [:map
   [:agent-id AgentId]
   [:cursor Cursor]])

;; =============================================================================
;; Run shapes
;; =============================================================================

(def Correlation
  "Optional caller coordinates, echoed unchanged from RunSpec to RunHandle."
  [:map
   [:run/task-id {:optional true} :string]
   [:run/attempt {:optional true} [:int {:min 0}]]])

(def RunSpec
  "What to run. :run/resume-from is a bare cursor, never a store."
  (into Correlation
        [[:run/agent-id AgentId]
         [:run/task :string]
         [:run/adapter {:optional true} :keyword]
         [:run/resume-from {:optional true} Cursor]
         [:run/limits {:optional true} [:map [:max-turns {:optional true} :int]]]
         [:run/opts {:optional true} [:map]]]))

(def RunState
  "Lifecycle of one run."
  [:enum :queued :running :succeeded :failed :cancelled])

(def terminal-states
  "States after which no further transcript entry appears for the run."
  #{:succeeded :failed :cancelled})

(def RunHandle
  "What submit! returns: the run's identity and where to read it."
  (into Correlation
        [[:run/id :string]
         [:run/adapter :keyword]
         [:run/transcript-ref TranscriptRef]]))

(def RunStatus
  "A point-in-time answer: the state, and how far the transcript has got."
  [:map
   [:run/state RunState]
   [:run/cursor Cursor]])

(def RunError
  "Why a run failed."
  [:map [:error/type :keyword]])

(def RunOutcome
  "A terminal answer. Flush-before-terminal is stated against
   :run/final-cursor. :run/error is present only when the state is :failed."
  (into RunStatus
        [[:run/final-cursor Cursor]
         [:run/error {:optional true} RunError]]))

;; =============================================================================
;; Ports, one capability each
;; =============================================================================

(defonce ^:private -irunsubmit-defined? (atom false))

(when (compare-and-set! -irunsubmit-defined? false true)
  (defprotocol IRunSubmit
    "Start a run."
    (submit! [this run-spec]
      "Start the run described by RUN-SPEC (RunSpec) without blocking.
       Returns Result<RunHandle>. The handle's :run/transcript-ref carries the
       agent-id the caller supplied and the cursor the run starts from; any
       :run/task-id and :run/attempt are echoed unchanged. An invalid spec is
       an err, :run/invalid-spec.")))

(defonce ^:private -iruncontinue-defined? (atom false))

(when (compare-and-set! -iruncontinue-defined? false true)
  (defprotocol IRunContinue
    "Optional: start a successor of a completed run in its native
     conversation."
    (continue! [this parent-handle run-spec]
      "Start one successor of the owned, completed run PARENT-HANDLE. The
       caller supplies a fresh transcript identity in RUN-SPEC and authorises
       the parent. Returns Result<RunHandle>. Never reopens the parent's
       transcript and never replays a successor already reserved. This is not
       cursor-based branching.")))

(defonce ^:private -irunawait-defined? (atom false))

(when (compare-and-set! -irunawait-defined? false true)
  (defprotocol IRunAwait
    "Block on a run."
    (await-run [this handle timeout-ms]
      "Block until the run is terminal, or until TIMEOUT-MS has elapsed.
       Returns Result<RunOutcome>. A timeout is an err, :run/timeout, and not
       a terminal outcome: the run is still going. On ok, transcript-since at
       :run/final-cursor returns nothing new.")))

(defonce ^:private -irunobserve-defined? (atom false))

(when (compare-and-set! -irunobserve-defined? false true)
  (defprotocol IRunObserve
    "Poll a run."
    (observe-run [this handle]
      "Non-blocking poll. Returns Result<RunStatus>. :run/cursor is the
       coordinate to pass to transcript-since. An unknown handle is an err,
       :run/unknown.")))

(defonce ^:private -iruncancel-defined? (atom false))

(when (compare-and-set! -iruncancel-defined? false true)
  (defprotocol IRunCancel
    "Optional: cancel a run."
    (cancel! [this handle]
      "Request cancellation. Returns Result<RunStatus>. Flush-before-terminal
       applies: the transcript is closed before :cancelled is published.
       Cancelling a run that is already terminal is not an error; the answer
       reports the state found.")))

(defonce ^:private -iruncleanup-defined? (atom false))

(when (compare-and-set! -iruncleanup-defined? false true)
  (defprotocol IRunCleanup
    "Optional: release a finished run's host resources."
    (cleanup! [this handle]
      "Release the HOST resources of a finished run: processes, temp dirs,
       in-memory registries. Never the transcript, which outlives the run and
       is the evidence. Returns Result<nil>. Idempotent.")))

;; =============================================================================
;; Predicates and validators: one definition, shared by implementations and
;; their tests
;; =============================================================================

(defn terminal?
  "True when STATUS (a RunStatus or RunOutcome) is in a state after which no
   further transcript entry appears."
  [status]
  (contains? terminal-states (:run/state status)))

(defn correlation
  "The correlation coordinates of X, a RunSpec or RunHandle: the
   :run/task-id and :run/attempt it carries, and no other key."
  [x]
  (select-keys x [:run/task-id :run/attempt]))

(def valid-run-spec? (m/validator RunSpec))
(def valid-run-handle? (m/validator RunHandle))
(def valid-run-status? (m/validator RunStatus))
(def valid-run-outcome? (m/validator RunOutcome))
(def valid-transcript-ref? (m/validator TranscriptRef))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> terminal? [:=> [:cat [:maybe [:map [:run/state {:optional true} :any]]]] :boolean])
(m/=> correlation [:=> [:cat [:maybe :map]] Correlation])
