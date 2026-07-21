(ns hive-spi.schema.help
  "Help-table projection over the hive core-op registry.

   One malli schema yields the per-param table a tool's `help` / `describe`
   surface renders. Loading this ns registers the :help projection into the
   derivation lever: every subsequent compile-op bundle carries :help.

   Row shape:
     :param        param name (keyword)
     :required?    boolean
     :type         JSON type token(s), '|'-joined; \"null\" is dropped from a
                   nullable union, \"any\" when the fragment declares no type
     :description  string | nil
     :enum         vector of allowed values | nil
     :default      declared default | nil
     :aliases      vector of alias keywords, [] when none are declared

   Rows are ordered required-first then optional, alphabetical by param name
   within each group.

   TOTAL by contract: a schema that does not resolve (unregistered ref,
   malformed form) yields nil; one that resolves without map entries yields [].
   Nothing here throws."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [hive-spi.schema.registry :as reg]
            [hive-spi.schema.derive :as der]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn- attempt
  "Value of `(f)`, or nil when it throws."
  [f]
  (try (f) (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- param-name
  "Sortable name of a param key: \"qn\" for :qn, \"a/b\" for :a/b, `str` for
   anything else."
  [x]
  (if (keyword? x) (subs (str x) 1) (str x)))

(defn- json-types
  "Ordered distinct JSON type tokens declared by JSON Schema fragment `j`,
   descending through :oneOf / :anyOf / :allOf branches. [] when none."
  [j]
  (let [t        (:type j)
        branches (concat (:oneOf j) (:anyOf j) (:allOf j))]
    (cond
      (string? t)               [t]
      (coll? t)                 (vec t)
      (seq branches)            (into [] (comp (mapcat json-types) (distinct)) branches)
      (contains? j :properties) ["object"]
      (contains? j :items)      ["array"]
      :else                     [])))

(defn- type-str
  "'|'-joined non-null type tokens of fragment `j`; \"null\" when null is the
   only token, \"any\" when there is none."
  [j]
  (let [ts (json-types j)
        nn (remove #{"null"} ts)]
    (cond
      (seq nn) (str/join "|" nn)
      (seq ts) "null"
      :else    "any")))

(defn- json-enum
  "Allowed values declared by fragment `j`, at its top level or in any
   :oneOf / :anyOf / :allOf branch. nil when none."
  [j]
  (if-let [e (:enum j)]
    (vec e)
    (let [vs (into [] (comp (mapcat #(or (json-enum %) [])) (distinct))
                   (concat (:oneOf j) (:anyOf j) (:allOf j)))]
      (when (seq vs) vs))))

(defn- alias-kw
  "Alias keyword of one AliasSpec value — a bare keyword, or the :alias of a
   spec map. nil for anything else."
  [a]
  (cond
    (keyword? a) a
    (map? a)     (let [k (:alias a)] (when (keyword? k) k))))

(defn- entry-aliases
  "Alias keywords declared under :aliases in entry properties `p`, in
   declaration order. [] when absent or unreadable."
  [p]
  (let [a (:aliases p)]
    (into [] (keep alias-kw)
          (cond
            (nil? a)        nil
            (sequential? a) a
            (set? a)        (sort-by str a)
            :else           [a]))))

(defn- help-row
  "One row for param `k` from its entry properties `p`, its JSON Schema
   fragment `j` and requiredness `required?`."
  [k p j required?]
  {:param       k
   :required?   required?
   :type        (type-str j)
   :description (or (:description p) (:description j))
   :enum        (json-enum j)
   :default     (if (contains? p :default) (:default p) (:default j))
   :aliases     (entry-aliases p)})

(defn schema->help-rows
  "Param help table for `?schema` — a registry key, an inline malli form or an
   already-compiled schema.

   -> [{:param kw :required? bool :type str :description str-or-nil
        :enum vector-or-nil :default any :aliases [kw]}]
   sorted required-first then optional, alphabetical by param name within each
   group. nil when `?schema` does not resolve; [] when it resolves to a schema
   carrying no map entries. Never throws.

   :required? and :type come from (der/input-schema ?schema); :description,
   :default and :aliases come from map ENTRY properties."
  [?schema]
  (when-let [s (attempt #(reg/schema ?schema))]
    (let [entries (attempt #(m/entries (m/deref-all s)))]
      (if-not (seq entries)
        []
        (let [js    (attempt #(der/input-schema s))
              props (:properties js)
              req   (into #{} (map param-name) (:required js))]
          (->> entries
               (map (fn [e]
                      (let [k (key e)
                            p (attempt #(m/properties (val e)))
                            j (or (get props k) (get props (param-name k)) {})]
                        (help-row k p j
                                  (if js
                                    (contains? req (param-name k))
                                    (not (:optional p)))))))
               (sort-by (juxt #(if (:required? %) 0 1) #(param-name (:param %))))
               vec))))))

(der/register-projection! :help schema->help-rows)
