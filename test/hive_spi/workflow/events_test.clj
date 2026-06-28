(ns hive-spi.workflow.events-test
  "Contract tests for hive-spi.workflow.events — the 8-variant WorkflowEvent
   ADT + valid-transition? lifecycle FSM."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.workflow.events :as ev]))

;; ---------------------------------------------------------------------------
;; ADT variant surface
;; ---------------------------------------------------------------------------

(def ^:private expected-variants
  #{:workflow/started
    :workflow/step-started
    :workflow/step-completed
    :workflow/step-failed
    :workflow/wave-dispatched
    :workflow/wave-completed
    :workflow/completed
    :workflow/failed})

(deftest workflow-event-adt-surface
  (testing "WorkflowEvent defines exactly the 8 canonical variants"
    (is (= expected-variants (:variants ev/WorkflowEvent))))
  (testing "variants vector mirrors the ADT and preserves canonical order"
    (is (= 8 (count ev/variants)))
    (is (= expected-variants (set ev/variants))))
  (testing "workflow-event constructor produces tagged ADT values"
    (let [e (ev/workflow-event :workflow/started
                               {:run-id "r1" :node-id [] :op :wf.op/seq
                                :ts 0 :payload {}})]
      (is (= :WorkflowEvent (:adt/type e)))
      (is (= :workflow/started (:adt/variant e)))
      (is (= "r1" (:run-id e)))))
  (testing "unknown variant kw FAILS LOUD"
    (is (thrown? Exception
                 (ev/workflow-event :workflow/never {:run-id "r"})))))

;; ---------------------------------------------------------------------------
;; FSM — valid-transition?
;; ---------------------------------------------------------------------------

(deftest fsm-initial-transition
  (testing "nil -> :workflow/started is the ONLY legal opening move"
    (is (ev/valid-transition? nil :workflow/started))
    (doseq [v (disj expected-variants :workflow/started)]
      (is (not (ev/valid-transition? nil v))
          (str "nil -> " v " must be rejected (the run hasn't started)")))))

(deftest fsm-started-fanout
  (testing ":workflow/started leads into step-*/wave-*/completed/failed"
    (doseq [v [:workflow/step-started
               :workflow/step-completed
               :workflow/step-failed
               :workflow/wave-dispatched
               :workflow/wave-completed
               :workflow/completed
               :workflow/failed]]
      (is (ev/valid-transition? :workflow/started v)
          (str ":workflow/started -> " v " must be legal")))
    (is (not (ev/valid-transition? :workflow/started :workflow/started))
        "a run can only :workflow/started once")))

(deftest fsm-step-started-restriction
  (testing ":workflow/step-started ONLY transitions to step-completed/step-failed"
    (is (ev/valid-transition? :workflow/step-started :workflow/step-completed))
    (is (ev/valid-transition? :workflow/step-started :workflow/step-failed))
    (doseq [v [:workflow/started
               :workflow/step-started
               :workflow/wave-dispatched
               :workflow/wave-completed
               :workflow/completed
               :workflow/failed]]
      (is (not (ev/valid-transition? :workflow/step-started v))
          (str "step-started -> " v " must be rejected")))))

(deftest fsm-wave-dispatched-restriction
  (testing ":workflow/wave-dispatched permits wave-completed / step-* / failed"
    (is (ev/valid-transition? :workflow/wave-dispatched :workflow/wave-completed))
    (is (ev/valid-transition? :workflow/wave-dispatched :workflow/step-started))
    (is (ev/valid-transition? :workflow/wave-dispatched :workflow/failed))
    (is (not (ev/valid-transition? :workflow/wave-dispatched :workflow/started))
        "cannot restart a run from a dispatched wave")
    (is (not (ev/valid-transition? :workflow/wave-dispatched :workflow/completed))
        "wave-dispatched cannot jump directly to completed — must close the wave first")))

(deftest fsm-terminals
  (testing ":workflow/completed and :workflow/failed are terminal"
    (doseq [v expected-variants]
      (is (not (ev/valid-transition? :workflow/completed v))
          (str ":workflow/completed -> " v " must be rejected (terminal)"))
      (is (not (ev/valid-transition? :workflow/failed v))
          (str ":workflow/failed -> " v " must be rejected (terminal)")))
    (is (ev/terminal? :workflow/completed))
    (is (ev/terminal? :workflow/failed))
    (is (not (ev/terminal? :workflow/started)))))

(deftest fsm-accepts-event-maps
  (testing "valid-transition? accepts either variant kws or full event maps"
    (let [started (ev/workflow-event :workflow/started
                                     {:run-id "r" :node-id []
                                      :op :wf.op/seq :ts 0 :payload {}})
          step-started (ev/workflow-event :workflow/step-started
                                          {:run-id "r" :node-id [0]
                                           :op :wf.op/call :ts 1 :payload {}})]
      (is (ev/valid-transition? started step-started))
      (is (ev/valid-transition? nil started)))))
