(ns hive-spi.test-cov-gaps-test
  "Direct tests for the public surface carto's test-cov detector ranked
   highest among 'called by production code, called by no test':
   diag.schema/NonNegLong, memory.ports count-entries / delete-entry!,
   editor.elisp Quotable / ElispCall / Feature, embeddings.space
   EmbeddingSpace, and the kanban IKanbanRead / IKanbanWrite ports.

   Every port is driven through its in-repo stub (injected, never redefined),
   so the tests pin the contract every adapter is held to."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-schemas.test :as hst]
            [hive-spi.diag.schema :as diag]
            [hive-spi.editor.elisp :as el]
            [hive-spi.embeddings.space :as space]
            [hive-spi.kanban :as kanban]
            [hive-spi.kanban.stub :as kanban-stub]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.stub :as mem-stub]
            [malli.core :as m]
            [malli.generator :as mg]))

;; SPDX-License-Identifier: MIT

;; =============================================================================
;; diag.schema/NonNegLong — the byte-count every diag reading is stamped with
;; =============================================================================

(deftest non-neg-long-admits-zero-positive-and-unavailable
  (is (m/validate diag/NonNegLong 0))
  (is (m/validate diag/NonNegLong 9007199254740993))
  (is (m/validate diag/NonNegLong nil) "an unavailable reading is nil, not 0"))

(deftest non-neg-long-rejects-negative-and-non-integral
  (is (not (m/validate diag/NonNegLong -1)))
  (is (not (m/validate diag/NonNegLong 1.5)))
  (is (not (m/validate diag/NonNegLong "1024"))))

(deftest non-neg-long-generates-only-what-it-admits
  (is (every? #(m/validate diag/NonNegLong %)
              (mg/sample diag/NonNegLong {:size 200 :seed 7}))))

;; =============================================================================
;; memory.ports count-entries / delete-entry! — over the stub store
;; =============================================================================

(defn- seeded-store []
  (doto (mem-stub/create-store)
    (ports/add-entry! {:id "a" :type :note :content "alpha" :tags ["x"]})
    (ports/add-entry! {:id "b" :type :decision :content "beta" :tags ["x" "y"]})
    (ports/add-entry! {:id "c" :type :note :content "gamma"})))

(deftest the-stub-store-implements-the-count-extension
  (is (satisfies? ports/IMemoryStoreCount (mem-stub/create-store))))

(deftest count-entries-counts-without-reading
  (let [s (seeded-store)]
    (testing "no filter counts everything"
      (is (= 3 (ports/count-entries s {}))))
    (testing "filter keys narrow like query-entries"
      (is (= 2 (ports/count-entries s {:type :note})))
      (is (= 1 (ports/count-entries s {:type :decision}))))
    (testing "the count agrees with a query of the same filter"
      (is (= (count (ports/query-entries s {:type :note}))
             (ports/count-entries s {:type :note}))))
    (testing ":limit is ignored by a count"
      (is (= 3 (ports/count-entries s {:limit 1}))))))

(deftest delete-entry!-removes-and-is-idempotent
  (let [s (seeded-store)]
    (is (true? (ports/delete-entry! s "a")))
    (is (nil? (ports/get-entry s "a")) "a deleted entry is gone")
    (is (= 2 (ports/count-entries s {})) "and no longer counted")
    (is (true? (ports/delete-entry! s "a")) "deleting it again is still true")
    (is (true? (ports/delete-entry! s "never-existed")))
    (is (= 2 (ports/count-entries s {})) "an unknown delete changes nothing")))

;; =============================================================================
;; editor.elisp — Quotable / Feature in, ElispCall out
;; =============================================================================

(deftest quotable-renders-every-branch
  (is (= "nil" (el/elisp-quote nil)))
  (is (= "\"s\"" (el/elisp-quote "s")))
  (is (= "'(1 \"a\")" (el/elisp-quote [1 "a"]))))

