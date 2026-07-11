(ns hive-spi.workflow.ports
  "Service Provider Interface (SPI) ports for the hive-workflows rebuild (HWF2).

   Pure protocol stubs — NO implementations live here. Each protocol method
   docstring states its CONTRACT (argument shapes + return shape + failure
   semantics). Implementations live in hive-workflows.*, hive-mcp.*, or
   downstream addons and are wired via `satisfies?`-probed registries.

   D1 SAFE SCAFFOLD slice — only the seven ports unblocked by the canonical
   design decision (hive memory 20260627145530-2c4394a8).

   DEFERRED (NOT defined here, gated on open questions with Pedro):
   * WorkflowEvent ADT (defadt taxonomy)
   * WorkflowAST malli schema
   * INotify (home TBD: hive-notify vs hive-spi)
   * IWorkflowEngine / IDispatchStrategy / WorkflowStrategyEntry —
     stay in hive-mcp.protocols.workflow until M9 repo-split."

  ;; Intentionally NO :require. SPI is a pure-contract leaf.
  )

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ===========================================================================
;;; IPlanCompiler
;;; ---------------------------------------------------------------------------
;;; Front-end #2 of HWF2 (after defworkflow code and LLM AST-EDN): lowers a
;;; Plan-EDN map into the wf-IR node-map carrier. Compute-waves Kahn over the
;;; :depends-on DAG yields (seq (par ...) (par ...)).
;;; ===========================================================================

