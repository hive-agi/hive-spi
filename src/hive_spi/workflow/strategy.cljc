(ns hive-spi.workflow.strategy
  "Dispatch-strategy port + the closed contribution ADT for the :method seam.

   Implementations can BUILD WorkflowStrategyEntry values and IMPLEMENT
   IDispatchStrategy to contribute a strategy through a registry, without
   depending on the host.

   A plan's :method field selects an IDispatchStrategy from the strategy
   registry. A strategy turns a normalized plan into running work — a wave of
   lings, an SAA cycle, a forge belt.

   Pure contract.

   INVARIANTS
   * Never re-`defprotocol` IDispatchStrategy downstream — a second protocol is
     a DISTINCT protocol and `satisfies?` silently returns false.
   * Never re-`defadt` WorkflowStrategyEntry downstream — hive-dsl.adt keys its
     registry on the BARE type name (:WorkflowStrategyEntry), so a duplicate
     definition is last-loaded-wins across the whole JVM.
   * A downstream alias MUST keep the symbol spelled `WorkflowStrategyEntry`:
     `adt-case` resolves its ADT argument by symbol name."
  (:require [hive-dsl.adt :refer [defadt]]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ===========================================================================
;;; IDispatchStrategy — selected by a plan's :method
;;; ===========================================================================

(defprotocol IDispatchStrategy
  "Turns a normalized plan into running work. Selected by the plan's :method."
  (dispatch [this plan opts]
    "Dispatch a normalized plan. Returns a hive-dsl Result ({:ok _} / {:error _})."))

(defn dispatch-strategy?
  "True if x satisfies IDispatchStrategy."
  [x]
  (satisfies? IDispatchStrategy x))

;;; ===========================================================================
;;; WorkflowStrategyEntry — what addons contribute to the strategy registry
;;; ---------------------------------------------------------------------------
;;; Interns: WorkflowStrategyEntry, workflow-strategy-entry (ctor),
;;;          ->workflow-strategy-entry (coercer), workflow-strategy-entry? (pred).
;;; ===========================================================================

(defadt WorkflowStrategyEntry
  "Addon contribution routed by the \"wf\" hook-key namespace to the strategy
   registry.

   :wf/strategy — registers `:strategy` (an IDispatchStrategy) under `:method`."
  [:wf/strategy {:method keyword? :strategy (constantly true) :owner keyword?}])
