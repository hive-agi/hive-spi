(ns hive-spi.role.ports
  "Service Provider Interface (SPI) ports for RoleCards — the per-workflow-step
   binding of {system-prompt, model, tools, memory-context} that lets each unit
   of work run as a distinct persona on a job-appropriate model with a curated
   base+specific memory bundle and a job-scoped toolset.

   Pure protocol stubs — NO implementations live here. Each method docstring
   states its CONTRACT (argument shapes + return shape + failure semantics).
   Concretions live in hive-mcp.* / hive-knowledge.* / hive-agent.* and are
   wired via `satisfies?`-probed registries (mirroring strategy-registry).

   ENGINE-AGNOSTIC BY DESIGN. The same overlay produced by `IRoleComposer`
   is consumed at three points over the engine's lifetime:
     (a) the live ling-spawn path  (compute-spawn-plan -> ling-ctx)  — Stage A,
     (b) HWF-M3 IDispatchStrategy wrappers (dag-wave / saa / forge)  — Stage B,
     (c) the HWF2 CombinatorWorkflowEngine via wf-IR :wf/args :role    — Stage C.
   Build the resolver once; consume it in three places.

   RoleCards are persisted as the `:role` memory type (queryable / KG-linked /
   supersedable) and REFERENCED by id from a plan step's `:role` field, which
   rides hive-mcp.plan.field-registry exactly like `:method` (per-step value,
   plan-level `:default-role` inheritance). The wf-IR carrier stays pure EDN:
   a node references a role by keyword in :wf/args, it never inlines the card.

   Composition (why five small ports, not one): IRoleComposer depends on the
   other four via injection, so each concretion is independently implementable
   and verifiable against its own contract — they can be built in parallel
   with no cross-dependency."

  ;; Intentionally NO :require. SPI is a pure-contract leaf.
  )

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ===========================================================================
;;; IRoleStore
;;; ---------------------------------------------------------------------------
;;; Read/write facade for RoleCards. The canonical concretion is backed by the
;;; `:role` memory type (one card == one memory entry, scope-tagged), but an
;;; EDN-file or in-memory test double can back the same contract.
;;; ===========================================================================

