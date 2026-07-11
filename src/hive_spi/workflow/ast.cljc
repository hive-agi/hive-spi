(ns hive-spi.workflow.ast
  "Workflow IR — pure static EDN node-map carrier + malli schema (HWF2 D1).

   Realises the canonical-design decision (hive memory 20260627145530-2c4394a8):

     `Carrier = plain EDN node map {:wf/op :wf/args :wf/children :wf/id
     :wf/meta} (NOT a record — LLM-emittable).`

   Pedro decision 2026-06-27: the AST is PURE STATIC EDN. Inputs resolve at
   RUN time via ctx :env. There is NO author-time symbolic-binding pass —
   :wf/args may carry literal data or :wf.ref/* lookup keys but never raw
   un-bound symbols.

   This namespace provides:
     * `WorkflowAST` — recursive malli schema for the carrier.
     * `valid?` / `explain` — schema gates (FAIL-LOUD authoring).
     * Smart constructors `seq*` `par*` `gate*` `loop*` `let*` `call*`
       `pure*` returning node maps with auto-assigned structural-path
       :wf/id (vector of [child-index ...] from root).
     * `->edn` / `edn->ast` round-trip helpers (an AST node IS EDN, so
       these are reflexive identity over well-formed nodes after id
       reassignment).

   No implementations of EvalAlgebra / DescribeAlgebra live here — those
   are M2 work in hive-workflows."
  (:refer-clojure :exclude [seq])
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ===========================================================================
;;; Op set
;;; ---------------------------------------------------------------------------
;;; Closed set of seven primitive ops. :wf.op/call is the SINGLE
;;; world-touching leaf — every effect verb is invoked through call*.
;;; The 4 "method" combinators (forge-belt, dag-wave, saa, multi-front) are
;;; DERIVED sugar over these primitives — they do NOT add ops.
;;; ===========================================================================

(def ops
  "The closed set of valid :wf/op keywords."
  #{:wf.op/seq
    :wf.op/par
    :wf.op/gate
    :wf.op/loop
    :wf.op/let
    :wf.op/call
    :wf.op/pure})

;;; ===========================================================================
;;; Malli schema — WorkflowAST
;;; ---------------------------------------------------------------------------
;;; Recursive via [:schema {:registry {::node ...}}]. Authoring tools use
;;; `valid?` and `explain` to FAIL-LOUD on malformed ASTs (per the
;;; effect-vocabulary convention — silent Noop is the headline footgun).
;;; ===========================================================================

(def WorkflowAST
  "Recursive malli schema for a wf-IR node.

   Shape:
     {:wf/op       keyword in `ops`
      :wf/args     map (verb args; may carry :wf.ref/* lookup keys but
                       MUST be EDN-printable — NO bare symbols)
      :wf/children vector of WorkflowAST (recursive; may be empty for leaves)
      :wf/id       vector (structural path from root; e.g. [0 1 2])
      :wf/meta     map (free-form authoring metadata; defaults to {})}"
  (m/schema
   [:schema
    {:registry
     {::node [:map
              [:wf/op       [:enum :wf.op/seq :wf.op/par :wf.op/gate
                             :wf.op/loop :wf.op/let :wf.op/call :wf.op/pure]]
              [:wf/args     [:map-of :any :any]]
              [:wf/children [:vector [:ref ::node]]]
              [:wf/id       [:vector :any]]
              [:wf/meta     [:map-of :any :any]]]}}
    [:ref ::node]]))

(defn valid?
  "True if `x` conforms to `WorkflowAST`. Pure; never throws."
  [x]
  (m/validate WorkflowAST x))

(defn explain
  "Return a malli explanation map for `x`, or nil if it conforms.
   Pure; never throws."
  [x]
  (m/explain WorkflowAST x))

;;; ===========================================================================
;;; Structural-path assignment
;;; ---------------------------------------------------------------------------
;;; :wf/id is the vector path of child-indices from the root (root = []).
;;; Smart constructors auto-assign by recursively walking children. This is
;;; the addressing scheme execute-step/cancel-workflow adapters use.
;;; ===========================================================================

(defn- assign-ids
  "Walk node tree and (re)assign :wf/id to the structural path from root.
   `path` accumulates the index trail; root invocation passes []."
  [node path]
  (let [children (vec (:wf/children node []))
        children' (vec (map-indexed
                        (fn [i child] (assign-ids child (conj path i)))
                        children))]
    (-> node
        (assoc :wf/id path)
        (assoc :wf/children children'))))

(defn- ->node
  "Build a raw node map with the common 5-key shape, then assign :wf/id
   structural paths over the whole subtree from root []."
  [op args children meta-]
  (assign-ids
   {:wf/op       op
    :wf/args     (or args {})
    :wf/children (vec (or children []))
    :wf/id       []
    :wf/meta     (or meta- {})}
   []))

;;; ===========================================================================
;;; Smart constructors (public)
;;; ---------------------------------------------------------------------------
;;; Each returns a node map. Children may be passed as a vector (preferred),
;;; or omitted for leaves. `args` and `meta-` are optional maps.
;;; ===========================================================================

(defn seq*
  "Sequential composition. Children run in order; failure short-circuits."
  ([children] (seq* {} children {}))
  ([args children] (seq* args children {}))
  ([args children meta-] (->node :wf.op/seq args children meta-)))

(defn par*
  "Parallel composition. Children run independently; failure semantics
   depend on the algebra (default: any failure fails the par)."
  ([children] (par* {} children {}))
  ([args children] (par* args children {}))
  ([args children meta-] (->node :wf.op/par args children meta-)))

(defn gate*
  "Conditional gate. `args` must include `:when` (a predicate kw — named-only
   in M2; SCI-allowed predicate spine is deferred to M10). Children typically
   `[then-branch else-branch]`."
  ([args children] (gate* args children {}))
  ([args children meta-] (->node :wf.op/gate args children meta-)))

(defn loop*
  "Bounded loop. `args` MAY include `:until` (predicate kw) or `:max-iters`."
  ([args children] (->node :wf.op/loop args children {}))
  ([args children meta-] (->node :wf.op/loop args children meta-)))

(defn let*
  "Lexical-style bindings. `args` must include `:bindings`, a vector of
   [kw expr ...] pairs. Carries STATIC EDN — bindings resolve at run time
   against ctx :env (NO author-time symbolic pass)."
  ([args children] (->node :wf.op/let args children {}))
  ([args children meta-] (->node :wf.op/let args children meta-)))

(defn call*
  "World-touching leaf. `args` must include `:verb` (the namespaced verb id
   to dispatch). Routed by hive-workflows.vocab into the OCP home
   determined by the verb's :tags."
  ([args] (call* args [] {}))
  ([args children] (call* args children {}))
  ([args children meta-] (->node :wf.op/call args children meta-)))

(defn pure*
  "Pure value leaf. Emits `(:value args)` into the fold. No effect."
  ([args] (pure* args [] {}))
  ([args children] (pure* args children {}))
  ([args children meta-] (->node :wf.op/pure args children meta-)))

;;; ===========================================================================
;;; EDN round-trip
;;; ---------------------------------------------------------------------------
;;; A well-formed AST IS plain EDN (LLM-emittable). The round-trip helpers
;;; are reflexive identity on conformant inputs — they exist so callers
;;; have a stable seam if a future schema migration ever requires a
;;; normalising pass (e.g. id reassignment after surgical editing).
;;; ===========================================================================

(defn ->edn
  "Project an AST node to its EDN form. Currently identity over conformant
   inputs (the carrier IS EDN); throws if `node` does not validate."
  [node]
  (when-not (valid? node)
    (throw (ex-info "Not a valid WorkflowAST node"
                    {:error :ast/invalid
                     :explanation (explain node)})))
  node)

(defn edn->ast
  "Read an EDN form back into an AST. Reassigns :wf/id structural paths so
   that surgically edited subtrees re-normalise. Throws if the resulting
   node does not validate."
  [edn]
  (let [normalised (assign-ids edn [])]
    (when-not (valid? normalised)
      (throw (ex-info "EDN does not parse to a valid WorkflowAST"
                      {:error :ast/invalid
                       :explanation (explain normalised)})))
    normalised))
