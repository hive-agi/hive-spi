(ns hive-spi.swarm.ports.memory-scope
  "Ports for project-scope derivation and KG disc-staleness context, consumed
   by the swarm subsystem hosted outside hive-mcp.

   Two small protocols (ISP): IProjectScope answers 'which project does this
   path belong to'; IDiscStaleness answers 'what does the Knowledge Graph know
   about the freshness of these files'. Both are answered by the HOST process
   (hive-mcp), because they read .hive-project.edn files and the KG disc
   store — state a standalone process must not own.

   Empty-policy: with nothing installed the port resolves the Noop — scope
   derivation answers \"global\" (the value every caller already branches on),
   staleness answers empty context. Never throws.

   Reload-safety: `defprotocol` is not idempotent, so each declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations. Consumers must NOT re-defprotocol these names."
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -iprojectscope-defined? (atom false))

(when (compare-and-set! -iprojectscope-defined? false true)
  (defprotocol IProjectScope
    "Project-scope resolution from filesystem paths. Implemented by the host,
     which owns .hive-project.edn discovery, alias resolution and caches."

    (project-id-for-path [this path]
      "The project-id for `path` (a directory), per the host's hierarchical
       resolution: this directory's .hive-project.edn project-id (alias
       resolved) first, then the last non-blank path segment, else \"global\".
       nil/blank `path` yields \"global\". Replaces
       hive-mcp.tools.memory.scope/get-current-project-id (1-arity surface).")

    (infer-scope-from-path [this path]
      "The project-id inferred for `path` by finding the NEAREST
       .hive-project.edn walking up, else \"global\". The side effect of the
       original (registering the discovered config) stays host-side. Replaces
       hive-mcp.knowledge-graph.scope/infer-scope-from-path.")))

(defonce ^:private -idiscstaleness-defined? (atom false))

(when (compare-and-set! -idiscstaleness-defined? false true)
  (defprotocol IDiscStaleness
    "KG disc staleness context for the dispatch prompt-enhancement pipeline.
     Answers come from the host's KG disc store and the filesystem (content
     hashing); a standalone process has no discs, hence the Noop below."

    (staleness-warnings [this paths]
      "Warnings (maps with at least :message) for stale file `paths`, or nil
       when nothing is stale. Replaces
       hive-mcp.knowledge-graph.disc/staleness-warnings.")

    (format-staleness-warnings [this warnings]
      "Render warnings (as returned by staleness-warnings) as a text block for
       injection into task prompts, or nil when empty. Pure formatting, but
       carried on the port so the host owns the format. Replaces
       hive-mcp.knowledge-graph.disc/format-staleness-warnings.")

    (kg-first-context [this paths]
      "Classify file `paths` by KG freshness BEFORE reading them. Returns
       {:kg-known [...] :needs-read [...] :stale [...] :summary {...}}.
       Replaces hive-mcp.knowledge-graph.disc/kg-first-context (1-arity; the
       host supplies its default staleness-threshold).")))

;;; ============================================================================
;;; Noop — the degraded host: 'global' everywhere, no staleness knowledge
;;; ============================================================================

(def noop
  "Degraded implementation for a standalone process: scope derivation answers
   \"global\" (the value every caller already handles), disc staleness answers
   empty context. Never throws."
  (reify IProjectScope
    (project-id-for-path [_this _path] "global")
    (infer-scope-from-path [_this _path] "global")
    IDiscStaleness
    (staleness-warnings [_this _paths] nil)
    (format-staleness-warnings [_this _warnings] nil)
    (kg-first-context [_this _paths]
      {:kg-known [] :needs-read [] :stale []
       :summary {:total 0 :known 0 :needs-read 0 :stale 0}})))

(defonce ^:private port-slot
  (slot/single-slot {:validate #(satisfies? IProjectScope %)
                     :on-empty (constantly noop)}))

(defn set-memory-scope!
  "Install IMPL as the active project-scope/disc-staleness port. Returns IMPL."
  [impl]
  (slot/install! port-slot impl))

(defn get-memory-scope
  "The active port: the installed one, else the Noop."
  []
  (slot/current port-slot))

(defn clear-memory-scope!
  "Remove the installed port, so consumers fall back to the Noop.
   Returns nil."
  []
  (slot/clear! port-slot))

(defn memory-scope-set?
  "True iff a port is explicitly installed. The Noop does not count."
  []
  (slot/present? port-slot))
