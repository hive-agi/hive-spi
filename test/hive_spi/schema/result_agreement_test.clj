(ns hive-spi.schema.result-agreement-test
  "MALLI-P4: executable spec<->malli convergence contract for the Result taxonomy.

   Pins the exact relationship between the validation layers so they cannot
   silently drift (the 'two validation systems' split, 2026-07-10):

     - membership predicates  hive-dsl.result/{ok?,err?}   key presence; total, loose
     - malli shape schemas    hive-spi.schema.registry      :hive/{ok,err,result}
     - clojure.spec contracts hive-dsl.result.spec          ::{ok-result,err-result,result}
     - category set           hive-dsl.result.taxonomy      (single source of categories)

   CANONICAL Results (produced by r/ok / r/err with qualified-keyword categories)
   agree across ALL layers, biconditionally. This is the operational contract.

   BOUNDARY (non-canonical inputs) is characterized explicitly. After the
   :hive/result XOR fix (both-keys -> invalid, MALLI-P4 D1b) the ONLY remaining
   malli/spec divergence is unqualified-keyword errors, where the RELEASED
   hive-dsl spec (::err-result uses keyword?) is looser than malli
   (:qualified-keyword). That is boarded fix D5; `spec-err-result-loose-D5-guard`
   is a tripwire that flips when hive-dsl tightens ::err-result -> ::error-category."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.spec.alpha :as s]
            [malli.generator :as mg]
            [hive-spi.schema.registry :as reg]
            [hive-dsl.result :as r]
            [hive-dsl.result.spec :as rspec]
            [hive-dsl.result.taxonomy :as tax]))

;; ---------------------------------------------------------------------------
;; Generators sourced from the SINGLE category truth (taxonomy)
;; ---------------------------------------------------------------------------

(def known-category-gen
  "Deterministic gen over the CURRENT taxonomy (read at generate-time so new
   consumer categories are covered; seed-stable via gen/nat)."
  (gen/let [i gen/nat]
    (let [cats (vec (tax/registered-categories))]
      (nth cats (mod i (count cats))))))

(def ok-gen  (gen/fmap r/ok gen/any-printable))

(def err-gen
  (gen/let [c      known-category-gen
            extras (gen/map gen/keyword-ns gen/any-printable {:max-elements 3})]
    (r/err c (dissoc extras :ok :error))))

(def canonical-result-gen (gen/one-of [ok-gen err-gen]))

;; ---------------------------------------------------------------------------
;; 1. CANONICAL biconditional agreement (the operational contract)
;; ---------------------------------------------------------------------------

(defspec malli-generated-results-agree-all-layers 300
  ;; malli's OWN shape generator -> agrees with spec + membership predicates
  (prop/for-all [x (mg/generator (reg/schema :hive/result))]
    (and (reg/validate :hive/result x)                       ; malli-gen only yields valid
         (= (reg/validate :hive/result x) (s/valid? ::rspec/result x))
         (= (r/ok?  x) (reg/validate :hive/ok  x) (s/valid? ::rspec/ok-result  x))
         (= (r/err? x) (reg/validate :hive/err x) (s/valid? ::rspec/err-result x)))))

(defspec constructor-results-agree-all-layers 300
  ;; realistic taxonomy-sourced Results -> cross-layer agreement + exclusivity.
  ;; (conjuncts are cross-layer — predicate vs malli vs spec — not a schema restated
  ;; against itself; the boundary specs below carry the divergence-surface coverage.)
  (prop/for-all [x canonical-result-gen]
    (and (reg/validate :hive/result x)                        ; malli accepts canonical
         (s/valid? ::rspec/result x)                          ; spec accepts canonical
         (= (r/ok? x) (not (r/err? x)))                       ; generated Results are exclusive
         (= (r/ok?  x) (reg/validate :hive/ok  x))            ; membership <=> malli :hive/ok
         (= (r/err? x) (reg/validate :hive/err x)))))         ; membership <=> malli :hive/err (canonical: qualified)

;; ---------------------------------------------------------------------------
;; 2. Category truth: generation is a subset of the taxonomy, across layers
;; ---------------------------------------------------------------------------

(defspec known-category-generation-subset-of-taxonomy 300
  (prop/for-all [c known-category-gen]
    (and (tax/known-error? c)
         (reg/validate :hive/known-error-category c)
         (s/valid? ::rspec/known-error-category c))))

(deftest known-error-category-schema-is-generatable
  (testing "MALLI-P4 D1: :hive/known-error-category carries :gen/elements; a bare
            :fn predicate alone throws malli such-that-failure"
    (let [xs (mg/sample (reg/schema :hive/known-error-category) {:size 30})]
      (is (seq xs))
      (is (every? tax/known-error? xs)))))

