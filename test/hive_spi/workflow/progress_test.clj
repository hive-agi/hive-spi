(ns hive-spi.workflow.progress-test
  "Contract tests for hive-spi.workflow.progress — the pure projections
   (tool-call->wire / entry->wire / event->wire / state->variant /
   transition->event) and the record-*! boundary folds."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-spi.workflow.progress :as p]))

(use-fixtures :each
  (fn [t]
    (p/clear-models!)
    (p/set-sink! nil)
    (t)
    (p/clear-models!)
    (p/set-sink! nil)))

;; ---------------------------------------------------------------------------
;; tool-call->wire — normalize provider-nested shapes to flat {:name :args}
;; ---------------------------------------------------------------------------

(deftest tool-call->wire-shapes
  (testing "flat {:name :args}"
    (is (= {:name "search" :args "q"}
           (p/tool-call->wire {:name "search" :args "q"}))))
  (testing "provider-nested :function"
    (is (= {:name "search" :args "{\"q\":1}"}
           (p/tool-call->wire {:function {:name "search" :arguments "{\"q\":1}"}}))))
  (testing "nil args -> empty string, map args -> pr-str"
    (is (= "" (:args (p/tool-call->wire {:name "x"}))))
    (is (= (pr-str {:a 1}) (:args (p/tool-call->wire {:name "x" :args {:a 1}})))))
  (testing "missing name -> empty string, name is always a string"
    (is (= "" (:name (p/tool-call->wire {}))))
    (is (= "42" (:name (p/tool-call->wire {:name 42}))))))

;; ---------------------------------------------------------------------------
;; entry->wire — pure subagent-message projection (run-id injected)
;; ---------------------------------------------------------------------------

(deftest entry->wire-projection
  (let [wire (p/entry->wire "run-7"
                            {:agent-id :ling-3 :turn 4 :role :assistant
                             :content "hi" :cost-usd 0.25
                             :tool-calls [{:name "t" :args "a"}]})]
    (is (= "run-7" (:run-id wire)))
    (is (= ":ling-3" (:agent-id wire)))
    (is (= 4 (:turn wire)))
    (is (= "assistant" (:role wire)))
    (is (= "hi" (:content wire)))
    (is (= 0.25 (:cost-usd wire)))
    (is (= [{:name "t" :args "a"}] (:tool-calls wire))))
  (testing "nil content/cost/role/tool-calls default safely"
    (let [wire (p/entry->wire "r" {:agent-id "a"})]
      (is (= "" (:content wire)))
      (is (= 0.0 (:cost-usd wire)))
      (is (= [] (:tool-calls wire)))
      (is (nil? (:role wire))))))

(deftest entry->wire-is-pure
  (testing "run-id comes ONLY from the argument, never from @models"
    (swap! p/models assoc-in [:subagents "a" :run-id] "SEEDED")
    (is (= "arg-run" (:run-id (p/entry->wire "arg-run" {:agent-id "a"})))))
  (testing "same inputs project to the same frame"
    (let [payload {:agent-id "a" :turn 1 :role :user :content "x" :tool-calls []}]
      (is (= (p/entry->wire "r" payload) (p/entry->wire "r" payload))))))

;; ---------------------------------------------------------------------------
;; state->variant — fsm state-id -> WorkflowEvent variant
;; ---------------------------------------------------------------------------

(deftest state->variant-mapping
  (is (= :workflow/started   (p/state->variant :hive.events.fsm/start)))
  (is (= :workflow/completed (p/state->variant :hive.events.fsm/end)))
  (is (= :workflow/failed    (p/state->variant :hive.events.fsm/error)))
  (is (= :workflow/completed (p/state->variant :hive.events.fsm/halt)))
  (testing "any other state is a step"
    (is (= :workflow/step-started (p/state->variant :some.ns/build)))
    (is (= :workflow/step-started (p/state->variant :whatever)))))

;; ---------------------------------------------------------------------------
;; transition->event — pure fsm-snapshot projection (ts injected)
;; ---------------------------------------------------------------------------

(deftest transition->event-projection
  (let [fsm {:current-state-id :hive.events.fsm/start
             :last-state-id nil
             :trace [{:state-id :s0 :status :ok}]}
        ev  (p/transition->event "r1" "build" fsm 999)]
    (is (= :WorkflowEvent (:adt/type ev)))
    (is (= :workflow/started (:adt/variant ev)))
    (is (= "r1" (:run-id ev)))
    (is (= 999 (:ts ev)))
    (is (= ["hive.events.fsm/start"] (:node-id ev)))
    (is (= :hive.events.fsm/start (:op ev)))
    (let [pl (:payload ev)]
      (is (= "build" (:label pl)))
      (is (= "hive.events.fsm/start" (:state-id pl)))
      (is (= "started" (:phase pl)))
      (is (= [{:state-id "s0" :status "ok"}] (:trace-tail pl))))))

(deftest transition->event-non-keyword-op
  (testing "a non-keyword state yields :wf.op/step and a step phase"
    (let [ev (p/transition->event "r" "l" {:current-state-id "raw-state"} 0)]
      (is (= :wf.op/step (:op ev)))
      (is (= "step" (get-in ev [:payload :phase]))))))

(deftest transition->event-trace-tail-cap
  (testing "trace-tail keeps only the last 8 segments"
    (let [trace (mapv (fn [i] {:state-id (keyword (str "s" i)) :status :ok}) (range 20))
          ev    (p/transition->event "r" "l"
                                     {:current-state-id :hive.events.fsm/end :trace trace} 0)]
      (is (= 8 (count (get-in ev [:payload :trace-tail]))))
      (is (= "s19" (:state-id (last (get-in ev [:payload :trace-tail]))))))))

;; ---------------------------------------------------------------------------
;; event->wire — flatten a WorkflowEvent into a wire frame
;; ---------------------------------------------------------------------------

(deftest event->wire-flatten
  (let [fsm {:current-state-id :hive.events.fsm/end :last-state-id :s1 :trace []}
        ev  (p/transition->event "r2" "done" fsm 5)
        w   (p/event->wire ev)]
    (is (= "r2" (:run-id w)))
    (is (= "workflow/completed" (:variant w)))
    (is (= "completed" (:phase w)))
    (is (= "hive.events.fsm/end" (:state-id w)))
    (is (= "s1" (:from w)))
    (testing "payload keys are lifted to the top level"
      (is (contains? w :trace-tail))
      (is (contains? w :label)))))

;; ---------------------------------------------------------------------------
;; record-*! — boundary folds: mutate the SSOT + emit through the sink
;; ---------------------------------------------------------------------------

(deftest record-subagent-turn!-folds-and-emits
  (let [seen (atom [])]
    (p/set-sink! (fn [ev data] (swap! seen conj [ev data])))
    (swap! p/models assoc-in [:subagents "a1" :run-id] "run-x")
    (let [wire (p/record-subagent-turn! {:agent-id "a1" :turn 0 :role :assistant
                                         :content "hi" :tool-calls []})]
      (is (= "run-x" (:run-id wire)))
      (is (= "a1" (:agent-id wire)))
      (testing "turn appended to the SSOT"
        (is (= [wire] (get-in @p/models [:subagents "a1" :turns])))
        (is (= 0 (get-in @p/models [:subagents "a1" :last-turn]))))
      (testing "a :subagent-message frame was emitted"
        (is (= [[:subagent-message wire]] @seen))))))

(deftest record-transition!-folds-and-emits
  (let [seen (atom [])]
    (p/set-sink! (fn [ev data] (swap! seen conj [ev data])))
    (let [fsm {:current-state-id :hive.events.fsm/start :last-state-id nil :trace []}
          ev  (p/record-transition! "run-y" "boot" fsm)]
      (is (= :WorkflowEvent (:adt/type ev)))
      (is (number? (:ts ev)))
      (testing "workflow folded into the SSOT keyed by run-id"
        (is (= "run-y" (get-in @p/models [:workflows "run-y" :run-id])))
        (is (= "started" (get-in @p/models [:workflows "run-y" :phase]))))
      (testing "a :workflow-transition frame was emitted"
        (is (= 1 (count @seen)))
        (is (= :workflow-transition (ffirst @seen)))))))

(deftest record-workflow-event!-folds-and-emits
  (let [seen (atom [])]
    (p/set-sink! (fn [ev data] (swap! seen conj [ev data])))
    (let [ev  {:adt/type :WorkflowEvent :adt/variant :workflow/step-completed
               :run-id "run-z" :node-id [:seq 0] :op :wf.op/call :ts 1 :payload {}}
          out (p/record-workflow-event! ev)]
      (is (= ev out) "returns the event unchanged")
      (testing "folded into the SSOT under run-id"
        (is (= "run-z" (get-in @p/models [:workflows "run-z" :run-id])))
        (is (= "workflow/step-completed" (get-in @p/models [:workflows "run-z" :variant])))
        (is (= "step" (get-in @p/models [:workflows "run-z" :phase]))))
      (testing "a :workflow-transition frame emitted with stringified node-id/op"
        (is (= 1 (count @seen)))
        (is (= :workflow-transition (ffirst @seen)))
        (let [wire (second (first @seen))]
          (is (= ["seq" "0"] (:node-id wire)))
          (is (= "wf.op/call" (:op wire))))))))

(deftest record-workflow-event!-carries-error
  (let [wire (atom nil)]
    (p/set-sink! (fn [_ data] (reset! wire data)))
    (p/record-workflow-event! {:adt/variant :workflow/step-failed :run-id "r"
                               :node-id [:call] :op :wf.op/call :payload {}
                               :error {:error :boom}})
    (is (= {:error :boom} (:error @wire)))
    (is (= "step" (:phase @wire)))))

(deftest set-sink!-guards-non-fn
  (testing "a non-fn sink resets to no-op and emit never throws"
    (p/set-sink! :not-a-fn)
    (is (= :WorkflowEvent
           (:adt/type (p/record-transition! "r" "l"
                                            {:current-state-id :hive.events.fsm/start
                                             :trace []}))))))

;; ---------------------------------------------------------------------------
;; Properties
;; ---------------------------------------------------------------------------

(defspec transition->event-ts-is-injected 100
  (prop/for-all [run-id gen/string-alphanumeric
                 ts     gen/large-integer]
    (= ts (:ts (p/transition->event run-id "l"
                                    {:current-state-id :hive.events.fsm/start :trace []} ts)))))

(defspec entry->wire-run-id-from-arg 100
  (prop/for-all [run-id gen/string-alphanumeric
                 aid    gen/string-alphanumeric]
    (= run-id (:run-id (p/entry->wire run-id {:agent-id aid})))))

(defspec tool-call->wire-is-total 100
  (prop/for-all [tc (gen/map gen/keyword (gen/one-of [gen/string gen/small-integer]))]
    (let [w (p/tool-call->wire tc)]
      (and (string? (:name w)) (string? (:args w))))))