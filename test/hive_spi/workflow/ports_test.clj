(ns hive-spi.workflow.ports-test
  "Contract-scaffold tests for hive-spi.workflow.ports.

   Per-protocol `satisfies?`-style smoke tests prove that
     (a) the protocols exist and load,
     (b) the method-name surface matches the expected contract, and
     (c) a do-nothing impl satisfies the protocol and can be probed."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.workflow.ports :as p]))

;; ---------------------------------------------------------------------------
;; Generic helpers
;; ---------------------------------------------------------------------------

(defn- protocol-method-names
  "Return the set of method-name symbols defined on protocol var pvar."
  [pvar]
  (->> @pvar :sigs vals (map :name) set))

(defn- assert-protocol
  "Assert that pvar is a protocol with the expected method-name set."
  [pvar expected-methods]
  (let [m (protocol-method-names pvar)]
    (is (= expected-methods m)
        (str "protocol " pvar " should expose exactly " expected-methods
             " — found " m))))

;; ---------------------------------------------------------------------------
;; IPlanCompiler
;; ---------------------------------------------------------------------------

(defrecord NoopPlanCompiler []
  p/IPlanCompiler
  (compile-plan  [_ _ _] {:wf/op :wf.op/seq :wf/children []})
  (validate-plan [_ _]   {:valid? true :errors [] :warnings []}))

(deftest plan-compiler-contract
  (testing "IPlanCompiler exposes the expected methods"
    (assert-protocol #'p/IPlanCompiler '#{compile-plan validate-plan}))
  (testing "a stub record satisfies IPlanCompiler"
    (is (satisfies? p/IPlanCompiler (->NoopPlanCompiler)))))

;; ---------------------------------------------------------------------------
;; IPlanGraph
;; ---------------------------------------------------------------------------

(defrecord NoopPlanGraph []
  p/IPlanGraph
  (nodes     [_]   [])
  (edges     [_]   #{})
  (node-data [_ _] nil)
  (waves     [_]   [])
  (roots     [_]   #{})
  (leaves    [_]   #{}))

(deftest plan-graph-contract
  (testing "IPlanGraph exposes the expected methods"
    (assert-protocol #'p/IPlanGraph
                     '#{nodes edges node-data waves roots leaves}))
  (testing "a stub record satisfies IPlanGraph"
    (is (satisfies? p/IPlanGraph (->NoopPlanGraph)))))

;; ---------------------------------------------------------------------------
;; ITaskBoard
;; ---------------------------------------------------------------------------

(defrecord NoopTaskBoard []
  p/ITaskBoard
  (list-tasks   [_ _]   [])
  (get-task     [_ _]   nil)
  (create-task! [_ t]   (assoc t :task/id "noop-0"))
  (update-task! [_ _ _] nil))

(deftest task-board-contract
  (testing "ITaskBoard exposes the expected methods"
    (assert-protocol #'p/ITaskBoard
                     '#{list-tasks get-task create-task! update-task!}))
  (testing "a stub record satisfies ITaskBoard"
    (is (satisfies? p/ITaskBoard (->NoopTaskBoard)))))

;; ---------------------------------------------------------------------------
;; IHeadlessDispatcher
;; ---------------------------------------------------------------------------

(defrecord NoopDispatcher []
  p/IHeadlessDispatcher
  (dispatcher-key       [_]      :noop)
  (dispatcher-spawn!    [_ _ _]  "noop-slave")
  (dispatcher-dispatch! [_ _ _]  true)
  (dispatcher-status    [_ _ _]  nil)
  (dispatcher-kill!     [_ _]    true))

(deftest headless-dispatcher-contract
  (testing "IHeadlessDispatcher exposes the expected methods"
    (assert-protocol #'p/IHeadlessDispatcher
                     '#{dispatcher-key dispatcher-spawn! dispatcher-dispatch!
                        dispatcher-status dispatcher-kill!}))
  (testing "a stub record satisfies IHeadlessDispatcher"
    (is (satisfies? p/IHeadlessDispatcher (->NoopDispatcher)))))

;; ---------------------------------------------------------------------------
;; IWorkflowStore
;; ---------------------------------------------------------------------------

(defrecord NoopWorkflowStore []
  p/IWorkflowStore
  (put-workflow!    [_ id _] {:stored? true :workflow-id id :revision 0})
  (get-workflow     [_ _]    nil)
  (list-workflows   [_ _]    [])
  (delete-workflow! [_ id]   {:deleted? false :workflow-id id}))

(deftest workflow-store-contract
  (testing "IWorkflowStore exposes the expected methods"
    (assert-protocol #'p/IWorkflowStore
                     '#{put-workflow! get-workflow list-workflows
                        delete-workflow!}))
  (testing "a stub record satisfies IWorkflowStore"
    (is (satisfies? p/IWorkflowStore (->NoopWorkflowStore)))))

;; ---------------------------------------------------------------------------
;; IEffectHandler
;; ---------------------------------------------------------------------------

(defrecord NoopEffectHandler []
  p/IEffectHandler
  (verb-id       [_]     :wf/noop)
  (verb-tags     [_]     #{:fx})
  (handle-effect [_ c _] c)
  (argv-schema   [_]     nil))

(deftest effect-handler-contract
  (testing "IEffectHandler exposes the expected methods"
    (assert-protocol #'p/IEffectHandler
                     '#{verb-id verb-tags handle-effect argv-schema}))
  (testing "a stub record satisfies IEffectHandler"
    (is (satisfies? p/IEffectHandler (->NoopEffectHandler))))
  (testing "verb-id is namespaced (refuse un-namespaced core keys)"
    (is (some? (namespace (p/verb-id (->NoopEffectHandler)))))))

;; ---------------------------------------------------------------------------
;; IIntrospectable
;; ---------------------------------------------------------------------------

(defrecord NoopIntrospectable []
  p/IIntrospectable
  (describe [_] {:id :noop :title "Noop" :doc "stub"}))

(defrecord SomethingOpaque [])

(deftest introspectable-contract
  (testing "IIntrospectable exposes the expected methods"
    (assert-protocol #'p/IIntrospectable '#{describe}))
  (testing "a stub record satisfies IIntrospectable"
    (is (satisfies? p/IIntrospectable (->NoopIntrospectable))))
  (testing "IIntrospectable is sibling/opt-in — opaque records do NOT satisfy it"
    (is (not (satisfies? p/IIntrospectable (->SomethingOpaque))))))

;; ---------------------------------------------------------------------------
;; Cross-cutting sanity
;; ---------------------------------------------------------------------------

(deftest deferred-ports-not-exposed
  (testing "the deferred protocol symbols are not defined here (sanity guard)"
    (doseq [sym '[IWorkflowEngine IDispatchStrategy WorkflowStrategyEntry
                  WorkflowEvent INotify]]
      (is (nil? (ns-resolve 'hive-spi.workflow.ports sym))
          (str sym " is deferred and MUST NOT be defined here.")))))
