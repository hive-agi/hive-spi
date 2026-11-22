(ns hive-spi.ingest.registry
  "The injection point for ingestion providers. Owner-scoped, process-local.

   A provider registers on init and retracts on shutdown. A write by an owner
   other than the incumbent is refused as :source-registry/owner-conflict.

   Rationale: hive memory 20260906013030-2d53c103."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defonce ^:private sources (atom {}))
(defonce ^:private rules (atom {}))

(defn- valid-owner? [owner]
  (and (some? owner) (not (str/blank? (str owner)))))

(defn- put!
  "Owner-scoped write into REG of the registration WRITE describes:
     {:owner .. :key .. :value .. :error-tag kw}
   :error-tag is the category a blank :key is refused under. A different
   owner cannot replace an existing registration.

   The positional (reg error-tag owner key value) arity is kept and delegates."
  ([reg error-tag owner key value]
   (put! reg {:error-tag error-tag :owner owner :key key :value value}))
  ([reg {:keys [error-tag owner key value]}]
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
             (r/ok {:key key :owner owner :replaced? false})))))))

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
    (put! sources {:error-tag :source-registry/invalid-source :owner owner
                   :key (str source-id) :value registration})))

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
    (put! rules {:error-tag :source-registry/invalid-rule :owner owner
                 :key (str rule-id)
                 :value (update registration :priority #(or % 100))})))

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

;; =============================================================================
;; Contracts
;; =============================================================================

(def RegistryError
  [:map [:error [:enum :source-registry/invalid-owner :source-registry/invalid-source
                 :source-registry/invalid-rule :source-registry/owner-conflict]]])

(def PutResult
  [:or [:map [:ok [:map [:key :any] [:owner :any] [:replaced? :boolean]]]] RegistryError])

(def DropResult
  [:or [:map [:ok [:map [:key :string] [:owner :any] [:removed? :boolean]]]] RegistryError])

(def Registry
  "One of the private registry atoms."
  [:fn {:gen/fmap (fn [_] (atom {})) :gen/schema :nil}
   #(instance? clojure.lang.IAtom %)])

(m/=> valid-owner? [:=> [:cat :any] :boolean])
(def Write
  "The registration `put!` writes."
  [:map [:error-tag :keyword] [:owner :any] [:key :any] [:value [:maybe :map]]])

(m/=> put! [:function
            [:=> [:cat Registry :keyword :any :any [:maybe :map]] PutResult]
            [:=> [:cat Registry Write] PutResult]])
(m/=> drop! [:=> [:cat Registry :any :any] DropResult])
(m/=> register-source! [:=> [:cat :any :any [:maybe :map]] PutResult])
(tc/ann-op register-source!)
(m/=> unregister-source! [:=> [:cat :any :any] DropResult])
(tc/ann-op unregister-source!)
(m/=> source-factory [:=> [:cat :any] [:maybe ifn?]])
(tc/ann-op source-factory)
(m/=> registered-sources [:=> [:cat] [:vector [:map [:source-id :string] [:owner :any]
                                                [:description :any] [:params :any]]]])
(tc/ann-op registered-sources)
(m/=> register-parser-rule! [:=> [:cat :any :any [:maybe :map]] PutResult])
(tc/ann-op register-parser-rule!)
(m/=> unregister-parser-rule! [:=> [:cat :any :any] DropResult])
(tc/ann-op unregister-parser-rule!)
(m/=> registered-rules [:=> [:cat] [:vector some?]])
(tc/ann-op registered-rules)
(m/=> retract-all! [:=> [:cat :any] [:map [:ok [:map [:owner :any]
                                               [:sources [:vector :string]]
                                               [:rules [:vector :string]]]]]])
(tc/ann-op retract-all!)
