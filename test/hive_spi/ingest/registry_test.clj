(ns hive-spi.ingest.registry-test
  "Owner-scoped registration: a provider registers on init and retracts on
   shutdown, and no other owner may replace what it registered."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.ingest.registry :as reg]
            [hive-test.golden :refer [deftest-golden]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private test-owners #{"addon-a" "addon-b" :owner/x "o"})

(use-fixtures :each
  (fn [f]
    (try (f) (finally (doseq [o test-owners] (reg/retract-all! o))))))

(def ^:private Owner [:enum "addon-a" "addon-b" :owner/x "" nil])
(def ^:private SourceId [:enum "notion" "jira" "rfc"])

(hst/deftrifecta-from-schema register-source-is-owner-scoped
  hive-spi.ingest.registry/register-source!
  {:in  [:cat Owner SourceId [:map [:factory [:= {:gen/return identity} identity]]]]
   :out reg/PutResult
   :rel (fn [[owner sid _] out]
          (let [incumbent (some #(when (= sid (:source-id %)) (:owner %))
                                (reg/registered-sources))]
            (cond
              (or (nil? owner) (= "" owner)) (= :source-registry/invalid-owner (:error out))
              (:ok out) (= owner incumbent)
              :else (and (= :source-registry/owner-conflict (:error out))
                         (not= owner incumbent)))))})

(deftest a-registration-without-a-factory-is-refused
  (is (= :source-registry/invalid-source
         (:error (reg/register-source! "o" "x" {:description "no factory"})))))

(deftest unregistering-what-was-never-there-succeeds
  (is (= {:ok {:key "ghost" :owner "o" :removed? false}}
         (reg/unregister-source! "o" "ghost"))))

(defn- rule [id] (reify Object (toString [_] (str "rule-" id))))

(deftest-golden owner-lifecycle
  "test/golden/hive_spi/ingest/registry.edn"
  (let [f identity]
    [(reg/register-source! "addon-a" "notion" {:factory f :description "Notion"})
     (reg/register-source! "addon-a" "notion" {:factory f :description "Notion v2"})
     (reg/register-source! "addon-b" "notion" {:factory f})
     (reg/register-source! "addon-b" "jira" {:factory f :params {:limit "max"}})
     (reg/registered-sources)
     (some? (reg/source-factory "notion"))
     (reg/unregister-source! "addon-b" "notion")
     (reg/register-parser-rule! "addon-a" :r/late {:rule (rule 1) :priority 200})
     (reg/register-parser-rule! "addon-b" :r/early {:rule (rule 2) :priority 10})
     (reg/register-parser-rule! "addon-a" :r/none {})
     (mapv str (reg/registered-rules))
     (reg/retract-all! "addon-a")
     (reg/registered-sources)
     (mapv str (reg/registered-rules))]))
