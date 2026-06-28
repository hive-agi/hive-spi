(ns hive-spi.role.card
  "RoleCard — pure value-type + malli schema for a per-workflow-step persona
   binding (HWF2 RoleCards).

   A RoleCard binds the four things a unit of work needs to run as a distinct
   persona:
     :role/system-prompt — who it is,
     :role/model         — which model (by capability ALIAS, not raw id),
     :role/tools         — what tools it may use (job-scoped),
     :role/memory        — which base+specific memory bundle it carries.

   The card is plain data (LLM-emittable EDN), persisted as the `:role` memory
   type and referenced by `:role/id` keyword from a plan step / wf-IR node.
   This namespace provides the schema, FAIL-LOUD gates (`valid?`/`explain`),
   the model-alias VOCABULARY, and a smart constructor `role-card`.

   Mirrors hive-mcp.agent.agent-definition.spec/AgentDefinition (system-prompt
   + model + tools) but: (1) keyed by a namespaced :role/id rather than an
   agent-type, (2) memory-backed rather than .md-file-backed, (3) addresses
   models by alias/tier rather than raw strings, (4) carries an explicit
   memory-context selector.

   Resolution of alias -> concrete id + provider, tool-spec -> allowlist, and
   selector -> bundle is the job of the hive-spi.role.ports concretions, NOT
   this pure leaf."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ===========================================================================
;;; Model alias vocabulary
;;; ---------------------------------------------------------------------------
;;; The IDENTITY axis (capability), kept separate from the COST axis (tier,
;;; owned by hive-mcp.agent.budget-router). `default-model-aliases` is a sane
;;; default the IModelRouter concretion MAY override from config — model ids
;;; are generation-specific, so the router is the authoritative resolver; this
;;; map exists so the schema has a vocabulary and the system works out of the
;;; box. Bare claude-* ids auto-route to native Anthropic OAuth downstream.
;;; ===========================================================================

(def default-model-aliases
  "Capability alias -> concrete current model id (defaults; router-overridable).
   Kept current per the claude-api reference; the in-repo config ids may lag."
  {:opus   "claude-opus-4-8"
   :sonnet "claude-sonnet-4-6"
   :haiku  "claude-haiku-4-5"
   :fable  "claude-fable-5"})

(def model-alias-keys
  "The set of keyword model aliases a RoleCard :role/model may use."
  (set (keys default-model-aliases)))

(defn expand-alias
  "Expand a model ref to a concrete id using `default-model-aliases`.
   A keyword alias resolves to its id; a string passes through; :inherit and
   unknown keywords return nil (the router decides policy for those).
   This is the DEFAULT expansion — the IModelRouter concretion may override
   the alias map from config."
  [model-ref]
  (cond
    (string? model-ref)  model-ref
    (= :inherit model-ref) nil
    (keyword? model-ref) (get default-model-aliases model-ref)
    :else nil))

;;; ===========================================================================
;;; Sub-schemas
;;; ===========================================================================

(def ModelRef
  "A RoleCard model reference: capability alias, raw id string, or :inherit."
  (m/schema
   [:or
    (into [:enum :inherit] model-alias-keys)
    :string]))

(def ToolSpec
  "A RoleCard tool grant: a tools.profiles preset key, an explicit allow/deny
   map, or nil for no restriction."
  (m/schema
   [:maybe
    [:or
     :keyword
     [:map {:closed false}
      [:allow {:optional true} [:vector :string]]
      [:deny  {:optional true} [:vector :string]]]]]))

(def MemorySelector
  "A RoleCard memory-context selector: which base+specific bundle to assemble.
   All keys optional; an empty selector means 'base only' (axioms/principles)."
  (m/schema
   [:maybe
    [:map {:closed false}
     [:tags    {:optional true} [:vector :string]]
     [:types   {:optional true} [:vector :keyword]]
     [:scope   {:optional true} [:or :keyword :string]]
     [:queries {:optional true} [:vector :string]]]]))

;;; ===========================================================================
;;; RoleCard schema
;;; ---------------------------------------------------------------------------
;;; Open map ({:closed false}) so deployments may carry extra :role/* keys
;;; (e.g. :role/max-turns, :role/spawn-mode) without a schema change — mirrors
;;; AgentDefinition. Only :role/id and :role/name are required.
;;; ===========================================================================

(def RoleCard
  "Malli schema for a RoleCard value.

   Shape:
     {:role/id            keyword (required) — lookup key, e.g. :role/reviewer
      :role/name          string  (required) — human label
      :role/description   string  (optional)
      :role/system-prompt string  (optional) — persona prompt; nil => default
      :role/model         ModelRef (optional, default :inherit)
      :role/tools         ToolSpec (optional, default nil => no restriction)
      :role/memory        MemorySelector (optional, default nil => base only)
      :role/tags          [string] (optional) — free tags for list-roles}"
  (m/schema
   [:map {:closed false}
    [:role/id            :keyword]
    [:role/name          :string]
    [:role/description   {:optional true} [:maybe :string]]
    [:role/system-prompt {:optional true} [:maybe :string]]
    [:role/model         {:optional true} ModelRef]
    [:role/tools         {:optional true} ToolSpec]
    [:role/memory        {:optional true} MemorySelector]
    [:role/tags          {:optional true} [:vector :string]]]))

(defn valid?
  "True if `x` conforms to `RoleCard`. Pure; never throws."
  [x]
  (m/validate RoleCard x))

(defn explain
  "Return a malli explanation map for `x`, or nil if it conforms.
   Pure; never throws."
  [x]
  (m/explain RoleCard x))

;;; ===========================================================================
;;; Smart constructor
;;; ===========================================================================

(def default-card
  "Defaults applied by `role-card` for unset optional keys."
  {:role/model  :inherit
   :role/tools  nil
   :role/memory nil})

(defn role-card
  "Build a validated RoleCard from a partial map, filling defaults.
   FAIL-LOUD: throws ex-info {:error :role/invalid :explanation <malli>} if the
   result does not conform (mirrors hive-spi.workflow.ast/->edn)."
  [m]
  (let [card (merge default-card m)]
    (when-not (valid? card)
      (throw (ex-info "Not a valid RoleCard"
                      {:error :role/invalid
                       :explanation (explain card)})))
    card))
