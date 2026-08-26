;; MIT License - Copyright (c) 2026 Pedro Gomes Branquinho (BuddhiLW)

(ns hive-spi.guard.contract-test
  "Contract tests for hive-spi.guard.* — the vendor-neutral guard leaf.

   The schemas state the shapes; these tests state what a schema cannot:

   1. The phase set is CLOSED and each phase's obligations differ — a `:multi`
      dispatch, not one permissive map.
   2. A `:deny` or `:warn` MUST carry a non-blank reason, enforced by the
      constructor. An unarguable deny is the failure mode the guard exists to
      remove.
   3. `strongest` is a JOIN: commutative, associative, idempotent, with
      `:allow` as identity. That is what makes rule evaluation ORDER unable to
      change an outcome.
   4. A rule is vendor-independent BY DEFAULT and survives EDN round-tripping,
      because the same datum is authored in hive memory, evaluated in-process,
      and rendered into a generated vendor hook."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-spi.guard.decision :as d]
            [hive-spi.guard.event :as e]
            [hive-spi.guard.rule :as r]
            [malli.generator :as mg]))

;;; ===========================================================================
;;; Event — a closed phase set with per-phase obligations
;;; ===========================================================================

(deftest phase-set-is-closed
  (testing "exactly the six moments a harness can report"
    (is (= #{:pre-tool :post-tool :session-start :subagent-start
             :prompt-submit :stop}
           e/phase-keys)))
  (testing "phase? admits members and nothing else"
    (is (every? e/phase? e/phase-keys))
    (is (not (e/phase? :pre-Tool)))
    (is (not (e/phase? "pre-tool")))
    (is (not (e/phase? nil))))
  (testing "an unknown phase does not validate — a new phase is a schema change"
    (is (not (e/valid? {:guard/phase :on-compact :guard/harness :claude-code}))))
  (testing "tool phases are the ones carrying a call"
    (is (= #{:pre-tool :post-tool} e/tool-phase-keys))
    (is (e/tool-phase? :pre-tool))
    (is (not (e/tool-phase? :stop)))
    (is (every? e/phase? e/tool-phase-keys))))

(deftest phase-obligations-differ
  (testing ":pre-tool and :post-tool require :tool/name"
    (is (e/valid? {:guard/phase :pre-tool :guard/harness :claude-code
                   :tool/name "Bash"}))
    (is (not (e/valid? {:guard/phase :pre-tool :guard/harness :claude-code})))
    (is (e/valid? {:guard/phase :post-tool :guard/harness :eca
                   :tool/name "Bash" :tool/result {:exit 0}}))
    (is (not (e/valid? {:guard/phase :post-tool :guard/harness :eca}))))
  (testing ":prompt-submit requires :prompt"
    (is (e/valid? {:guard/phase :prompt-submit :guard/harness :claude-code
                   :prompt "ship it"}))
    (is (not (e/valid? {:guard/phase :prompt-submit :guard/harness :claude-code}))))
  (testing ":stop and the session phases carry no payload of their own"
    (is (e/valid? {:guard/phase :stop :guard/harness :hive-agent}))
    (is (e/valid? {:guard/phase :session-start :guard/harness :claude-code
                   :session/trigger :resume}))
    (is (e/valid? {:guard/phase :subagent-start :guard/harness :claude-code
                   :agent/type "Explore"}))))

(deftest harness-is-open-but-required
  (testing "any keyword is a harness — a new vendor needs no schema change"
    (is (every? #(e/valid? {:guard/phase :stop :guard/harness %})
                [:claude-code :eca :opencode :hive-agent :mcp :some-future-vendor])))
  (testing "but a decision must always be able to name its harness"
    (is (not (e/valid? {:guard/phase :stop})))
    (is (not (e/valid? {:guard/phase :stop :guard/harness "claude-code"})))))

(deftest branches-are-open-so-projections-may-pass-vendor-fields-through
  (is (e/valid? {:guard/phase :pre-tool :guard/harness :claude-code
                 :tool/name "Bash"
                 :vendor/transcript-path "/tmp/x.jsonl"
                 :vendor/permission-mode "auto"})))

(deftest guard-event-is-fail-loud
  (testing "a conformant map passes through unchanged"
    (let [ev {:guard/phase :pre-tool :guard/harness :eca :tool/name "Bash"}]
      (is (= ev (e/guard-event ev)))))
  (testing "a mis-decoded event throws rather than reaching the engine"
    (let [ex (try (e/guard-event {:guard/phase :pre-tool :guard/harness :eca})
                  (catch Exception ex ex))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :guard/invalid-event (:error (ex-data ex))))
      (is (some? (:explanation (ex-data ex)))))))

(deftest event-gates-are-total
  (doseq [x [nil {} [] "" 42 :kw {:guard/phase nil}]]
    (is (false? (e/valid? x)) (pr-str x))
    (is (some? (e/explain x)) (pr-str x))))

(defspec generated-events-conform 100
  (prop/for-all [ev (mg/generator e/GuardEvent)]
                (e/valid? ev)))