(deftest feature-admits-symbols-and-strings-only
  (is (m/validate el/Feature 'hive-mcp))
  (is (m/validate el/Feature "hive-mcp"))
  (is (not (m/validate el/Feature :hive-mcp))))

(deftest require-and-call-emits-one-guarded-form
  (let [src (el/require-and-call 'hive-mcp 'hive-mcp-status)]
    (is (m/validate el/ElispCall src) "a single parenthesised form")
    (is (re-find #"\(require 'hive-mcp nil t\)" src) "soft require, never an error on absence")
    (is (re-find #"\(fboundp 'hive-mcp-status\)" src) "guarded on the function existing")))

(deftest elisp-call-rejects-what-is-not-one-form
  (is (not (m/validate el/ElispCall "message")))
  (is (not (m/validate el/ElispCall ""))))

(hst/deftrifecta-from-schema require-and-call-is-always-an-elisp-call
  hive-spi.editor.elisp/require-and-call
  {:in  [:cat el/Feature el/Feature]
   :out el/ElispCall
   :rel (fn [[feature f] out]
          (and (str/includes? out (str "(require '" feature))
               (str/includes? out (str "(fboundp '" f ")"))))})

;; =============================================================================
;; embeddings.space/EmbeddingSpace — absence is never a wildcard
;; =============================================================================

(def ^:private base-space
  {:provider :ollama :model "nomic-embed-text" :digest "sha256:abc"
   :dimension 768 :metric :cosine :normalized? true
   :query-template-rev "q1" :doc-serializer-rev "d1"})

(deftest an-embedding-space-requires-every-identity-key
  (is (m/validate space/EmbeddingSpace base-space))
  (is (not (m/validate space/EmbeddingSpace (dissoc base-space :dimension))))
  (is (not (m/validate space/EmbeddingSpace (assoc base-space :dimension 0)))
      "a zero-dimension space is not a space"))

(deftest a-space-is-compatible-with-itself-only
  (is (true? (space/compatible? base-space base-space)))
  (is (false? (space/compatible? base-space (assoc base-space :model "other"))))
  (testing "an unrecorded digest is a different space, not a wildcard"
    (is (false? (space/compatible? base-space (assoc base-space :digest nil))))))

(deftest equal-spaces-share-an-id
  (is (= (space/space-id base-space) (space/space-id base-space)))
  (is (not= (space/space-id base-space)
            (space/space-id (assoc base-space :dimension 384)))))

(hst/deftrifecta-from-schema compatible?-is-reflexive-on-generated-spaces
  hive-spi.embeddings.space/compatible?
  {:in  [:cat space/EmbeddingSpace space/EmbeddingSpace]
   :out :boolean
   :rel (fn [[a b] out] (or (not= a b) (true? out)))})

;; =============================================================================
;; kanban IKanbanRead / IKanbanWrite — through the recording stub
;; =============================================================================

(deftest the-recording-stub-implements-both-ports
  (let [st (kanban-stub/recording-kanban [])]
    (is (satisfies? kanban/IKanbanRead st))
    (is (satisfies? kanban/IKanbanWrite st))))

(deftest reads-answer-the-seeded-board
  (let [st (kanban-stub/recording-kanban [{:id "t1" :title "a" :status "todo"}])]
    (is (= [{:id "t1" :title "a" :status "todo"}] (kanban/list-tasks st {})))
    (is (= "a" (:title (kanban/get-task st "t1"))))
    (is (nil? (kanban/get-task st "missing")))))

(deftest writes-answer-results-the-schemas-admit
  (let [st (kanban-stub/recording-kanban [{:id "t1" :title "a" :status "todo"}])]
    (testing "create answers {:ok {:id ..}}"
      (is (m/validate kanban/CreateResult (kanban/create-task! st {:title "new"}))))
    (testing "transition answers the moved task and the board reflects it"
      (let [res (kanban/transition! st {:task-id "t1" :new-status "done"})]
        (is (m/validate kanban/TransitionResult res))
        (is (= "done" (get-in res [:ok :status])))
        (is (= "done" (:status (kanban/get-task st "t1"))))))
    (testing "every port call is recorded in order"
      (is (= [:create-task! :transition! :get-task] (mapv first (kanban-stub/calls st)))))))
