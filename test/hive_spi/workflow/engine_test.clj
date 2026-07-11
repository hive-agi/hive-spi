(ns hive-spi.workflow.engine-test
  "Contract tests for hive-spi.workflow.engine (HWF2 D1b).

   Proves (a) the two ports exist with the exact method surface relocated from
   hive-mcp.protocols.workflow, (b) a stub impl satisfies them, (c) the
   `def`-alias re-export contract that hive-mcp.protocols.workflow relies on
   actually holds — a record defined against an ALIASED protocol var still
   satisfies the original, and a second `defprotocol` does NOT."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.workflow.engine :as e]))

;; ---------------------------------------------------------------------------
;; Helpers (mirrors hive-spi.workflow.ports-test)
;; ---------------------------------------------------------------------------

(defn- protocol-method-names [pvar]
  (->> @pvar :sigs vals (map :name) set))

(defn- assert-protocol [pvar expected-methods]
  (let [m (protocol-method-names pvar)]
    (is (= expected-methods m)
        (str "protocol " pvar " should expose exactly " expected-methods
             " — found " m))))

;; ---------------------------------------------------------------------------
;; IWorkflowEngine
;; ---------------------------------------------------------------------------

(defrecord StubEngine []
  e/IWorkflowEngine
  (load-workflow     [_ n _] {:name n :loaded? true})
  (validate-workflow [_ _]   {:valid? true})
  (execute-step      [_ _ s _] {:success? true :step-id s})
  (execute-workflow  [_ _ _] {:success? true})
  (get-status        [_ id]  {:workflow-id id :status :done})
  (cancel-workflow   [_ id _] {:success? true :workflow-id id}))

(deftest workflow-engine-contract
  (testing "IWorkflowEngine exposes exactly the relocated method surface"
    (assert-protocol #'e/IWorkflowEngine
                     '#{load-workflow validate-workflow execute-step
                        execute-workflow get-status cancel-workflow}))
  (testing "a stub record satisfies IWorkflowEngine"
    (is (satisfies? e/IWorkflowEngine (->StubEngine)))
    (is (e/workflow-engine? (->StubEngine))))
  (testing "a non-impl does not satisfy it"
    (is (not (e/workflow-engine? {})))))

;; ---------------------------------------------------------------------------
;; IWorkflowPersistence
;; ---------------------------------------------------------------------------

(defrecord StubPersistentEngine []
  e/IWorkflowEngine
  (load-workflow     [_ n _] {:name n})
  (validate-workflow [_ _]   {:valid? true})
  (execute-step      [_ _ _ _] {:success? true})
  (execute-workflow  [_ _ _] {:success? true})
  (get-status        [_ _]   {:status :done})
  (cancel-workflow   [_ _ _] {:success? true})
  e/IWorkflowPersistence
  (save-state     [_ _ _] :saved)
  (load-state     [_ _]   {})
  (list-workflows [_ _]   []))

(deftest workflow-persistence-contract
  (testing "IWorkflowPersistence exposes the expected methods"
    (assert-protocol #'e/IWorkflowPersistence
                     '#{save-state load-state list-workflows}))
  (testing "persistence is an OPT-IN sibling — a plain engine does not satisfy it"
    (is (not (e/persistent-engine? (->StubEngine))))
    (is (e/persistent-engine? (->StubPersistentEngine)))))

;; ---------------------------------------------------------------------------
;; The re-export contract that hive-mcp.protocols.workflow depends on
;; ---------------------------------------------------------------------------

(def AliasedEngine e/IWorkflowEngine)
(def aliased-load-workflow e/load-workflow)

(defrecord ViaAlias []
  AliasedEngine
  (load-workflow     [_ _ _] :via-alias)
  (validate-workflow [_ _]   {:valid? true})
  (execute-step      [_ _ _ _] {:success? true})
  (execute-workflow  [_ _ _] {:success? true})
  (get-status        [_ _]   {:status :done})
  (cancel-workflow   [_ _ _] {:success? true}))

(deftest def-alias-reexport-contract
  (testing "a record defined against a `def` alias satisfies the ORIGINAL protocol"
    (is (satisfies? e/IWorkflowEngine (->ViaAlias)))
    (is (instance? (:on-interface e/IWorkflowEngine) (->ViaAlias))))
  (testing "method vars dispatch identically through alias and original"
    (is (= :via-alias (e/load-workflow (->ViaAlias) "w" {})))
    (is (= :via-alias (aliased-load-workflow (->ViaAlias) "w" {}))))
  (testing "the alias holds the very same protocol map"
    (is (identical? AliasedEngine e/IWorkflowEngine))))

(deftest second-defprotocol-is-a-distinct-protocol
  (testing "re-`defprotocol`-ing the name mints a DIFFERENT protocol (the trap D1b must avoid)"
    (let [trap-ns (create-ns 'hive-spi.workflow.engine-test.trap)]
      (try
        (binding [*ns* trap-ns]
          (refer-clojure)
          (eval '(defprotocol IWorkflowEngine (load-workflow [this n opts]))))
        (let [trap @(ns-resolve trap-ns 'IWorkflowEngine)]
          (is (not (identical? trap e/IWorkflowEngine)))
          (is (not (satisfies? trap (->StubEngine))))
          (is (satisfies? e/IWorkflowEngine (->StubEngine))))
        (finally (remove-ns (ns-name trap-ns)))))))