;;; ===========================================================================
;;; Decision — a deny must be arguable, and the fold is a join
;;; ===========================================================================

(deftest verdicts-are-closed-and-ordered
  (is (= [:allow :warn :deny] d/verdict-order))
  (is (= #{:allow :warn :deny} d/verdict-keys))
  (is (< (d/verdict-rank :allow) (d/verdict-rank :warn)))
  (is (< (d/verdict-rank :warn) (d/verdict-rank :deny)))
  (is (not (d/valid? {:guard/verdict :block :guard/reason "no"}))))

(deftest deny-and-warn-require-a-reason
  (testing "the schema requires it"
    (is (not (d/valid? {:guard/verdict :deny})))
    (is (not (d/valid? {:guard/verdict :deny :guard/reason ""})))
    (is (not (d/valid? {:guard/verdict :warn})))
    (is (not (d/valid? {:guard/verdict :warn :guard/reason ""})))
    (is (d/valid? {:guard/verdict :deny :guard/reason "x"})))
  (testing "an :allow needs none"
    (is (d/valid? (d/allow))))
  (testing "the constructors enforce it rather than describing it"
    (doseq [ctor [d/deny d/warn]]
      (let [ex (try (ctor "") (catch Exception ex ex))]
        (is (instance? clojure.lang.ExceptionInfo ex))
        (is (= :guard/invalid-decision (:error (ex-data ex))))))))

(deftest constructors-carry-provenance
  (let [dec (d/deny "no AI attribution in commits or PRs"
                    {:guard/rule-id :guard/no-ai-attribution
                     :guard/citations ["20260704183422-5333b2fb"]})]
    (is (d/valid? dec))
    (is (d/denied? dec))
    (is (= :guard/no-ai-attribution (:guard/rule-id dec))))
  (testing "citations must be hive memory ids, not free text"
    (is (not (d/valid? {:guard/verdict :deny :guard/reason "x"
                        :guard/citations ["see CLAUDE.md"]})))
    (is (d/valid? {:guard/verdict :deny :guard/reason "x" :guard/citations []}))))

(def ^:private gen-decision
  (gen/one-of
   [(gen/return (d/allow))
    (gen/fmap #(d/warn (str "warn-" %)) gen/small-integer)
    (gen/fmap #(d/deny (str "deny-" %)) gen/small-integer)]))

(defn- verdict [dec] (:guard/verdict dec))

(defspec strongest-is-commutative-on-severity 200
  (prop/for-all [a gen-decision b gen-decision]
                (= (verdict (d/strongest [a b]))
                   (verdict (d/strongest [b a])))))

(defspec strongest-is-associative-on-severity 200
  (prop/for-all [a gen-decision b gen-decision c gen-decision]
                (= (verdict (d/strongest [(d/strongest [a b]) c]))
                   (verdict (d/strongest [a (d/strongest [b c])])))))

(defspec strongest-is-idempotent 100
  (prop/for-all [a gen-decision]
                (= (verdict a) (verdict (d/strongest [a a])))))

(defspec allow-is-the-identity 100
  (prop/for-all [a gen-decision]
                (and (= (verdict a) (verdict (d/strongest [a (d/allow)])))
                     (= (verdict a) (verdict (d/strongest [(d/allow) a]))))))

(defspec strongest-is-total-and-conformant 200
  (prop/for-all [ds (gen/vector gen-decision 0 6)]
                (d/valid? (d/strongest ds))))

(defspec deny-absorbs 200
  (prop/for-all [ds (gen/vector gen-decision 1 6)]
                (= (boolean (some d/denied? ds))
                   (d/denied? (d/strongest ds)))))

(defspec nils-are-ignored-not-fatal 100
  (prop/for-all [ds (gen/vector gen-decision 0 4)]
                (= (verdict (d/strongest ds))
                   (verdict (d/strongest (interpose nil ds))))))

(defspec generated-decisions-conform 100
  (prop/for-all [dec (mg/generator d/GuardDecision)]
                (d/valid? dec)))

(deftest strongest-edge-cases
  (testing "empty and all-nil fold to a bare allow"
    (is (= :allow (verdict (d/strongest []))))
    (is (= :allow (verdict (d/strongest [nil nil]))))
    (is (d/valid? (d/strongest []))))
  (testing "the winner keeps its own reason and citations"
    (let [win (d/deny "the reason" {:guard/citations ["20260704183422-5333b2fb"]})]
      (is (= win (d/strongest [(d/allow) (d/warn "ignored") win])))))
  (testing "a tie keeps the FIRST decision at that rank — deterministic"
    (let [a (d/deny "first") b (d/deny "second")]
      (is (= a (d/strongest [a b])))))
  (testing "rank of an unknown verdict degrades to :allow rather than throwing"
    (is (zero? (d/rank {:guard/verdict :nonsense})))
    (is (zero? (d/rank {})))))

;;; ===========================================================================
;;; Rule — vendor-independent by default, and EDN all the way down
;;; ===========================================================================

(def ^:private no-ai-attribution
  (r/guard-rule
   {:rule/id        :guard/no-ai-attribution
    :rule/phases    #{:pre-tool}
    :rule/match     {:match/tool ["Bash" "mcp__hive__git"]
                     :match/input-pattern "Co-?Authored-?By"}
    :rule/verdict   :deny
    :rule/reason    "no AI attribution in commits or PRs"
    :rule/citations ["20260704183422-5333b2fb"]}))

(deftest smart-constructor-fills-defaults
  (is (r/valid? no-ai-attribution))
  (is (true? (:rule/enabled? no-ai-attribution)))
  (is (nil? (:rule/harnesses no-ai-attribution)))
  (is (= [] (:rule/citations (r/guard-rule {:rule/id :x :rule/phases #{:stop}
                                            :rule/match {} :rule/verdict :allow
                                            :rule/reason "r"}))))
  (testing "an explicit value beats the default"
    (let [rule (r/guard-rule (assoc no-ai-attribution :rule/enabled? false))]
      (is (false? (:rule/enabled? rule)))
      (is (not (r/enabled? rule))))))

(deftest smart-constructor-is-fail-loud
  (doseq [bad [{:rule/id :x}
               {:rule/id :x :rule/phases #{} :rule/match {}
                :rule/verdict :deny :rule/reason "r"}
               {:rule/id :x :rule/phases #{:pre-tool} :rule/match {}
                :rule/verdict :deny :rule/reason ""}
               {:rule/id :x :rule/phases #{:no-such-phase} :rule/match {}
                :rule/verdict :deny :rule/reason "r"}
               {:rule/id :x :rule/phases #{:pre-tool} :rule/match {}
                :rule/verdict :block :rule/reason "r"}]]
    (let [ex (try (r/guard-rule bad) (catch Exception ex ex))]
      (is (instance? clojure.lang.ExceptionInfo ex) (pr-str bad))
      (is (= :guard/invalid-rule (:error (ex-data ex))) (pr-str bad)))))

(deftest a-reason-is-required-even-for-an-allow
  (is (not (r/valid? {:rule/id :x :rule/phases #{:stop} :rule/match {}
                      :rule/verdict :allow :rule/reason ""}))))

(deftest rules-apply-to-every-harness-unless-narrowed
  (testing "absent, nil and empty :rule/harnesses all mean EVERY harness"
    (doseq [hs [nil #{}]]
      (let [rule (assoc no-ai-attribution :rule/harnesses hs)]
        (is (every? #(r/applies-to-harness? rule %)
                    [:claude-code :eca :opencode :hive-agent :mcp])
            (pr-str hs))))
    (is (r/applies-to-harness? (dissoc no-ai-attribution :rule/harnesses)
                               :some-future-vendor)))
  (testing "narrowing is the explicit act"
    (let [rule (assoc no-ai-attribution :rule/harnesses #{:claude-code})]
      (is (r/applies-to-harness? rule :claude-code))
      (is (not (r/applies-to-harness? rule :eca))))))

(deftest candidate-filters-on-enabled-phase-and-harness
  (let [ev {:guard/phase :pre-tool :guard/harness :eca :tool/name "Bash"}]
    (is (r/candidate? no-ai-attribution ev))
    (is (not (r/candidate? (assoc no-ai-attribution :rule/enabled? false) ev)))
    (is (not (r/candidate? no-ai-attribution (assoc ev :guard/phase :stop))))
    (is (not (r/candidate? (assoc no-ai-attribution :rule/harnesses #{:claude-code}) ev)))
    (testing "candidate? does NOT interpret :rule/match — that is the engine's"
      (is (r/candidate? no-ai-attribution
                        {:guard/phase :pre-tool :guard/harness :eca
                         :tool/name "TotallyUnrelatedTool"})))))

(deftest match-regexes-are-pattern-SOURCE-not-compiled
  (testing "so a rule round-trips through EDN in memory and over the wire"
    (is (= no-ai-attribution (edn/read-string (pr-str no-ai-attribution))))))

(defspec generated-rules-survive-edn-round-trip 100
  (prop/for-all [rule (mg/generator r/GuardRule)]
                (let [round (edn/read-string (pr-str rule))]
                  (and (r/valid? round) (= rule round)))))

(defspec generated-rule-sets-conform 50
  (prop/for-all [rules (mg/generator r/RuleSet)]
                (every? r/valid? rules)))

(deftest an-empty-match-is-a-phase-wide-rule
  (let [rule (r/guard-rule {:rule/id      :guard/carto-first-reminder
                            :rule/phases  #{:session-start :subagent-start}
                            :rule/match   {}
                            :rule/verdict :warn
                            :rule/reason  "carto is the interface for code questions"})]
    (is (r/valid? rule))
    (is (r/candidate? rule {:guard/phase :session-start :guard/harness :opencode}))
    (is (r/candidate? rule {:guard/phase :subagent-start :guard/harness :hive-agent}))
    (is (not (r/candidate? rule {:guard/phase :stop :guard/harness :hive-agent})))))
