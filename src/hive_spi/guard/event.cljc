(ns hive-spi.guard.event
  "GuardEvent — the normalized harness event a guard evaluates.

   One vocabulary for what every agent harness reports at the same moments:
   a tool is about to run, a tool finished, a session or subagent started, a
   prompt was submitted, the turn is ending. Each vendor names and shapes these
   differently (Claude Code `PreToolUse` + `hookSpecificOutput`, eca
   `chat/toolCall*`, a headless loop's own dispatch point); an
   `IGuardProjection` decodes the vendor's raw payload INTO this schema, and
   nothing above the projection layer learns which vendor produced it.

   `:guard/phase` is a CLOSED set — the moments a harness can report are fixed,
   so the schema is a `:multi` dispatch and a new phase is a schema change, not
   a free-form string. `:guard/harness` is an OPEN keyword — new vendors arrive
   without a schema change.

   Pure leaf: schema, vocabulary, FAIL-LOUD gates and a smart constructor.
   Matching an event against rules is the engine's job, not this namespace's."
  (:require [malli.core :as m]))

;; MIT License - Copyright (c) 2026 Pedro Gomes Branquinho (BuddhiLW)

(def phase-keys
  "The closed set of moments a harness can report.

     :pre-tool       — a tool call is about to run; the only DENIABLE phase.
     :post-tool      — a tool call returned.
     :session-start  — a session began (startup / resume / clear / compact).
     :subagent-start — a subagent/ling session began.
     :prompt-submit  — a user prompt was submitted.
     :stop           — the turn is ending."
  #{:pre-tool :post-tool :session-start :subagent-start :prompt-submit :stop})

(def Phase
  "A guard phase keyword, drawn from `phase-keys`."
  (m/schema (into [:enum] (sort phase-keys))))

(def tool-phase-keys
  "Phases that carry a tool call, and so can be matched on `:tool/name`."
  #{:pre-tool :post-tool})

(def ^:private common-entries
  "Entries every phase carries. `:guard/harness` is required so a decision can
   always name which harness it applies to; everything else is optional because
   a harness may not report it."
  [[:guard/harness :keyword]
   [:session/id {:optional true} [:maybe :string]]
   [:agent/id   {:optional true} [:maybe :string]]
   [:role/id    {:optional true} [:maybe :keyword]]
   [:cwd        {:optional true} [:maybe :string]]])

(defn- phase-map
  "Build the open `:map` schema for one phase: the phase literal, the common
   entries, then that phase's own entries."
  [phase & entries]
  (into [:map {:closed false} [:guard/phase [:= phase]]]
        (concat common-entries entries)))

(def GuardEvent
  "Malli schema for a normalized harness event.

   A `:multi` on `:guard/phase`, so each phase states exactly what it must
   carry: `:pre-tool` and `:post-tool` require `:tool/name`, `:prompt-submit`
   requires `:prompt`. Every branch is an OPEN map — a projection may pass a
   vendor's extra fields through untouched.

   Shape (common to all phases):
     {:guard/phase   Phase   (required)
      :guard/harness keyword (required) — :claude-code, :eca, :hive-agent, :mcp, …
      :session/id    string  (optional)
      :agent/id      string  (optional)
      :role/id       keyword (optional) — the RoleCard in force, if any
      :cwd           string  (optional)}"
  (m/schema
   [:multi {:dispatch :guard/phase}
    [:pre-tool       (phase-map :pre-tool
                                [:tool/name :string]
                                [:tool/input {:optional true} [:maybe :map]])]
    [:post-tool      (phase-map :post-tool
                                [:tool/name :string]
                                [:tool/input  {:optional true} [:maybe :map]]
                                [:tool/result {:optional true} :any])]
    [:session-start  (phase-map :session-start
                                [:session/trigger {:optional true} [:maybe :keyword]])]
    [:subagent-start (phase-map :subagent-start
                                [:agent/type {:optional true} [:maybe :string]])]
    [:prompt-submit  (phase-map :prompt-submit
                                [:prompt :string])]
    [:stop           (phase-map :stop)]]))

(defn phase?
  "True if `x` is a member of `phase-keys`."
  [x]
  (contains? phase-keys x))

(defn tool-phase?
  "True if `x` is a phase that carries a tool call."
  [x]
  (contains? tool-phase-keys x))

(defn valid?
  "True if `x` conforms to `GuardEvent`. Pure; never throws."
  [x]
  (try (m/validate GuardEvent x) (catch #?(:clj Exception :cljs :default) _ false)))

(defn explain
  "Return a malli explanation map for `x`, or nil if it conforms.
   Pure; never throws."
  [x]
  (try (m/explain GuardEvent x) (catch #?(:clj Exception :cljs :default) _ nil)))

(defn guard-event
  "Build a validated GuardEvent from a partial map.

   FAIL-LOUD: throws ex-info {:error :guard/invalid-event :explanation <malli>}
   if the result does not conform — a projection that mis-decodes a vendor
   payload must not produce an event the engine then evaluates on partial data."
  [m]
  (if (valid? m)
    m
    (throw (ex-info (str "Invalid GuardEvent: " (pr-str m))
                    {:error       :guard/invalid-event
                     :event       m
                     :explanation (explain m)}))))