;; ---------------------------------------------------------------------------
;; 3. Exclusive sum: illegal states rejected by BOTH validity layers
;; ---------------------------------------------------------------------------

(deftest both-keys-rejected-by-malli-and-spec
  (testing "MALLI-P4 D1b: {:ok _ :error _} is not a Result (XOR). Converged this session."
    (let [x {:ok 1 :error :io/timeout}]
      (is (not (reg/validate :hive/result x)) "malli :hive/result rejects both-keys")
      (is (not (s/valid? ::rspec/result x))   "spec ::result rejects both-keys")
      (is (and (r/ok? x) (r/err? x))
          "membership predicates are BOTH true here (loose by design)"))))

(deftest structural-non-results-rejected-by-all
  (testing "non-maps / empty map are not Results in any layer"
    (doseq [x [{} nil "s" 42 [:ok 1] :ok]]
      (is (not (reg/validate :hive/result x)) (str "malli rejects " (pr-str x)))
      (is (not (s/valid? ::rspec/result x))   (str "spec rejects "  (pr-str x)))
      (is (not (or (r/ok? x) (r/err? x)))     (str "membership: neither " (pr-str x))))))

;; ---------------------------------------------------------------------------
;; 3b. BOUNDARY property coverage — exercises the DIVERGENCE surface that the
;;     canonical generators never reach (both-keys, neither, unqualified-kw and
;;     non-keyword errors, non-maps). These are what actually guard the two
;;     malli-side contract changes (XOR exclusivity + qualified-kw error); a
;;     revert of either now fails a PROPERTY, not just a single-example deftest.
;; ---------------------------------------------------------------------------

(def adversarial-result-gen
  "Draws ACROSS the layer-disagreement boundary, not just the agreement region."
  (gen/one-of
   [canonical-result-gen
    (gen/fmap (fn [c] {:ok 1 :error c}) known-category-gen)   ; both-keys, qualified cat
    (gen/return {:ok 1 :error :x})                            ; both-keys, unqualified
    (gen/return {})                                           ; neither
    (gen/fmap (fn [k] {:error k}) gen/keyword)                ; unqualified-kw error (D5)
    (gen/fmap (fn [s] {:error s}) gen/string)                 ; non-keyword error
    (gen/elements [nil 42 "s" [:ok 1] :ok])]))                ; non-maps

(defspec boundary-malli-result-is-exclusive-qualified-sum 400
  ;; :hive/result validity == EXACTLY (exclusive) AND (ok OR qualified-keyword err).
  ;; Reverting the XOR dispatch (both-keys -> :ok) OR loosening :hive/err to accept
  ;; unqualified keywords breaks this equality on generated boundary inputs.
  (prop/for-all [x adversarial-result-gen]
    (let [o (r/ok? x) e (r/err? x)
          expected (or (and o (not e))
                       (and e (not o) (qualified-keyword? (:error x))))]
      (= (reg/validate :hive/result x) (boolean expected)))))

(defspec boundary-malli-spec-agree-except-unqualified-kw 400
  ;; malli :hive/result == spec ::result EVERYWHERE except the boarded D5 case
  ;; (unqualified-keyword error), where the released spec is loose and malli strict.
  (prop/for-all [x adversarial-result-gen]
    (let [d5? (and (r/err? x) (not (r/ok? x))
                   (keyword? (:error x)) (not (qualified-keyword? (:error x))))]
      (if d5?
        (and (not (reg/validate :hive/result x)) (s/valid? ::rspec/result x))
        (= (reg/validate :hive/result x) (s/valid? ::rspec/result x))))))

;; ---------------------------------------------------------------------------
;; 4. BOUNDARY: the ONE remaining divergence (boarded D5 tripwire)
;; ---------------------------------------------------------------------------

(deftest spec-err-result-loose-D5-guard
  (testing "BOARDED D5: released hive-dsl spec ::err-result uses keyword? (loose);
            malli :hive/err uses :qualified-keyword (canonical, matches taxonomy).
            They diverge ONLY on unqualified-keyword errors. When hive-dsl tightens
            ::err-result -> ::error-category this test flips and must be updated."
    (let [x {:error :unqualified}]                 ; unqualified-keyword category
      (is (r/err? x)                          "membership: err (key present)")
      (is (s/valid? ::rspec/err-result x)     "released spec ACCEPTS unqualified (loose) — flips on D5")
      (is (not (reg/validate :hive/err x))    "malli REJECTS unqualified (canonical qualified-kw)"))
    (testing "string errors: both validity layers already agree (reject)"
      (let [x {:error "not-a-keyword"}]
        (is (not (s/valid? ::rspec/err-result x)))
        (is (not (reg/validate :hive/err x)))))))
