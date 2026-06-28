(ns hive-spi.role.ports
  "SPI ports for RoleCards — the per-step binding of
   {system-prompt, model, tools, memory-context} so a unit of work can run as a
   distinct persona on a chosen model with a curated memory bundle and a
   job-scoped toolset.

   Pure protocol stubs — NO implementations live here. Each method docstring
   states its CONTRACT (argument shapes + return shape + failure semantics).

   Five small ports rather than one: IRoleComposer depends on the other four by
   injection, so each concretion is independently implementable and verifiable
   against its own contract.")

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

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
    "Classify the cost tier of a model ref.
     Returns: one of #{:free :economy :standard :premium}, derived after
              alias expansion. nil for :inherit. Pure; never throws.")

  (known-aliases
    [this]
    "Return the set of keyword aliases this router can expand. Pure read."))

(defprotocol IToolGranter
  "Resolve a RoleCard tool-spec into a concrete tool grant."

  (grant
    [this tool-spec]
    "Resolve a tool-spec to a concrete grant.
     Arguments:
       tool-spec — one of:
         * keyword         — a named tool-profile preset,
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

(defprotocol IMemoryBundler
  "Assemble a curated base+specific memory bundle for a role + task."

  (assemble
    [this selector ctx]
    "Build a memory-context bundle.
     Arguments:
       selector — the RoleCard :role/memory map (any key optional):
                  {:tags [string ...]   — role/job tag filter,
                   :types [keyword ...]  — restrict to these memory types,
                   :scope keyword|string — scope descriptor (default :auto),
                   :queries [string ...] — extra semantic queries}.
       ctx      — {:project-id string :task string :cwd string
                   :token-budget int (chars; default budgeted by concretion)}.
     Returns: {:entries [<memory-entry> ...]  ; deduped, ranked
               :text    string                ; rendered, budget-trimmed
               :token-estimate int}.
     Pure read over the memory store; never throws on empty (returns an empty
     bundle, :text \"\")."))

(defprotocol IRoleComposer
  "Compose a RoleCard (+ per-step overrides) into a spawn-opts overlay."

  (compose
    [this role-ref step ctx]
    "Produce the spawn-opts overlay for one unit of work.
     Arguments:
       role-ref — keyword role id, inline RoleCard, or nil (no role => empty
                  overlay; spawn proceeds with its own defaults).
       step     — the step map. Per-step keys OVERRIDE the RoleCard: :model
                  (model ref), :role (id), and any inline overrides ride here.
                  May be {} when composing outside a plan.
       ctx      — {:project-id string :task string :cwd string
                   :token-budget int}. Carries what the bundler/router need.
     Returns: a spawn-opts OVERLAY map, additive over the spawn defaults (every
              key optional; unset role => {}):
              {:model            string   ; concrete id (alias-expanded)
               :provider         keyword
               :system-prompt    string   ; persona prompt
               :presets          [string] ; preset names, if role names presets
               :tools            [string]
               :disallowed-tools [string]
               :agents           [string]
               :context-bundle   {:text :entries :token-estimate}
               :role/id          keyword
               :role/tier        keyword} ; cost tier of the resolved model
     Throws: only on a hard resolution error (e.g. role-ref id refers to a
             stored card that fails validation); a missing/nil role is NOT an
             error — it yields {}."))
