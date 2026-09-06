(ns hive-spi.ingest.tck-test
  "Proof that the ingestion seam works in the direction that is hard.

   The easy direction is FOSS <- proprietary: a closed addon consuming open
   libraries. The fleet has that everywhere.

   This is the other one. A CLOSED pipeline, extended by a provider its author
   could not read, could not resolve, and never needed. The falsifiable claim
   is exactly this:

     a working provider can be written, registered, discovered and PROVEN
     correct with hive-spi alone on the classpath.

   Everything below therefore requires only hive-spi.ingest.*. If any of it
   ever needs the pipeline, the seam has leaked and this file stops compiling,
   which is the point of putting the proof here rather than in the host.

   `community-corpus` stands in for a third-party provider. `broken-corpus` is
   the control: a kit that cannot fail is not evidence, so the same laws are
   run against a provider that violates them and must report those violations."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-spi.ingest.model :as model]
            [hive-spi.ingest.ports :as ports]
            [hive-spi.ingest.registry :as registry]
            [hive-spi.ingest.tck :as tck]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; A provider a community author could have written
;; =============================================================================

(defn- doc
  "Built through the SPI's own smart constructor, which is the only Document
   constructor a third party has."
  [id text]
  (let [made (model/make-document {:id id
                                   :source (str "memo://" id)
                                   :format :format/markdown
                                   :content text})]
    (:ok made)))

(defrecord CommunityCorpus [entries]
  ports/ISource
  (source-id [_] "community-memo")
  (fetch-documents [_ {:keys [limit]}]
    (let [docs (mapv (fn [[id text]] (doc id text)) entries)]
      (r/ok (if (pos-int? limit) (vec (take limit docs)) docs))))

  ports/ISourceHealth
  (source-health [_] {:status :ok :details {:entries (count entries)}}))

(def ^:private community
  (->CommunityCorpus [["a" "first memo"] ["b" "second memo"] ["c" "third memo"]]))

;; The control. Each violation is deliberate and named.
(defrecord BrokenCorpus []
  ports/ISource
  (source-id [_] "Broken Corpus")                    ; not lowercase-hyphenated
  (fetch-documents [_ _]
    (r/ok [{:document/id "dup" :document/source "x://1" :document/content "one"}
           {:document/id "dup" :document/source "x://2" :document/content "two"}
           {:document/id "" :document/source "x://3" :document/content "three"}
           {:document/id "d" :document/source "x://4" :document/content "four"
            :document/format :format/invented}])))   ; undeclared variant

(def ^:private broken (->BrokenCorpus))

(def ^:private fixtures
  {:opts {} :limit-opts {:limit 2}})

;; =============================================================================
;; The claim
;; =============================================================================

(deftest a-provider-written-against-the-spi-alone-conforms
  (let [report (tck/conform community fixtures)]
    (is (:ok report) (tck/explain community fixtures))
    (testing "and it was measured at BOTH rungs, not just the static one"
      (is (= #{:rung/descriptor :rung/behaviour} (:rungs-measured report))))
    (testing "with nothing skipped: this provider implements the optional health port too"
      (is (empty? (:skips report)) (pr-str (:skips report))))))

(deftest the-kit-can-actually-fail
  (testing "a kit that passes everything is not evidence"
    (let [report (tck/conform broken fixtures)
          failed (set (map :law/id (:failures report)))]
      (is (not (:ok report)))
      (testing "and it names each violation specifically"
        (is (contains? failed :source/id-follows-convention))
        (is (contains? failed :source/documents-are-well-formed))
        (is (contains? failed :source/document-ids-are-unique))
        (is (contains? failed :source/format-is-a-declared-variant))))))

(deftest an-unimplemented-optional-port-is-skipped-never-passed
  (testing "BrokenCorpus omits ISourceHealth, so that law must not count as a pass"
    (let [report (tck/conform broken fixtures)
          skipped (set (map :law/id (:skips report)))]
      (is (contains? skipped :source/health-reports-a-known-status))
      (is (not (contains? (set (:passes report)) :source/health-reports-a-known-status))))))

(deftest a-missing-fixture-is-skipped-never-passed
  (testing "no :limit-opts supplied means the limit law was NOT measured"
    (let [report (tck/conform community {:opts {}})
          skipped (set (map :law/id (:skips report)))]
      (is (contains? skipped :source/limit-is-honoured))
      (is (not (contains? (set (:passes report)) :source/limit-is-honoured))))))

(deftest a-report-that-measured-nothing-says-so
  (testing ":ok alone is not a claim; :rungs-measured is what carries the weight"
    (let [report (tck/conform community {})]
      (is (:ok report))
      (is (= #{:rung/descriptor} (:rungs-measured report))
          "with no fixtures, only the static rung ran"))))

;; =============================================================================
;; Registration, the other half of the seam
;; =============================================================================

(deftest a-provider-registers-and-is-discovered-through-the-spi
  (registry/retract-all! "test-owner")
  (try
    (let [reg (registry/register-source! "test-owner" "community-memo"
                                         {:factory (fn [_] community)
                                          :description "an in-memory corpus"})]
      (is (r/ok? reg))
      (testing "the pipeline side finds it without knowing who registered it"
        (let [factory (registry/source-factory "community-memo")]
          (is (fn? factory))
          (is (= "community-memo" (ports/source-id (factory {}))))))
      (testing "and it appears in the listing with its owner"
        (is (some #(= "community-memo" (:source-id %)) (registry/registered-sources)))))
    (finally (registry/retract-all! "test-owner"))))

(deftest one-owner-cannot-clobber-another
  (registry/retract-all! "owner-a")
  (registry/retract-all! "owner-b")
  (try
    (registry/register-source! "owner-a" "shared-id" {:factory (fn [_] community)})
    (let [attempt (registry/register-source! "owner-b" "shared-id" {:factory (fn [_] broken)})]
      (is (r/err? attempt) "a second owner must be refused, not silently win")
      (testing "and the original registration is untouched"
        (is (= "community-memo"
               (ports/source-id ((registry/source-factory "shared-id") {}))))))
    (finally
      (registry/retract-all! "owner-a")
      (registry/retract-all! "owner-b"))))

(deftest retract-all-removes-only-its-own-owner
  (registry/retract-all! "owner-a")
  (registry/retract-all! "owner-b")
  (try
    (registry/register-source! "owner-a" "corpus-a" {:factory (fn [_] community)})
    (registry/register-source! "owner-b" "corpus-b" {:factory (fn [_] community)})
    (registry/retract-all! "owner-a")
    (is (nil? (registry/source-factory "corpus-a")))
    (is (some? (registry/source-factory "corpus-b"))
        "a provider shutting down must not take its neighbours with it")
    (finally
      (registry/retract-all! "owner-a")
      (registry/retract-all! "owner-b"))))

(deftest the-law-set-is-open
  (testing "a corpus contributes a law only it can state, without editing the kit"
    (try
      (tck/register-law!
       {:law/id :memo/ids-are-single-letters
        :law/rung :rung/behaviour
        :law/inputs #{:opts}
        :law/summary "This corpus numbers its memos with single letters."
        :law/check (fn [{:keys [source opts]}]
                     (every? #(= 1 (count (:document/id %)))
                             (:ok (ports/fetch-documents source opts))))})
      (is (contains? (set (map :law/id (tck/laws))) :memo/ids-are-single-letters))
      (is (contains? (set (:passes (tck/conform community fixtures)))
                     :memo/ids-are-single-letters))
      (finally (tck/unregister-law! :memo/ids-are-single-letters)))))
