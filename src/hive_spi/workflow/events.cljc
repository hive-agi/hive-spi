(ns hive-spi.workflow.events
  "Closed `:workflow/*` progress taxonomy + lifecycle FSM (HWF2 D1).

   Realises the canonical-design decision (hive memory 20260627145530-2c4394a8)
   and the progress-taxonomy convention (hive memory 20260627145506-080ff6af).

   This is the SINGLE source of greppable :workflow/* literal namespaced
   keywords. Sinks and bridges (see hwf2-progress-taxonomy) refine these into
   :headless/* / hivemind shapes downstream — they never re-collapse here.

   The taxonomy is a hive-dsl `defadt` closed sum: every constructed event
   carries {:adt/type :WorkflowEvent :adt/variant :workflow/...} on top of a
   common envelope so a single literal key (:adt/variant) is enough for
   filtering at any sink."
  (:require [hive-dsl.adt :refer [defadt]]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ===========================================================================
;;; WorkflowEvent — closed 8-variant ADT
;;; ---------------------------------------------------------------------------
;;; Common envelope keys (every variant carries these unless noted):
;;;   :run-id   string  — unique run id (UUID-ish; never reused across runs).
;;;   :node-id  vector  — structural path = the AST node's :wf/id.
;;;   :op       keyword — the :wf.op/* of the node, for sink projection.
;;;   :ts       int     — epoch millis at emission.
;;;   :payload  map     — variant-specific extra data; per-node opaque to FSM.
;;;
;;; Variant-specific keys:
;;;   :wave-id  keyword|string — present on :workflow/wave-* variants.
;;;   :slots    vector         — present on :workflow/wave-* variants
;;;                              (e.g. the [{:wf/id ...} ...] of the wave).
;;;   :error    map            — present on :workflow/*-failed variants
;;;                              (ex-info-style {:error :reason :data ...}).
;;; ===========================================================================

(defadt WorkflowEvent
  "Closed taxonomy of workflow progress events. Eight variants — exhaustive
   over the EvalAlgebra fold. Do NOT extend in place: open a design decision."
  [:workflow/started          {:run-id   string?
                               :node-id  vector?
                               :op       keyword?
                               :ts       int?
                               :payload  map?}]
  [:workflow/step-started     {:run-id   string?
                               :node-id  vector?
                               :op       keyword?
                               :ts       int?
                               :payload  map?}]
  [:workflow/step-completed   {:run-id   string?
                               :node-id  vector?
                               :op       keyword?
                               :ts       int?
                               :payload  map?}]
  [:workflow/step-failed      {:run-id   string?
                               :node-id  vector?
                               :op       keyword?
                               :ts       int?
                               :payload  map?
                               :error    map?}]
  [:workflow/wave-dispatched  {:run-id   string?
                               :node-id  vector?
                               :op       keyword?
                               :ts       int?
                               :payload  map?
                               :wave-id  (some-fn keyword? string?)
                               :slots    vector?}]
  [:workflow/wave-completed   {:run-id   string?
                               :node-id  vector?
                               :op       keyword?
                               :ts       int?
                               :payload  map?
                               :wave-id  (some-fn keyword? string?)
                               :slots    vector?}]
  [:workflow/completed        {:run-id   string?
                               :node-id  vector?
                               :op       keyword?
                               :ts       int?
                               :payload  map?}]
  [:workflow/failed           {:run-id   string?
                               :node-id  vector?
                               :op       keyword?
                               :ts       int?
                               :payload  map?
                               :error    map?}])

;;; ===========================================================================
;;; Lifecycle FSM — valid-transition?
;;; ---------------------------------------------------------------------------
;;; A run is a sequence of WorkflowEvents. The FSM constrains which variant
;;; may follow which. The carrier of state is the previous variant kw (or
;;; nil at the start). :workflow/completed and :workflow/failed are terminal.
;;;
;;; Transition graph (variant -> #{valid-next-variants}):
;;;   nil                          -> #{:workflow/started}
;;;   :workflow/started            -> step-* / wave-* / completed / failed
;;;   :workflow/step-started       -> step-completed / step-failed
;;;   :workflow/step-completed     -> step-* / wave-* / completed / failed
;;;   :workflow/step-failed        -> step-* / wave-* / completed / failed
;;;                                   (a method may catch and continue, OR
;;;                                    bubble — the FSM allows either)
;;;   :workflow/wave-dispatched    -> wave-completed / step-* / failed
;;;   :workflow/wave-completed     -> step-* / wave-* / completed / failed
;;;   :workflow/completed          -> #{} (terminal)
;;;   :workflow/failed             -> #{} (terminal)
;;; ===========================================================================

(def ^:private active-next
  "Set of variants permitted after any non-terminal mid-run state."
  #{:workflow/step-started
    :workflow/step-completed
    :workflow/step-failed
    :workflow/wave-dispatched
    :workflow/wave-completed
    :workflow/completed
    :workflow/failed})

(def transitions
  "Static FSM: previous-variant -> #{permitted-next-variants}.

   nil represents the pre-run state; :workflow/completed and :workflow/failed
   are terminal (empty next-set)."
  {nil                         #{:workflow/started}
   :workflow/started           active-next
   :workflow/step-started      #{:workflow/step-completed
                                 :workflow/step-failed}
   :workflow/step-completed    active-next
   :workflow/step-failed       active-next
   :workflow/wave-dispatched   #{:workflow/wave-completed
                                 :workflow/step-started
                                 :workflow/step-completed
                                 :workflow/step-failed
                                 :workflow/failed}
   :workflow/wave-completed    active-next
   :workflow/completed         #{}
   :workflow/failed            #{}})

(defn- variant-of
  "Coerce an event-or-variant-keyword to the bare :workflow/* variant kw.

   Accepts: nil, a :workflow/* keyword, or a WorkflowEvent map (with
   :adt/variant or :type)."
  [x]
  (cond
    (nil? x) nil
    (keyword? x) x
    (map? x) (or (:adt/variant x) (:type x))
    :else x))

(defn valid-transition?
  "Predicate: may `next-event` legally follow `prev-event` under the FSM?

   Arguments accept either a :workflow/* variant kw or a full event map.
   `prev` may be nil (i.e. the run has not started yet)."
  [prev next-event]
  (let [p (variant-of prev)
        n (variant-of next-event)]
    (boolean (contains? (get transitions p #{}) n))))

(defn terminal?
  "True if the variant (or event) is a terminal lifecycle state."
  [event-or-variant]
  (let [v (variant-of event-or-variant)]
    (or (= v :workflow/completed) (= v :workflow/failed))))

(def variants
  "Vector of the 8 :workflow/* variant kws in canonical order."
  [:workflow/started
   :workflow/step-started
   :workflow/step-completed
   :workflow/step-failed
   :workflow/wave-dispatched
   :workflow/wave-completed
   :workflow/completed
   :workflow/failed])
