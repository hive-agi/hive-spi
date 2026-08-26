(ns hive-spi.guard.rule
  "GuardRule — one enforceable rule, as data.

   A rule says: at these phases, on these harnesses, when the event matches
   this pattern, return this verdict for this reason, derived from these hive
   memory ids. It is EDN, so the same rule set drives every projection —
   an in-process gate, a generated vendor hook script, an approve/reject RPC —
   without being restated per vendor.

   The match spec is deliberately small and CLOSED as data: tool name, a regex
   over the tool name, a regex over the serialized tool input. Matching that
   cannot be said in those terms names a predicate by keyword under
   `:match/pred`, which the ENGINE resolves through its own registry. That
   keeps this leaf pure data while the set of predicates stays open — a new
   predicate is a registration, never an edit here.

   Pure leaf: schema, FAIL-LOUD gates, a smart constructor. Evaluating a match
   is the engine's job, not this namespace's."
  (:require [hive-spi.guard.decision :as d]
            [hive-spi.guard.event :as e]
            [malli.core :as m]))

;; MIT License - Copyright (c) 2026 Pedro Gomes Branquinho (BuddhiLW)

(def Match
  "A rule's match spec. Every key is optional and all present keys must hold
   (conjunction); an EMPTY match spec matches every event at the rule's phases,
   which is how a phase-wide rule (a session-start reminder) is written.

   Regex fields carry the PATTERN SOURCE as a string, not a compiled #\"…\",
   so a rule survives EDN round-tripping through memory and over the wire.

   Shape:
     {:match/tool          string | [string] (optional) — exact tool name(s)
      :match/tool-pattern  string (optional) — regex source over the tool name
      :match/input-pattern string (optional) — regex source over the serialized
                                               tool input
      :match/pred          keyword (optional) — engine-registered predicate}"
  (m/schema
   [:map {:closed false}
    [:match/tool          {:optional true} [:maybe [:or :string [:vector :string]]]]
    [:match/tool-pattern  {:optional true} [:maybe :string]]
    [:match/input-pattern {:optional true} [:maybe :string]]
    [:match/pred          {:optional true} [:maybe :keyword]]]))

(def GuardRule
  "Malli schema for one guard rule.

   `:rule/reason` is required and non-blank even for an `:allow` rule: a rule
   that fires must be able to say why it did, or the decision it produces
   cannot carry a reason either (see hive-spi.guard.decision).

   `:rule/harnesses` absent or nil means ALL harnesses — a rule is
   vendor-independent by default, and narrowing it to one vendor is the
   explicit act.

   Shape:
     {:rule/id         keyword  (required) — stable, e.g. :guard/no-ai-attribution
      :rule/phases     #{Phase} (required, non-empty)
      :rule/match      Match    (required; {} matches every event at :rule/phases)
      :rule/verdict    Verdict  (required)
      :rule/reason     string   (required, min length 1)
      :rule/citations  [MemoryId] (optional) — the axioms it was derived from
      :rule/harnesses  #{keyword} (optional) — nil => every harness
      :rule/enabled?   boolean  (optional, default true)}"
  (m/schema
   [:map {:closed false}
    [:rule/id        :keyword]
    [:rule/phases    [:set {:min 1} e/Phase]]
    [:rule/match     Match]
    [:rule/verdict   d/Verdict]
    [:rule/reason    [:string {:min 1}]]
    [:rule/citations {:optional true} d/Citations]
    [:rule/harnesses {:optional true} [:maybe [:set :keyword]]]
    [:rule/enabled?  {:optional true} :boolean]]))

(def RuleSet
  "An ordered collection of rules. Order is presentational only — the guard
   folds verdicts with `decision/strongest`, which is order-independent."
  (m/schema [:vector GuardRule]))

(def default-rule
  "Defaults applied by `guard-rule` for unset optional keys."
  {:rule/enabled?  true
   :rule/citations []
   :rule/harnesses nil})

(defn valid?
  "True if `x` conforms to `GuardRule`. Pure; never throws."
  [x]
  (try (m/validate GuardRule x) (catch #?(:clj Exception :cljs :default) _ false)))

(defn explain
  "Return a malli explanation map for `x`, or nil if it conforms.
   Pure; never throws."
  [x]
  (try (m/explain GuardRule x) (catch #?(:clj Exception :cljs :default) _ nil)))

(defn enabled?
  "True unless `rule` explicitly sets `:rule/enabled?` false."
  [rule]
  (not (false? (:rule/enabled? rule))))

(defn applies-to-harness?
  "True if `rule` applies to `harness`. A rule with no `:rule/harnesses`
   applies to every harness."
  [rule harness]
  (let [hs (:rule/harnesses rule)]
    (or (nil? hs) (empty? hs) (contains? hs harness))))

(defn applies-to-phase?
  "True if `rule` declares `phase`."
  [rule phase]
  (contains? (:rule/phases rule) phase))

(defn candidate?
  "True if `rule` is enabled and declares this event's phase and harness.
   The cheap structural pre-filter before the engine evaluates `:rule/match`."
  [rule event]
  (and (enabled? rule)
       (applies-to-phase? rule (:guard/phase event))
       (applies-to-harness? rule (:guard/harness event))))

(defn guard-rule
  "Build a validated GuardRule from a partial map, filling `default-rule`.

   FAIL-LOUD: throws ex-info {:error :guard/invalid-rule :explanation <malli>}
   if the result does not conform — a malformed rule must fail where it is
   authored, not silently never fire."
  [m]
  (let [r (merge default-rule m)]
    (if (valid? r)
      r
      (throw (ex-info (str "Invalid GuardRule: " (pr-str m))
                      {:error       :guard/invalid-rule
                       :rule        m
                       :explanation (explain r)})))))