(defprotocol IRoleStore
  "Persistence + lookup facade for RoleCards (see hive-spi.role.card/RoleCard)."

  (get-role
    [this role-ref]
    "Resolve one RoleCard.
     Arguments:
       role-ref — keyword role id (e.g. :role/reviewer), OR an already-inline
                  RoleCard map (returned as-is after validation).
     Returns: a conformant RoleCard map, or nil if no such role.
     Pure read; never throws on miss (nil, not exception).")

  (list-roles
    [this query]
    "List RoleCards matching query.
     Arguments:
       query — Map. Recognised keys (all optional):
               :tags       [string ...] — all-of tag filter.
               :project-id string       — scope filter.
               :limit      int.
     Returns: vector of RoleCard maps (possibly empty). Pure read.")

  (put-role!
    [this role-card]
    "Persist (create or supersede) a RoleCard.
     Arguments:
       role-card — a RoleCard map; MUST carry :role/id and :role/name.
     Returns: {:stored? true :role/id <kw> :revision int}.
     Throws: ex-info {:error :role/invalid :explanation <malli>} when the card
             does not conform to RoleCard."))

;;; ===========================================================================
;;; IModelRouter
;;; ---------------------------------------------------------------------------
;;; The model IDENTITY axis (alias -> concrete id + provider), kept separate
;;; from the COST axis (tier). A RoleCard says `:role/model :opus`; the router
;;; resolves that to a concrete provider+id and (separately) classifies its
;;; cost tier. Concretion composes hive-spi.role.card/default-model-aliases
;;; (overridable by config) with the existing provider + tier machinery.
;;; ===========================================================================

(defprotocol IModelRouter
  "Resolve a RoleCard model reference to a concrete provider + model id."

  (resolve-model
    [this model-ref]
    "Resolve an alias / id / :inherit to a concrete provider+model.
     Arguments:
       model-ref — keyword alias (#{:opus :sonnet :haiku :fable ...}),
                   a raw model-id string, or :inherit.
     Returns: {:provider <kw> :model <string>} on a concrete ref, or nil when
              model-ref is :inherit (meaning: do NOT override the spawn default).
     Throws: ex-info {:error :model/unknown-alias :ref ...} on an unknown
             keyword alias (a raw string is passed through, never rejected).")

  (model-tier
    [this model-ref]
    "Classify the COST tier of a model ref (identity-agnostic).
     Returns: one of #{:free :economy :standard :premium}, derived after
              alias expansion. nil for :inherit. Pure; never throws.")

  (known-aliases
    [this]
    "Return the set of keyword aliases this router can expand. Pure read."))

;;; ===========================================================================
;;; IToolGranter
;;; ---------------------------------------------------------------------------
;;; Turns a RoleCard tool-spec into the concrete allow/deny + sub-agent grant
;;; the spawn backends consume. Bridges the two existing tool surfaces:
;;; hive-agent.tools.profiles preset keys and AgentDefinition :tools/:disallowed.
;;; ===========================================================================

(defprotocol IToolGranter
  "Resolve a RoleCard tool-spec into a concrete tool grant."

  (grant
    [this tool-spec]
    "Resolve a tool-spec to a concrete grant.
     Arguments:
       tool-spec — one of:
         * keyword         — a tools.profiles preset key (e.g. :code-reviewer),
         * {:allow [string] :deny [string]} — explicit allow/deny lists
                              (:allow [\"*\"] means all tools),
         * nil             — no restriction (backend default / all tools).
     Returns: {:tools [string ...]            ; resolved allow-list (tool names)
               :disallowed-tools [string ...] ; resolved deny-list
               :agents [string ...]}          ; sub-agent definition names, if any
              Any key may be empty. Pure; deterministic for a given spec.
     Throws: ex-info {:error :tools/unknown-preset :preset ...} on an unknown
             preset keyword.")

  (known-presets
    [this]
    "Return the set of preset keywords this granter recognises. Pure read."))

;;; ===========================================================================
;;; IMemoryBundler
;;; ---------------------------------------------------------------------------
;;; Assembles the "base + specific" memory context for a role on a task. BASE =
;;; always-on axioms/principles (scope-piercing); SPECIFIC = role-tagged +
;;; task-semantic recall. Composes catchup.bundle + agent.hints under one
;;; token budget. This is the "custom memory-context per persona" surface.
;;; ===========================================================================

(defprotocol IMemoryBundler
  "Assemble a curated base+specific memory bundle for a role + task."

  (assemble
    [this selector ctx]
    "Build a memory-context bundle.
     Arguments:
       selector — the RoleCard :role/memory map (any key optional):
                  {:tags [string ...]   — SPECIFIC: role/job tag filter,
                   :types [keyword ...]  — restrict to these memory types,
                   :scope keyword|string — scope descriptor (default :auto),
                   :queries [string ...] — extra semantic queries}.
       ctx      — {:project-id string :task string :cwd string
                   :token-budget int (chars; default budgeted by concretion)}.
     Returns: {:entries [<memory-entry> ...]  ; deduped base+specific, ranked
               :text    string                ; rendered, budget-trimmed
               :token-estimate int}.
     Pure read over the memory store; never throws on empty (returns an empty
     bundle, :text \"\")."))

;;; ===========================================================================
;;; IRoleComposer
;;; ---------------------------------------------------------------------------
;;; The fold. Resolves a role reference (+ per-step overrides) into a spawn-opts
;;; OVERLAY that merges into ling-ctx before strategy-spawn!. The single value
;;; consumed at all three engine stages. Depends on the other four ports.
;;; ===========================================================================

(defprotocol IRoleComposer
  "Compose a RoleCard (+ per-step overrides) into a spawn-opts overlay."

  (compose
    [this role-ref step ctx]
    "Produce the spawn-opts overlay for one unit of work.
     Arguments:
       role-ref — keyword role id, inline RoleCard, or nil (no role => empty
                  overlay; spawn proceeds with its own defaults).
       step     — the plan step / wf-IR node map. Per-step keys OVERRIDE the
                  RoleCard: :model (model ref), :role (id), and any inline
                  overrides ride here. May be {} when composing outside a plan.
       ctx      — {:project-id string :task string :cwd string
                   :token-budget int}. Carries what the bundler/router need.
     Returns: a spawn-opts OVERLAY map, additive over ling-ctx (every key
              optional; unset role => {}):
              {:model            string   ; concrete id (alias-expanded)
               :provider         keyword
               :system-prompt    string   ; persona prompt (-> :preset-content)
               :presets          [string] ; preset names, if role names presets
               :tools            [string]
               :disallowed-tools [string]
               :agents           [string]
               :context-bundle   {:text :entries :token-estimate}  ; from bundler
               :role/id          keyword  ; provenance, for telemetry/KG
               :role/tier        keyword} ; cost tier of the resolved model
     Throws: only on a hard resolution error (e.g. role-ref id refers to a
             stored card that fails validation); a missing/nil role is NOT an
             error — it yields {}."))
