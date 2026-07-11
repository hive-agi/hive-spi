(ns hive-spi.workflow.strategy-test
  "Contract tests for hive-spi.workflow.strategy (HWF2 D1b).

   The load-bearing assertion is `adt-case-through-a-def-alias`: hive-mcp
   re-exports WorkflowStrategyEntry as a plain `def`, and
   hive-mcp.workflows.strategy-registry/register-by-key! dispatches on it with
   `adt-case`. If that stops working the :method seam breaks silently."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.adt :refer [adt-case]]
            [hive-spi.workflow.strategy :as s]))

;; ---------------------------------------------------------------------------
;; IDispatchStrategy
;; ---------------------------------------------------------------------------

(defrecord StubStrategy []
  s/IDispatchStrategy
  (dispatch [_ plan _opts] {:ok (:id plan)}))

(deftest dispatch-strategy-contract
  (testing "IDispatchStrategy exposes exactly #{dispatch}"
    (is (= '#{dispatch} (->> @#'s/IDispatchStrategy :sigs vals (map :name) set))))
  (testing "a stub record satisfies it and dispatches"
    (is (s/dispatch-strategy? (->StubStrategy)))
    (is (= {:ok "p1"} (s/dispatch (->StubStrategy) {:id "p1"} {}))))
  (testing "a plain map does not satisfy it"
    (is (not (s/dispatch-strategy? {})))))

;; ---------------------------------------------------------------------------
;; WorkflowStrategyEntry — the addon contribution ADT
;; ---------------------------------------------------------------------------

(deftest workflow-strategy-entry-adt
  (testing "defadt interns the four vars hive-mcp must re-export"
    (doseq [sym '[WorkflowStrategyEntry workflow-strategy-entry
                  ->workflow-strategy-entry workflow-strategy-entry?]]
      (is (some? (ns-resolve 'hive-spi.workflow.strategy sym))
          (str sym " must exist — hive-mcp.workflows.strategy aliases it"))))
  (testing "the closed sum has exactly one variant"
    (is (= #{:wf/strategy} (:variants s/WorkflowStrategyEntry))))
  (testing "a constructed entry carries the ADT envelope and passes the predicate"
    (let [e (s/workflow-strategy-entry :wf/strategy
                                       {:method :dag-wave
                                        :strategy (->StubStrategy)
                                        :owner :hive.workflows.strategy})]
      (is (s/workflow-strategy-entry? e))
      (is (= :wf/strategy (:adt/variant e)))
      (is (= :dag-wave (:method e)))))
  (testing "a plain map is not an entry"
    (is (not (s/workflow-strategy-entry? {:method :dag-wave})))))

;; ---------------------------------------------------------------------------
;; The re-export contract strategy-registry/register-by-key! depends on
;; ---------------------------------------------------------------------------

;; Exactly what hive-mcp.workflows.strategy does. Symbol name MUST match:
;; adt-case resolves its ADT argument by symbol name.
(def WorkflowStrategyEntry s/WorkflowStrategyEntry)
(def workflow-strategy-entry? s/workflow-strategy-entry?)

(deftest adt-case-through-a-def-alias
  (let [entry (s/workflow-strategy-entry :wf/strategy
                                         {:method :saa
                                          :strategy (->StubStrategy)
                                          :owner :test})]
    (testing "the alias holds the identical ADT value"
      (is (identical? WorkflowStrategyEntry s/WorkflowStrategyEntry)))
    (testing "the aliased predicate still recognises entries"
      (is (workflow-strategy-entry? entry)))
    (testing "adt-case dispatches through the ALIASED symbol (register-by-key! path)"
      (is (= [:registered :saa]
             (adt-case WorkflowStrategyEntry entry
               :wf/strategy [:registered (:method entry)]))))))
