(ns hive-spi.ingest.registry
  "The injection point for ingestion providers. Owner-scoped, process-local.

   A provider registers on init and retracts on shutdown. A write by an owner
   other than the incumbent is refused as :source-registry/owner-conflict.

   Rationale: hive memory 20260906013030-2d53c103."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defonce ^:private sources (atom {}))
(defonce ^:private rules (atom {}))

(defn- valid-owner? [owner]
  (and (some? owner) (not (str/blank? (str owner)))))

(defn- put!
  "Owner-scoped write into REG under KEY. A different owner cannot replace an
   existing registration."
  [reg error-tag owner key value]
  (cond
    (not (valid-owner? owner))
    (r/err :source-registry/invalid-owner {:owner owner})

    (str/blank? (str key))
    (r/err error-tag {:key key})

    :else
    (locking reg
      (if-let [existing (get @reg key)]
        (if (= owner (:owner existing))
          (do (swap! reg assoc key (assoc value :owner owner))
              (r/ok {:key key :owner owner :replaced? true}))
          (r/err :source-registry/owner-conflict
                 {:key key :owner owner :registered-owner (:owner existing)}))
        (do (swap! reg assoc key (assoc value :owner owner))
            (r/ok {:key key :owner owner :replaced? false}))))))

(defn- drop!
  "Owner-scoped removal from REG. Removing what was never there succeeds with
   :removed? false, so a shutdown path is idempotent."
  [reg owner key]
  (let [key (str key)]
    (locking reg
      (if-let [existing (get @reg key)]
        (if (= owner (:owner existing))
          (do (swap! reg dissoc key)
              (r/ok {:key key :owner owner :removed? true}))
          (r/err :source-registry/owner-conflict
                 {:key key :owner owner :registered-owner (:owner existing)}))
        (r/ok {:key key :owner owner :removed? false})))))

(defn register-source!
  "Register a source adapter under SOURCE-ID.

   :factory      (fn [opts] -> ISource) — required
   :description  human-readable, surfaced by listings
   :params       map of param name -> description, for a tool surface"
  [owner source-id {:keys [factory] :as registration}]
  (if-not (fn? factory)
    (r/err :source-registry/invalid-source {:source-id source-id})
    (put! sources :source-registry/invalid-source owner (str source-id) registration)))

(defn unregister-source!
  "Remove a source registration only when called by its owner."
  [owner source-id]
  (drop! sources owner source-id))

(defn source-factory
  "The registered factory for SOURCE-ID, or nil."
  [source-id]
  (:factory (get @sources (str source-id))))

(defn registered-sources
  "Stable registration metadata, without callback values."
  []
  (->> @sources
       (map (fn [[id registration]]
              {:source-id   id
               :owner       (:owner registration)
               :description (:description registration)
               :params      (:params registration)}))
       (sort-by :source-id)
       vec))

(defn register-parser-rule!
  "Register an IParserRule under RULE-ID.

   Registered rules are consulted BEFORE any built-in chain, so a provider can
   claim a response shape the pipeline would otherwise hand to a generic
   parser.

   :rule      an IParserRule implementation — required
   :priority  lower runs first among registered rules (default 100)"
  [owner rule-id {:keys [rule] :as registration}]
  (if (nil? rule)
    (r/err :source-registry/invalid-rule {:rule-id rule-id})
    (put! rules :source-registry/invalid-rule owner (str rule-id)
          (update registration :priority #(or % 100)))))

(defn unregister-parser-rule!
  "Remove a rule registration only when called by its owner."
  [owner rule-id]
  (drop! rules owner rule-id))

(defn registered-rules
  "Registered IParserRule values in precedence order."
  []
  (->> (vals @rules)
       (sort-by (juxt :priority :owner))
       (mapv :rule)))

(defn retract-all!
  "Remove every registration owned by OWNER. Called from addon shutdown."
  [owner]
  (let [owned (fn [reg] (into [] (keep (fn [[k v]] (when (= owner (:owner v)) k))) @reg))
        source-keys (owned sources)
        rule-keys   (owned rules)]
    (locking sources (swap! sources #(apply dissoc % source-keys)))
    (locking rules (swap! rules #(apply dissoc % rule-keys)))
    (r/ok {:owner owner :sources source-keys :rules rule-keys})))
