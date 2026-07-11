(ns hive-spi.workflow.progress
  "Projects FSM transitions into WorkflowEvent variants and subagent transcript
   turns into chat frames; folds an SSOT model; pushes wire frames to a sink.")

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ===========================================================================
;;; SSOT model + pluggable sink
;;; ===========================================================================

(defonce models (atom {:subagents {} :workflows {} :waves {}}))

(defonce ^:private sink (atom (fn [_event _data] nil)))

(defn set-sink!
  "Install the sink `(fn [event-kw wire-map] ...)`; non-fn resets to no-op."
  [f]
  (reset! sink (if (fn? f) f (fn [_ _] nil)))
  :ok)

(defn- emit! [event data]
  (try (@sink event data) (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- ->str [x]
  (when (some? x) (if (keyword? x) (subs (str x) 1) (str x))))

(defn- now-ms []
  #?(:clj (System/currentTimeMillis) :cljs (.getTime (js/Date.))))

;;; ===========================================================================
;;; Subagent chat projection
;;; ===========================================================================

(defn tool-call->wire
  "Normalize a tool-call to flat `{:name :args}`, handling provider-nested shapes."
  [tc]
  (let [f (:function tc)]
    {:name (str (or (:name f) (:name tc) (:tool-call/name tc) (get tc "name") ""))
     :args (let [a (or (:arguments f) (:args tc) (:arguments tc))]
             (cond
               (nil? a)    ""
               (string? a) a
               :else       (pr-str a)))}))

(defn entry->wire
  "Project a `:transcript/entry-recorded` payload into a :subagent-message frame."
  [run-id {:keys [agent-id turn role content cost-usd tool-calls]}]
  {:run-id     run-id
   :agent-id   (str agent-id)
   :turn       turn
   :role       (some-> role name)
   :content    (str (or content ""))
   :cost-usd   (or cost-usd 0.0)
   :tool-calls (mapv tool-call->wire (or tool-calls []))})

(defn record-subagent-turn!
  "Fold a transcript payload into the SSOT and emit a :subagent-message frame."
  [payload]
  (let [run-id (get-in @models [:subagents (:agent-id payload) :run-id])
        wire   (entry->wire run-id payload)
        aid    (:agent-id wire)]
    (swap! models update-in [:subagents aid]
           (fn [m] (-> (or m {:agent-id aid :turns []})
                       (update :turns conj wire)
                       (assoc :last-turn (:turn wire)))))
    (emit! :subagent-message wire)
    wire))

;;; ===========================================================================
;;; Workflow FSM projection
;;; ===========================================================================

(def ^:const fsm-start :hive.events.fsm/start)
(def ^:const fsm-end   :hive.events.fsm/end)
(def ^:const fsm-error :hive.events.fsm/error)
(def ^:const fsm-halt  :hive.events.fsm/halt)

(defn state->variant
  "Map a hive.events.fsm state-id onto a hive-spi.workflow.events variant kw."
  [state-id]
  (condp = state-id
    fsm-start :workflow/started
    fsm-end   :workflow/completed
    fsm-error :workflow/failed
    fsm-halt  :workflow/completed
    :workflow/step-started))

(def ^:private variant->phase
  {:workflow/started      "started"
   :workflow/completed    "completed"
   :workflow/failed       "failed"
   :workflow/step-started "step"})

(defn transition->event
  "Project a hive.events.fsm `:post` snapshot into a WorkflowEvent map."
  [run-id label fsm ts]
  (let [state   (:current-state-id fsm)
        variant (state->variant state)]
    {:adt/type    :WorkflowEvent
     :adt/variant variant
     :run-id      run-id
     :node-id     [(->str state)]
     :op          (if (keyword? state) state :wf.op/step)
     :ts          ts
     :payload     {:label      label
                   :state-id   (->str state)
                   :from       (->str (:last-state-id fsm))
                   :phase      (get variant->phase variant "step")
                   :trace-tail (mapv (fn [seg]
                                       {:state-id (->str (:state-id seg))
                                        :status   (some-> (:status seg) name)})
                                     (take-last 8 (or (:trace fsm) [])))}}))

(defn event->wire
  "Flatten a WorkflowEvent map into a :workflow-transition wire frame."
  [ev]
  (merge {:run-id  (:run-id ev)
          :variant (->str (:adt/variant ev))}
         (:payload ev)))

(defn record-transition!
  "Fold a transition into the SSOT and emit a :workflow-transition frame."
  [run-id label fsm]
  (let [ev   (transition->event run-id label fsm (now-ms))
        wire (event->wire ev)]
    (swap! models update-in [:workflows run-id]
           (fn [w] (merge (or w {:run-id run-id :label label}) wire)))
    (emit! :workflow-transition wire)
    ev))

(defn- ->coarse-phase
  "Coarse lifecycle phase for a :workflow/* variant, matching the fsm wire vocab."
  [variant]
  (case variant
    :workflow/started   "started"
    :workflow/completed "completed"
    :workflow/failed    "failed"
    (:workflow/wave-dispatched :workflow/wave-completed) "wave"
    "step"))

(defn record-workflow-event!
  "Fold an EvalAlgebra WorkflowEvent (a hive-spi.workflow.events ADT value) into
   the SSOT :workflows and emit a :workflow-transition wire frame. Shaped as a
   1-arg fn for direct injection as the EvalAlgebra :sink. Returns ev."
  [ev]
  (let [run-id (:run-id ev)
        wire   (cond-> (assoc (event->wire ev)
                              :node-id (mapv ->str (:node-id ev))
                              :op      (->str (:op ev))
                              :phase   (->coarse-phase (:adt/variant ev)))
                 (:error ev) (assoc :error (:error ev)))]
    (swap! models update-in [:workflows run-id]
           (fn [w] (merge (or w {:run-id run-id}) wire)))
    (emit! :workflow-transition wire)
    ev))

;;; ===========================================================================
;;; Wave / drone-roster projection
;;; ===========================================================================

(defn record-wave!
  "Fold a swarm wave payload into the SSOT `:waves` and emit a `:workflow-wave`
   frame. `variant` is a :workflow/wave-* keyword; `payload` carries :run-id and
   drone parity counts (:dispatched :completed :failed) + roster."
  [variant payload]
  (let [run-id (:run-id payload)
        wire   (assoc payload
                      :variant (->str variant)
                      :phase   "wave")]
    (swap! models update-in [:waves run-id]
           (fn [w] (merge (or w {:run-id run-id}) wire)))
    (emit! :workflow-wave wire)
    wire))

;;; ===========================================================================
;;; Snapshot / reset
;;; ===========================================================================

(defn snapshot [] @models)

(defn snapshot-frames
  "Replay the SSOT as an ordered seq of `[event-kw wire]` frames: workflow
   transitions first, then subagent turns in recorded order."
  []
  (let [{:keys [workflows subagents waves]} @models]
    (concat
     (map (fn [[_ w]] [:workflow-transition w]) workflows)
     (map (fn [[_ w]] [:workflow-wave w]) waves)
     (mapcat (fn [[_ s]] (map (fn [t] [:subagent-message t]) (:turns s)))
             subagents))))

(defn clear-models! []
  (reset! models {:subagents {} :workflows {} :waves {}})
  :cleared)