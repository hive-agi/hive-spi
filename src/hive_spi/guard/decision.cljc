(ns hive-spi.guard.decision
  "GuardDecision — the verdict a guard returns for a GuardEvent, and the join
   that folds several verdicts into one.

   Three verdicts, ordered by severity: `:allow` < `:warn` < `:deny`. The order
   is not decoration — several rules may fire on one event, and `strongest`
   folds them by taking the maximum. That makes the fold commutative and
   associative, so rule evaluation ORDER cannot change the outcome.

   A `:deny` and a `:warn` MUST carry a non-blank `:guard/reason`. A verdict a
   caller cannot read back is one it cannot argue with, and an unarguable deny
   is the failure mode this subsystem exists to remove. `:guard/citations`
   carries the hive memory ids the rule was derived from, so the reason points
   at the axiom rather than restating it.

   Pure leaf: schema, vocabulary, constructors, the join. Deciding WHICH
   verdict an event earns is the engine's job, not this namespace's."
  (:require [malli.core :as m]))

;; MIT License - Copyright (c) 2026 Pedro Gomes Branquinho (BuddhiLW)

(def verdict-order
  "Verdicts from least to most severe. The vector IS the order `strongest`
   folds by; `verdict-rank` is derived from it."
  [:allow :warn :deny])

(def verdict-keys
  "The closed set of guard verdicts."
  (set verdict-order))

(def verdict-rank
  "Verdict -> severity rank. Higher wins a `strongest` fold."
  (zipmap verdict-order (range)))

(def Verdict
  "A guard verdict keyword, drawn from `verdict-keys`."
  (m/schema (into [:enum] verdict-order)))

(def MemoryId
  "A hive memory entry id — `yyyyMMddHHmmss-<8 hex>`."
  (m/schema [:re #"^\d{14}-[0-9a-f]{8}$"]))

(def Citations
  "The memory ids a verdict was derived from. May be empty: a verdict derived
   from a RoleCard grant rather than an axiom cites no memory entry."
  (m/schema [:vector MemoryId]))

(def ^:private common-entries
  [[:guard/rule-id   {:optional true} [:maybe :keyword]]
   [:guard/citations {:optional true} Citations]])

(def GuardDecision
  "Malli schema for a guard verdict.

   A `:multi` on `:guard/verdict`, because the obligation differs by verdict:
   `:deny` and `:warn` REQUIRE a non-blank `:guard/reason`, `:allow` does not.
   Open maps — a projection may carry vendor-specific fields alongside.

   Shape:
     {:guard/verdict   Verdict  (required)
      :guard/reason    string   (required for :deny / :warn, min length 1)
      :guard/rule-id   keyword  (optional) — the rule that produced it
      :guard/citations [MemoryId] (optional) — the axioms it was derived from}"
  (m/schema
   [:multi {:dispatch :guard/verdict}
    [:allow (into [:map {:closed false}
                   [:guard/verdict [:= :allow]]
                   [:guard/reason {:optional true} [:maybe :string]]]
                  common-entries)]
    [:warn  (into [:map {:closed false}
                   [:guard/verdict [:= :warn]]
                   [:guard/reason [:string {:min 1}]]]
                  common-entries)]
    [:deny  (into [:map {:closed false}
                   [:guard/verdict [:= :deny]]
                   [:guard/reason [:string {:min 1}]]]
                  common-entries)]]))

(defn valid?
  "True if `x` conforms to `GuardDecision`. Pure; never throws."
  [x]
  (try (m/validate GuardDecision x) (catch #?(:clj Exception :cljs :default) _ false)))

(defn explain
  "Return a malli explanation map for `x`, or nil if it conforms.
   Pure; never throws."
  [x]
  (try (m/explain GuardDecision x) (catch #?(:clj Exception :cljs :default) _ nil)))

(defn allow
  "An `:allow` decision. Optional `opts` may carry :guard/rule-id and
   :guard/citations."
  ([] {:guard/verdict :allow})
  ([opts] (merge {:guard/verdict :allow} opts)))

(defn warn
  "A `:warn` decision carrying `reason`. Optional `opts` may carry
   :guard/rule-id and :guard/citations.

   FAIL-LOUD on a blank reason — see the namespace docstring."
  ([reason] (warn reason nil))
  ([reason opts]
   (let [d (merge {:guard/verdict :warn :guard/reason reason} opts)]
     (if (valid? d)
       d
       (throw (ex-info "A :warn decision requires a non-blank :guard/reason"
                       {:error :guard/invalid-decision :decision d
                        :explanation (explain d)}))))))

(defn deny
  "A `:deny` decision carrying `reason`. Optional `opts` may carry
   :guard/rule-id and :guard/citations.

   FAIL-LOUD on a blank reason — see the namespace docstring."
  ([reason] (deny reason nil))
  ([reason opts]
   (let [d (merge {:guard/verdict :deny :guard/reason reason} opts)]
     (if (valid? d)
       d
       (throw (ex-info "A :deny decision requires a non-blank :guard/reason"
                       {:error :guard/invalid-decision :decision d
                        :explanation (explain d)}))))))

(defn denied?
  "True if `decision` is a `:deny`."
  [decision]
  (= :deny (:guard/verdict decision)))

(defn rank
  "Severity rank of `decision`'s verdict; an unknown verdict ranks as `:allow`."
  [decision]
  (get verdict-rank (:guard/verdict decision) 0))

(defn strongest
  "Fold `decisions` to the most severe one, preserving its reason and citations.

   Commutative and associative on rank, so rule evaluation ORDER cannot change
   the outcome; ties keep the FIRST decision at that rank, which makes the fold
   deterministic for a stable rule sequence. An empty (or all-nil) input folds
   to a bare `:allow`."
  [decisions]
  (or (reduce (fn [best d]
                (cond
                  (nil? d)                 best
                  (nil? best)              d
                  (> (rank d) (rank best)) d
                  :else                    best))
              nil
              decisions)
      (allow)))
