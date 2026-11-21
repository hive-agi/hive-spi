(ns hive-spi.embeddings.space-test
  "EmbeddingSpace identity, the compatibility guard, the collection stamp,
   admission and the provenance audit. Pure: metadata maps in, verdicts out."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.embeddings.space :as space]
            [malli.core :as m]
            [malli.generator :as mg]))

;; SPDX-License-Identifier: MIT

(def ^:private nomic
  {:provider :ollama :model "nomic-embed-text" :dimension 768
   :digest "sha256:0a109f422b47" :metric :cosine :normalized? true
   :query-template-rev "q1" :doc-serializer-rev "d1"})

(def ^:private coll-name "hive-mcp-memory-8d")

(defn- space-of [overrides]
  (space/->space (merge {:provider :ollama :model "stub-embed" :digest "sha256:aaaa"
                         :dimension 8 :metric :l2 :query-template-rev "raw"
                         :doc-serializer-rev "memory-to-document/1"}
                        overrides)))

(deftest space-id-is-stable-and-moves-with-every-field
  (let [id (space/space-id nomic)]
    (is (m/validate space/SpaceId id))
    (is (= id (space/space-id (into (sorted-map) nomic))) "key order is irrelevant")
    (doseq [[k v] {:provider :openai :model "mxbai" :digest nil :dimension 1024
                   :metric :l2 :normalized? false :query-template-rev "q2"
                   :doc-serializer-rev "d2"}]
      (is (not= id (space/space-id (assoc nomic k v))) (str k)))
    (is (not= id space/unknown-space-id))
    (is (not (m/validate space/SpaceId space/unknown-space-id)))
    (is (m/validate space/SpaceId (mg/generate space/SpaceId)))))

(deftest invalid-space-is-refused
  (is (= :invalid-embedding-space
         (:error (ex-data (try (space/->space {:provider :ollama})
                               (catch clojure.lang.ExceptionInfo e e))))))
  (is (= "nomic-embed-text"
         (:model (space/config->space {:provider-type :ollama
                                       :model "nomic-embed-text"
                                       :dimension 768})))))

(deftest guard-refuses-a-different-or-unattributed-space
  (is (= (space/->space nomic) (space/check-compatible! nomic nomic)))
  (let [d (ex-data (try (space/check-compatible! nomic (assoc nomic :digest "x"))
                        (catch clojure.lang.ExceptionInfo e e)))]
    (is (= :embedding-space-mismatch (:error d)))
    (is (= [:digest] (:differs-on d)))
    (is (= #{:isolate :re-embed} (:remedy d))))
  (is (= space/unknown-space-id
         (:actual-id (ex-data (try (space/check-compatible! nomic nil)
                                   (catch clojure.lang.ExceptionInfo e e)))))))

(deftest stamp-round-trips-through-metadata
  (let [s (space-of {})
        md (space/space->metadata s)]
    (is (m/validate space/StampMetadata md))
    (is (= s (space/metadata->space md)))
    (is (= s (space/metadata->space (update-keys md name))) "string keys read the same")
    (is (nil? (space/metadata->space {:dimension 8})) "no stamp is legacy")
    (is (nil? (space/metadata->space nil)))))

(deftest admit-stamped-legacy-and-mismatch
  (let [s (space-of {})]
    (is (= :stamped (:verdict (space/admit coll-name s (space/space->metadata s)))))
    (is (= :legacy (:verdict (space/admit coll-name s {:dimension 8}))))
    (let [d (ex-data (try (space/admit coll-name s (space/space->metadata (space-of {:digest "sha256:bbbb"})))
                          (catch clojure.lang.ExceptionInfo e e)))]
      (is (= :embedding-space-mismatch (:error d)))
      (is (= coll-name (:collection d))))))

(deftest audit-reports-every-verdict-as-data
  (let [s (space-of {})]
    (testing "stamped with the expected space"
      (let [row (space/audit coll-name s (space/space->metadata s))]
        (is (= :stamped (:verdict row)))
        (is (= (space/space-id s) (:space-id row) (:expected-id row)))
        (is (nil? (:remedy row)))
        (is (m/validate space/AuditRow row))))
    (testing "no stamp at all is legacy"
      (let [row (space/audit coll-name s {:dimension 8})]
        (is (= :legacy (:verdict row)))
        (is (= space/unknown-space-id (:space-id row)))
        (is (= #{:isolate :re-embed} (:remedy row)))
        (is (m/validate space/AuditRow row))))
    (testing "a recorded space that differs is diverged, never thrown"
      (let [other (space-of {:digest "sha256:bbbb"})
            row   (space/audit coll-name s (space/space->metadata other))]
        (is (= :diverged (:verdict row)))
        (is (= (space/space-id other) (:space-id row)))
        (is (= [:digest] (:differing-keys row)))
        (is (m/validate space/AuditRow row))))
    (testing "with no expected space nothing is named"
      (is (= :stamped (:verdict (space/audit coll-name (space/space->metadata s)))))
      (is (not (contains? (space/audit coll-name nil) :expected-id))))
    (testing "differing keys come back in canonical order"
      (is (= [:model :digest]
             (:differing-keys (space/audit coll-name s (space/space->metadata
                                                        (assoc s :digest "sha256:bbbb" :model "other")))))))))