(defprotocol IPlanCompiler
  "Compile a Plan-EDN front-end into the wf-IR node-map tree.

   Implementations live in hive-workflows.frontend.plan (M7)."

  (compile-plan
    [this plan-edn opts]
    "Compile a Plan-EDN map into wf-IR.

     Arguments:
       plan-edn — Map with at least
                  {:id        keyword|string  ; plan id
                   :title     string
                   :steps     [PlanStep ...]   ; each {:id :depends-on :method ...}
                   :default-method keyword     ; e.g. :dag-wave
                   }
       opts     — Map. Recognised keys (all optional):
                  :strict?         boolean — fail-loud on unknown field/method.
                  :method-resolver fn (kw -> WorkflowStrategyEntry).

     Returns: wf-IR node map
       {:wf/op :wf.op/seq
        :wf/id ...
        :wf/children [{:wf/op :wf.op/par :wf/children [...]} ...]
        :wf/meta {:plan/id ... :plan/source :plan-edn}}

     Throws: ex-info {:error :plan/invalid :reasons [...]} when strict? and
             validation fails.")

  (validate-plan
    [this plan-edn]
    "Static structural check of a Plan-EDN before compilation.

     Returns: {:valid? boolean
               :errors   [{:path [...] :message string} ...]
               :warnings [{:path [...] :message string} ...]}
     Never throws; pure data."))

;;; ===========================================================================
;;; IPlanGraph
;;; ---------------------------------------------------------------------------
;;; Read-only DAG view of a Plan. Decouples Kahn ordering and wave projection
;;; from the carrier representation so dag-wave/multi-front can operate over
;;; either a Plan-EDN or an authored AST without leaking either internally.
;;; ===========================================================================

(defprotocol IPlanGraph
  "Read-only view of a plan as a directed acyclic dependency graph."

  (nodes
    [this]
    "Return a vector of node ids (keywords or strings) in declaration order.")

  (edges
    [this]
    "Return a set of [from-id to-id] tuples where from-id depends on to-id
     (i.e. to-id must complete before from-id can start).")

  (node-data
    [this node-id]
    "Return the full data map for one node (e.g. a PlanStep), or nil if
     node-id is unknown. Pure lookup, no side-effects.")

  (waves
    [this]
    "Return a vector of vectors (wave-index -> [node-id ...]) produced by a
     Kahn topological grouping. Wave 0 has no dependencies; wave N depends
     only on waves < N. Throws ex-info {:error :graph/cycle :cycle [...]}
     if a cycle is detected.")

  (roots
    [this]
    "Return the set of node-ids with zero incoming dependency edges.")

  (leaves
    [this]
    "Return the set of node-ids with zero outgoing dependency edges."))

;;; ===========================================================================
;;; ITaskBoard
;;; ---------------------------------------------------------------------------
;;; Headless task/kanban surface that methods (forge-belt, dag-wave) reach
;;; into. Lives behind a protocol so that the in-repo kanban tool, an
;;; external GitHub Projects backend, or an in-memory test double can all
;;; back the same workflow method.
;;; ===========================================================================

(defprotocol ITaskBoard
  "Pluggable task-board / kanban backend used by workflow methods."

  (list-tasks
    [this query]
    "List tasks matching query.
     Arguments:
       query — Map. Recognised keys:
               :status   #{:todo :inprogress :inreview :done} or nil for any.
               :project-id string  — scope filter.
               :tags     [string ...] — all-of filter.
               :limit    int.
     Returns: vector of task maps
              {:task/id string :task/title string :task/status keyword
               :task/description string :task/tags [string ...]
               :task/created inst :task/updated inst}.
     Pure read; never throws on empty.")

  (get-task
    [this task-id]
    "Fetch one task by id. Returns task map or nil if not found.")

  (create-task!
    [this task]
    "Create a new task.
     Arguments:
       task — {:task/title string (required)
               :task/description string
               :task/status keyword (default :todo)
               :task/tags [string ...]
               :task/project-id string}.
     Returns: created task map (including :task/id).
     Throws: ex-info {:error :task/invalid :reasons [...]} on bad input.")

  (update-task!
    [this task-id patch]
    "Apply a partial update to a task.
     Arguments:
       task-id string
       patch   — Map of :task/* keys to overwrite.
     Returns: updated task map, or nil if task-id unknown.
     Throws: ex-info {:error :task/conflict ...} on optimistic-concurrency
             conflict if the backend tracks revisions."))

;;; ===========================================================================
;;; IHeadlessDispatcher
;;; ---------------------------------------------------------------------------
;;; Mirrors hive-mcp.addons.headless/IHeadlessBackend in shape but lives in
;;; the SPI so non-addons callers (workflow methods, MCP run verb) can
;;; depend on the contract without a reverse dep on hive-mcp.
;;; ===========================================================================

(defprotocol IHeadlessDispatcher
  "Spawn and dispatch on a headless agent backend (ling-style)."

  (dispatcher-key
    [this]
    "Return the keyword identifying this backend (e.g. :sdk, :vterm, :tmux).
     Used as the dispatch key in the headless-registry and must be stable.")

  (dispatcher-spawn!
    [this ctx opts]
    "Spawn a headless session.
     Arguments:
       ctx  — {:id string :cwd string :presets [...] :project-id string :model string}
       opts — {:task string :buffer-capacity int :env-extra map :agents [...]}
     Returns: string slave-id on success.
     Throws: ex-info {:error :dispatch/spawn-failed :id :reason}.")

  (dispatcher-dispatch!
    [this ctx task-opts]
    "Dispatch a task to a running headless session.
     Arguments:
       ctx       — {:id string}
       task-opts — {:task string :timeout-ms int}
     Returns: result channel (core.async), true, or backend-specific value.
     Throws: ex-info {:error :dispatch/failed :ling-id :reason}.")

  (dispatcher-status
    [this ctx ds-status]
    "Return liveness/status map {:slave/id :slave/status} or nil.
     Pure read; backend-specific extra keys allowed.")

  (dispatcher-kill!
    [this ctx]
    "Terminate the headless session for ctx.
     Returns: true on best-effort termination, false if already-dead.
     Never throws."))

;;; ===========================================================================
;;; IWorkflowStore
;;; ---------------------------------------------------------------------------
;;; Persistence facade for authored workflow ASTs.
;;; ===========================================================================

(defprotocol IWorkflowStore
  "Persistence facade for authored workflow ASTs and live run state."

  (put-workflow!
    [this workflow-id ast]
    "Store an authored workflow AST under workflow-id.
     Arguments:
       workflow-id keyword|string — namespaced id, e.g. :forge/strike.
       ast         — wf-IR node map produced by defworkflow / author verb.
     Returns: {:stored? true :workflow-id ... :revision int}.
     Throws: ex-info {:error :store/invalid-ast :reasons [...]}.")

  (get-workflow
    [this workflow-id]
    "Fetch one authored AST by id. Returns the AST node map, or nil if not
     found. Pure read.")

  (list-workflows
    [this query]
    "List authored workflows matching query.
     Arguments:
       query — {:tags [string ...] :project-id string :limit int}.
     Returns: vector of summary maps
              [{:workflow-id ... :title string :tags [...]
                :revision int :updated inst} ...].")

  (delete-workflow!
    [this workflow-id]
    "Remove a workflow from the store.
     Returns: {:deleted? boolean :workflow-id ...}.
     Idempotent: deleting an unknown id returns :deleted? false, never throws."))

;;; ===========================================================================
;;; IEffectHandler
;;; ---------------------------------------------------------------------------
;;; Self-describing verb seam. hive.events.fx remains the live runtime
;;; registry; IEffectHandler is the AUTHORING facet so that hive-workflows.vocab
;;; can register-verb! and describe verbs uniformly regardless of which OCP
;;; home (:fx / :method / :plan-field / :saa-port / :forge-ext) they route to.
;;; See convention `hwf2-fx-vocabulary` (memory 20260627145505-4eae0f7d).
;;; ===========================================================================

(defprotocol IEffectHandler
  "Self-describing effect/method verb. Authoring-side counterpart of
   hive.events.fx — does NOT replace it.

   Implementations are typically VerbSpec records constructed by
   hive-workflows.vocab and routed by :tags into the existing OCP homes."

  (verb-id
    [this]
    "Return the namespaced keyword identifying this verb (e.g. :fx/log,
     :method/forge, :plan-field/title). Must be namespaced — refuse
     registration over un-namespaced core keys.")

  (verb-tags
    [this]
    "Return a set of routing tags. Recognised: #{:fx :method :plan-field
     :saa-port :forge-ext}. Used by hive-workflows.vocab/register-verb! to
     route into hive.events.fx, strategy-registry, plan.field-registry, etc.")

  (handle-effect
    [this ctx args]
    "Invoke the verb's effect.
     Arguments:
       ctx  — runtime context (resources, sinks, current :wf/id, etc.)
       args — verb-specific argument map (validated by argv-schema).
     Returns: verb-specific result map. For :fx verbs this typically returns
              an updated ctx (mirrors hive.events.fx semantics). For :method
              verbs this returns the dispatch outcome.
     Throws: ex-info {:error :verb/failed :verb-id ... :reason ...}.")

  (argv-schema
    [this]
    "Return the malli schema for the args map accepted by handle-effect.
     Used by author-time FAIL-LOUD validation. Return nil for unschematized
     verbs (discouraged)."))

;;; ===========================================================================
;;; IIntrospectable
;;; ---------------------------------------------------------------------------
;;; Sibling protocol probed via `satisfies?` so adding describability to an
;;; existing strategy/verb does NOT change its primary protocol (ISP). The
;;; MCP `workflow describe` verb and the DescribeAlgebra static fold both
;;; consult this when present.
;;; ===========================================================================

(defprotocol IIntrospectable
  "Optional self-description for strategies, verbs, and engines.

   Probe with `(satisfies? IIntrospectable x)` — never assume presence.
   Implementations MUST be pure and side-effect-free."

  (describe
    [this]
    "Return a human/LLM-readable description map.
     Recommended shape:
       {:id           keyword
        :title        string
        :doc          string
        :inputs       [{:name kw :schema malli :doc string} ...]
        :outputs      {:schema malli :doc string}
        :examples     [{:input ... :output ...} ...]
        :tags         #{kw ...}
        :source-loc   {:ns symbol :file string :line int}}.
     Pure data; never throws."))
