(ns hive-spi.await.catalog
  "Which addon provides which await source type and secret scheme, as data.

   A host names the addon to install when a subscription asks for a type no
   source is registered for, without naming a backend in its own code. The
   catalog is advisory: a type absent here is still served by whatever
   registers it, and a missing one is reported with the known types instead
   of an addon name."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def sources
  "Source type -> {:addon addon-id :lib maven-coordinate}."
  {:sql-predicate {:addon "hive.await-sql" :lib "io.github.hive-agi/hive-await-sql"}
   :k8s-log-regex {:addon "hive.await-k8s" :lib "io.github.hive-agi/hive-await-k8s"}
   :prom-predicate {:addon "hive.await-prom" :lib "io.github.hive-agi/hive-await-prom"}})

(def schemes
  "Secret-ref scheme -> {:addon addon-id :lib maven-coordinate}."
  {"k8s"   {:addon "hive.await-k8s" :lib "io.github.hive-agi/hive-await-k8s"}
   "vault" {:addon "hive.cloak" :lib "io.github.hive-agi/hive-cloak"}})

(defn source-provider
  "The catalog entry for source TYPE, or nil. Pure."
  [type]
  (get sources type))

(defn scheme-provider
  "The catalog entry for secret scheme NAME, or nil. Pure."
  [name]
  (get schemes name))

(defn- known-text [known]
  (if (seq known) (str/join ", " (map str (sort-by str known))) "none"))

(defn missing-source
  "The ex-info a host throws when no source is registered for TYPE. KNOWN
   lists the types that are. Names the addon when the catalog knows one.
   Data: {:error :await/source-missing :type :known} plus :addon and :lib. Pure."
  [type known]
  (let [{:keys [addon lib]} (source-provider type)]
    (ex-info (if addon
               (str "await: source " type " needs addon " addon " (" lib "); none is registered")
               (str "await: no source registered for type " type "; known: " (known-text known)))
             (cond-> {:error :await/source-missing :type type :known (vec known)}
               addon (assoc :addon addon :lib lib)))))

(defn missing-scheme
  "The ex-info a host throws when no resolver is registered for secret
   scheme NAME. Names the addon when the catalog knows one. Data:
   {:error :await/scheme-missing :scheme :known} plus :addon and :lib. Pure."
  [name known]
  (let [{:keys [addon lib]} (scheme-provider name)]
    (ex-info (if addon
               (str "await: secret scheme " name " needs addon " addon " (" lib "); none is registered")
               (str "await: secret scheme " name " has no resolver; known: " (known-text known)))
             (cond-> {:error :await/scheme-missing :scheme name :known (vec known)}
               addon (assoc :addon addon :lib lib)))))

;; =============================================================================
;; Contracts
;; =============================================================================

(def Provider
  "A catalog entry."
  [:map [:addon :string] [:lib :string]])

(def ExInfo
  "An ex-info."
  [:fn {:error/message "must be an ex-info"} #(some? (ex-data %))])

(m/=> source-provider [:=> [:cat :any] [:maybe Provider]])
(tc/ann-op source-provider)
(m/=> scheme-provider [:=> [:cat :any] [:maybe Provider]])
(tc/ann-op scheme-provider)
(m/=> missing-source [:=> [:cat :any [:maybe [:sequential :any]]] ExInfo])
(tc/ann-op missing-source)
(m/=> missing-scheme [:=> [:cat :any [:maybe [:sequential :any]]] ExInfo])
(tc/ann-op missing-scheme)
