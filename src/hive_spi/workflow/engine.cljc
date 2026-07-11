(ns hive-spi.workflow.engine
  "Workflow-execution ports: IWorkflowEngine + IWorkflowPersistence (HWF2 D1b).

   Relocated from hive-mcp.protocols.workflow so hive-workflows can implement
   them without depending on hive-mcp (HWF2-M9: hive-workflows' deps are ONLY
   hive-spi + hive-dsl + hive-events).

   Pure protocol stubs — NO implementations. NoopWorkflowEngine and the active-
   engine slot stay in hive-mcp.protocols.workflow, which re-exports the vars
   below as plain `def` aliases.

   INVARIANT — never re-`defprotocol` these names downstream. A second
   `defprotocol` mints a DISTINCT protocol; records implementing the original
   then fail `satisfies?` silently. Alias with `def`, always."

  ;; Intentionally NO :require. SPI is a pure-contract leaf.
  )

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ===========================================================================
;;; IWorkflowEngine
;;; ---------------------------------------------------------------------------
;;; The execution port. hive-mcp.workflows.fsm-engine satisfies it today; the
;;; HWF2-M7 CombinatorWorkflowEngine (hive-workflows) will satisfy it next.
;;; ===========================================================================

(defprotocol IWorkflowEngine
  "Protocol for workflow execution engines."

  (load-workflow [this workflow-name opts]
    "Load a workflow definition by name.")

  (validate-workflow [this workflow]
    "Validate a loaded workflow definition.")

  (execute-step [this workflow step-id opts]
    "Execute a single step within a workflow.")

  (execute-workflow [this workflow opts]
    "Execute all steps in a workflow respecting dependency order.")

  (get-status [this workflow-id]
    "Get the current status of a workflow execution.")

  (cancel-workflow [this workflow-id opts]
    "Cancel a running workflow."))

;;; ===========================================================================
;;; IWorkflowPersistence — optional sibling extension
;;; ===========================================================================

(defprotocol IWorkflowPersistence
  "Optional extension for durable workflow state."

  (save-state [this workflow-id state]
    "Persist current workflow execution state.")

  (load-state [this workflow-id]
    "Load persisted workflow state.")

  (list-workflows [this opts]
    "List workflow executions matching criteria."))

;;; ===========================================================================
;;; Predicates
;;; ===========================================================================

(defn workflow-engine?
  "True if x satisfies IWorkflowEngine."
  [x]
  (satisfies? IWorkflowEngine x))

(defn persistent-engine?
  "True if x satisfies IWorkflowPersistence."
  [x]
  (satisfies? IWorkflowPersistence x))
