(ns hive-spi.schema.capability
  "Capability-manifest projections over the hive core-op registry.

   A capability manifest is a DUCK-TYPED map — its schema lives in
   hive-addon.capability, which this namespace never requires:

     {:tool         string
      :owner        qualified-keyword
      :description  string
      :all-commands #{command-name}
      :commands     [{:command :summary :schema :examples
                      :stability :effects :group}]
      :envelope     #{keyword}}

   Projections, all pure:

     catalog            per-command summary + param-name split
     command-contract   one command's full contract, with help rows
     merge-params       per-param union across the manifest's schemas
     flat-input-schema  the advertised JSON-Schema wire view
     coverage           declared / schematized / total

   plus `resolve-params` (the resolution seam every projection reads) and
   `alias-map` / `fold-aliases` (param canonicalization).

   TOTAL by contract: an unresolvable :schema ref yields nil, never a throw.
   Nothing here validates the manifest — that is the host's job at the
   contribution boundary.

   Loading this namespace loads hive-spi.schema.help, registering the :help
   projection into every subsequent compile-op bundle."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hive-spi.schema.derive :as der]
            [hive-spi.schema.help :as help]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; =============================================================================
;; Envelope
;; =============================================================================

(def default-envelope
  "Cross-cutting call keys no op-schema declares: :command/:timeout/:timeout-ms
   from the dispatcher, :agent_id/:_caller_cwd injected by the MCP bridge.
   Used when a manifest carries no :envelope."
  #{:command :timeout :timeout-ms :agent_id :_caller_cwd})

(def default-envelope-properties
  "JSON-Schema fragment advertised for each envelope key. An envelope key absent
   from this map advertises {} (any value). Override per call through
   (:envelope-properties opts) of `flat-input-schema`."
  {:command     {:type "string"}
   :timeout     {:type "integer"
                 :description "Call timeout in seconds."}
   :timeout-ms  {:type "integer"
                 :description "Call timeout budget in milliseconds; takes priority over timeout. 0 means unbounded."}
   :agent_id    {:type "string"
                 :description "Calling agent identifier."}
   :_caller_cwd {:type "string"
                 :description "Calling agent's working directory."}})

(def ^:private default-command-description
  "Subcommand to dispatch. Must be one of the enumerated values.")

;; =============================================================================
;; Resolution
;; =============================================================================

(defn- attempt
  "Value of `(f)`, or nil when it throws."
  [f]
  (try (f) (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- prop-name
  "Wire name of a param key: \"qn\" for :qn, \"a/b\" for :a/b, `str` otherwise."
  [x]
  (if (keyword? x) (subs (str x) 1) (str x)))

(defn resolve-params
  "Param artifacts for `schema-ref` — a registry key, an inline malli form or an
   already-compiled schema.

   -> {:properties {param-kw json-fragment}
       :required   [param-kw ...]           ; declaration-independent, sorted
       :rows       [help-row ...]}          ; see hive-spi.schema.help

   nil when `schema-ref` does not resolve; :properties is {} and :required/:rows
   are empty when it resolves to a schema carrying no map entries. Never throws."
  [schema-ref]
  (when-let [rows (help/schema->help-rows schema-ref)]
    {:properties (get (attempt #(der/input-schema schema-ref)) :properties {})
     :required   (into [] (comp (filter :required?) (map :param)) rows)
     :rows       rows}))

;; =============================================================================
;; Aliases
;; =============================================================================

(defn alias-map
  "{alias-kw canonical-param-kw} for every alias declared in `?schema`'s entry
   :aliases properties. {} when none are declared, nil when `?schema` does not
   resolve. Never throws."
  [?schema]
  (when-let [rows (help/schema->help-rows ?schema)]
    (into {} (for [r rows, a (:aliases r)] [a (:param r)]))))

(defn fold-aliases
  "`params` with every alias key declared by `?schema` renamed to its canonical
   param. A canonical key already present in `params` WINS: the alias key is
   dropped, never merged over it. Competing aliases of one canonical param
   resolve in alias-name order. Returns `params` unchanged when it is not a map,
   when `?schema` does not resolve, or when no alias is declared. Never throws."
  [?schema params]
  (let [am (when (map? params) (alias-map ?schema))]
    (if (seq am)
      (let [present (filterv #(contains? am %) (keys params))]
        (reduce (fn [acc a]
                  (let [canonical (get am a)]
                    (if (contains? acc canonical)
                      acc
                      (assoc acc canonical (get params a)))))
                (apply dissoc params present)
                (sort-by prop-name present)))
      params)))

;; =============================================================================
;; Manifest accessors
;; =============================================================================

(defn- spec-status
  "CommandSpec -> :capability/declared when it carries a :schema, else
   :capability/undeclared."
  [{:keys [schema]}]
  (if schema :capability/declared :capability/undeclared))

(defn- declared
  "manifest -> set of command names carrying a CommandSpec."
  [manifest]
  (into #{} (map :command) (:commands manifest)))

(defn- schematized
  "manifest -> set of command names whose CommandSpec carries a :schema."
  [manifest]
  (into #{} (comp (filter :schema) (map :command)) (:commands manifest)))

(defn coverage
  "manifest -> {:tool :total :declared :schematized :undeclared
                :declared-pct :schematized-pct}.
   :total counts :all-commands; :undeclared is the sorted vector of dispatchable
   commands with no CommandSpec; percentages are 1.0 when the tool dispatches
   nothing."
  [manifest]
  (let [all (set (:all-commands manifest))
        d   (declared manifest)
        s   (schematized manifest)
        t   (count all)]
    {:tool            (:tool manifest)
     :total           t
     :declared        (count d)
     :schematized     (count s)
     :undeclared      (vec (sort (set/difference all d)))
     :declared-pct    (if (zero? t) 1.0 (double (/ (count d) t)))
     :schematized-pct (if (zero? t) 1.0 (double (/ (count s) t)))}))

;; =============================================================================
;; Catalog + contract
;; =============================================================================

(defn catalog
  "manifest -> sorted {command-name {:summary :required :optional :status
                                     :stability :effects :group}}.

   One entry per DECLARED command. :required/:optional are vectors of param
   keywords, both empty when the command declares no :schema or its :schema does
   not resolve. :stability defaults to :stability/stable and :effects to
   #{:effect/read}; :group is nil when undeclared. Never throws."
  [manifest]
  (into (sorted-map)
        (map (fn [{:keys [command summary stability effects group] :as spec}]
               (let [{:keys [required rows]} (resolve-params (:schema spec))]
                 [command {:summary   summary
                           :required  (vec required)
                           :optional  (into [] (comp (remove :required?) (map :param)) rows)
                           :status    (spec-status spec)
                           :stability (or stability :stability/stable)
                           :effects   (or effects #{:effect/read})
                           :group     group}])))
        (:commands manifest)))

(defn command-contract
  "manifest -> command -> the full contract for one command.

   -> {:tool :command :summary :status :stability :effects :group :schema
       :accepted {:required [help-row ...] :optional [help-row ...]}
       :examples [...]}

   :accepted is nil whenever the accepted params are unknown — the command
   declares no :schema, or its :schema does not resolve. nil overall when
   `command` is neither declared nor a member of :all-commands. Never throws."
  [manifest command]
  (let [spec (first (filter #(= command (:command %)) (:commands manifest)))]
    (when (or spec (contains? (set (:all-commands manifest)) command))
      (let [rows                 (:rows (resolve-params (:schema spec)))
            {req true opt false} (group-by :required? rows)]
        {:tool      (:tool manifest)
         :command   command
         :summary   (:summary spec)
         :status    (if spec (spec-status spec) :capability/undeclared)
         :stability (or (:stability spec) :stability/stable)
         :effects   (or (:effects spec) #{:effect/read})
         :group     (:group spec)
         :schema    (:schema spec)
         :accepted  (when rows {:required (vec req) :optional (vec opt)})
         :examples  (vec (:examples spec))}))))

;; =============================================================================
;; Per-param merge
;; =============================================================================

(defn- prop-description
  "Non-blank :description carried by JSON-Schema fragment `j` — on the fragment
   itself, else on the first :allOf/:anyOf/:oneOf branch that carries one. nil
   when it carries none."
  [j]
  (when (map? j)
    (or (let [d (:description j)]
          (when-not (str/blank? d) d))
        (some prop-description
              (concat (:allOf j) (:anyOf j) (:oneOf j))))))

(defn- better-candidate?
  "True when candidate `b` displaces `a` as a param's advertised property: a
   described candidate beats an undescribed one, and between two described
   candidates the longer :description wins. Equal or shorter keeps `a`. Both
   candidates are {:prop json :schema schema-ref :desc str-or-nil}."
  [a b]
  (let [da (:desc a)
        db (:desc b)]
    (boolean
     (and db (or (nil? da) (> (count db) (count da)))))))

(defn- absorb-schema
  "Fold `schema-ref`'s derived JSON-Schema :properties into the per-param
   accumulator {param-kw {:prop json :schema schema-ref :desc str-or-nil
                          :described [{:schema schema-ref :desc str} ...]}}.
   Each param resolves independently via `better-candidate?`; :described
   collects every candidate carrying a description, winner included. An
   unresolvable `schema-ref` contributes nothing."
  [acc schema-ref]
  (reduce (fn [acc [param prop]]
            (let [cand    {:prop prop :schema schema-ref :desc (prop-description prop)}
                  current (get acc param)
                  winner  (if (or (nil? current) (better-candidate? current cand))
                            cand
                            current)
                  seen    (cond-> (:described current [])
                            (:desc cand) (conj {:schema schema-ref :desc (:desc cand)}))]
              (assoc acc param (assoc winner :described seen))))
          acc
          (:properties (resolve-params schema-ref))))

(defn merge-params
  "Deterministic per-param union of the JSON-Schema :properties derived from the
   distinct :schema refs of `manifest`'s declared commands, plus the merge
   report.

   Refs are walked in (sort-by :command) order, each param resolved
   independently: a candidate carrying a non-blank :description beats one that
   carries none, the longer :description wins between two described candidates,
   and the earlier ref breaks any remaining tie.

   -> {:properties              {param-kw json-fragment}   ; keyword keys
       :conflicts               [{:param :winner :losers [schema-ref ...]}]
       :description-regressions [{:param :winner :sources [schema-ref ...]}]
       :undescribed             [param-kw ...]}            ; sorted

   :conflicts lists params for which two refs supply non-equal descriptions.
   :description-regressions lists params whose merged fragment carries NO
   description although some ref supplies one. :undescribed lists params no ref
   documents. Never throws."
  [manifest]
  (let [refs   (into [] (comp (keep :schema) (distinct))
                     (sort-by :command (:commands manifest)))
        merged (reduce absorb-schema {} refs)
        report (fn [pred build]
                 (->> merged
                      (keep (fn [[param entry]] (when (pred entry) (build param entry))))
                      (sort-by :param)
                      vec))]
    {:properties  (reduce-kv (fn [m param entry] (assoc m param (:prop entry))) {} merged)
     :conflicts   (report (fn [{:keys [desc described]}]
                            (some #(not= (:desc %) desc) described))
                          (fn [param {:keys [schema desc described]}]
                            {:param  param
                             :winner schema
                             :losers (->> described
                                          (remove #(= (:desc %) desc))
                                          (map :schema)
                                          distinct
                                          sort
                                          vec)}))
     :description-regressions
     (report (fn [{:keys [desc described]}] (and (nil? desc) (seq described)))
             (fn [param {:keys [schema described]}]
               {:param   param
                :winner  schema
                :sources (->> described (map :schema) distinct sort vec)}))
     :undescribed (->> merged
                       (keep (fn [[param {:keys [desc]}]] (when-not desc param)))
                       sort
                       vec)}))

;; =============================================================================
;; Wire view
;; =============================================================================

(defn- stringify-props
  "{param-key json-fragment} with every key rendered as its wire name."
  [props]
  (reduce (fn [m [k v]] (assoc m (prop-name k) v))
          {}
          (sort-by (comp prop-name key) props)))

(defn flat-input-schema
  "manifest -> the advertised MCP :inputSchema view.

   -> {:type \"object\"
       :properties {wire-name json-fragment}   ; STRING keys
       :required [\"command\"]
       :x-hive/commands <catalog>
       :x-hive/coverage <coverage>}

   The \"command\" property's :enum is the sorted vector of :all-commands, so
   every dispatchable command reaches the wire. Per-command required params are
   NOT expressible at this root; they live in :x-hive/commands and in
   `command-contract`.

   Properties are layered, later winning: merged params, then :extra-properties,
   then the envelope, then \"command\".

   opts
     :properties-mode      :merged (default) | :envelope-only — whether the
                           merged param properties are advertised at all
     :envelope-properties  {envelope-kw json-fragment} merged over
                           `default-envelope-properties`
     :extra-properties     {name-or-kw json-fragment} folded in before the
                           envelope
     :command-description  :description of the \"command\" property

   Never throws."
  ([manifest] (flat-input-schema manifest {}))
  ([manifest {:keys [properties-mode envelope-properties extra-properties
                     command-description]}]
   (let [envelope  (or (:envelope manifest) default-envelope)
         env-props (merge default-envelope-properties envelope-properties)
         shared    (when-not (= properties-mode :envelope-only)
                     (:properties (merge-params manifest)))
         cmd-prop  (assoc (get env-props :command {})
                          :type "string"
                          :description (or command-description
                                           default-command-description)
                          :enum (vec (sort (:all-commands manifest))))]
     {:type       "object"
      :properties (-> (stringify-props shared)
                      (merge (stringify-props extra-properties))
                      (merge (stringify-props
                              (into {} (map (fn [k] [k (get env-props k {})]))
                                    (disj (set envelope) :command))))
                      (assoc "command" cmd-prop))
      :required   ["command"]
      :x-hive/commands (catalog manifest)
      :x-hive/coverage (coverage manifest)})))
