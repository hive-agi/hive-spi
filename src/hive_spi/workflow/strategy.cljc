(ns hive-spi.workflow.strategy
  "Dispatch-strategy port + the closed contribution ADT for the :method seam
   (HWF2 D1b).

   Relocated from hive-mcp.workflows.strategy so an addon (hive-workflows) can
   BUILD WorkflowStrategyEntry values and IMPLEMENT IDispatchStrategy without
   depending on hive-mcp. That is what lets the addon contribute through the
   IAddon `(hooks)` seam — `hive-mcp.workflows.strategy-registry/register-by-key!`
   — instead of reaching into the host with `requiring-resolve`.

   A plan's :method field selects an IDispatchStrategy from the strategy
   registry. A strategy turns a normalized plan into running work — a wave of
   lings, an SAA cycle, a forge belt.

   Pure contract. NoopDispatchStrategy (an impl, and the only thing here that
   needed hive-dsl.result) stays in hive-mcp.workflows.strategy, which re-exports
   every var below as a plain `def` alias.

   INVARIANTS
   * Never re-`defprotocol` IDispatchStrategy downstream — a second protocol is
     a DISTINCT protocol and `satisfies?` silently returns false.
   * Never re-`defadt` WorkflowStrategyEntry downstream — hive-dsl.adt keys its
     registry on the BARE type name (:WorkflowStrategyEntry), so a duplicate
     definition is last-loaded-wins across the whole JVM.
   * The downstream alias MUST keep the symbol spelled `WorkflowStrategyEntry`:
     `adt-case` resolves its ADT argument by symbol name."
  (:require [hive-dsl.adt :refer [defadt]]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
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
;;; All four must be re-exported by hive-mcp.workflows.strategy.
;;; ===========================================================================

(defadt WorkflowStrategyEntry
  "Addon contribution routed by the \"wf\" hook-key namespace to the strategy
   registry.

   :wf/strategy — registers `:strategy` (an IDispatchStrategy) under `:method`."
  [:wf/strategy {:method keyword? :strategy (constantly true) :owner keyword?}])
