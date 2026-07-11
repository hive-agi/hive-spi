(ns hive-spi.schema.typed
  "Derive Typed Clojure type syntax (as data) from a hive-registry malli schema.

   `schema->type` maps a schema to its validator-type (the type of values that
   pass m/validate):
     :map -> t/HMap, :multi/:or -> t/U, :maybe -> t/Nilable, :enum -> U of
     t/Val, :and -> first member, scalars -> t/Str|Kw|Sym|AnyInteger|Bool|UUID,
     :vector/:sequential -> t/Vec, :set -> t/Set, :map-of -> t/Map. Named
     registry refs deref through the registry; unknown nodes -> t/Any.
   Output is fully-qualified `typed.clojure/*` symbols; requires only malli.core."
  (:require [malli.core :as m]
            [hive-spi.schema.registry :as reg]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def ^:private Any 'typed.clojure/Any)

(declare schema->type)

(defn- map->hmap
  "[:map ...] -> (t/HMap :mandatory {..} :optional {..}). Omits empty sections."
  [sch]
  (let [{req true opt false}
        (->> (m/children sch)
             (group-by (fn [[_ props _]] (not (:optional props)))))
        pair (fn [[k _ vs]] [k (schema->type vs)])
        reqm (into {} (map pair) req)
        optm (into {} (map pair) opt)]
    (concat (list 'typed.clojure/HMap)
            (when (seq reqm) [:mandatory reqm])
            (when (seq optm) [:optional optm]))))

(defn- schema->type*
  [sch]
  (case (m/type sch)
    (:any any?)                                                    Any
    (:string string?)                                             'typed.clojure/Str
    (:int integer? int?)                                          'typed.clojure/AnyInteger
    (:double double?)                                             'Double
    (:boolean boolean?)                                           'typed.clojure/Bool
    (:keyword keyword? :qualified-keyword qualified-keyword?
     simple-keyword?)                                             'typed.clojure/Kw
    (:symbol symbol? :qualified-symbol qualified-symbol?
     simple-symbol?)                                              'typed.clojure/Sym
    (:nil nil?)                                                   (list 'typed.clojure/Val nil)
    :uuid                                                         'typed.clojure/UUID
    :map                                                          (map->hmap sch)
    :map-of                                                       (let [[k v] (map schema->type (m/children sch))]
                                                                    (list 'typed.clojure/Map k v))
    (:vector :sequential)                                         (list 'typed.clojure/Vec (schema->type (first (m/children sch))))
    :set                                                          (list 'typed.clojure/Set (schema->type (first (m/children sch))))
    :tuple                                                        (list 'quote (mapv schema->type (m/children sch)))
    :enum                                                         (cons 'typed.clojure/U (map (fn [v] (list 'typed.clojure/Val v)) (m/children sch)))
    :maybe                                                        (list 'typed.clojure/Nilable (schema->type (first (m/children sch))))
    :or                                                           (let [ts (mapv schema->type (m/children sch))]
                                                                    (if (= 1 (count ts)) (first ts) (cons 'typed.clojure/U ts)))
    :and                                                          (schema->type (first (m/children sch)))
    :multi                                                        (let [ts (mapv (fn [c] (schema->type (nth c 2))) (m/children sch))]
                                                                    (if (= 1 (count ts)) (first ts) (cons 'typed.clojure/U ts)))
    :=                                                            (list 'typed.clojure/Val (first (m/children sch)))
    :fn                                                           Any
    (:ref :schema ::m/schema)                                    (schema->type (m/deref sch))
    (if (m/-ref-schema? sch)
      (schema->type (m/deref sch))
      Any)))

(defn schema->type
  "Typed Clojure validator-type (as data) for `?schema` — a registry key like
   :hive/result, or an inline malli form. Named registry refs resolve through
   the hive registry."
  [?schema]
  (schema->type* (reg/schema ?schema)))

(defn op-fn-type
  "Typed Clojure fn-type for an op handler: `[ArgT :-> ResultT]` (a vector —
   the checker's fn-type syntax). `result-schema` defaults to :hive/result."
  ([arg-schema] (op-fn-type arg-schema :hive/result))
  ([arg-schema result-schema]
   [(schema->type arg-schema) :-> (schema->type result-schema)]))

(defn ann-form
  "`(t/ann sym FnType)` form annotating handler var `sym` as an op over
   `arg-schema` returning `result-schema` (default :hive/result)."
  ([sym arg-schema] (ann-form sym arg-schema :hive/result))
  ([sym arg-schema result-schema]
   (list 'typed.clojure/ann sym (op-fn-type arg-schema result-schema))))

(defn defalias-form
  "`(t/defalias Name Type)` form aliasing `alias-sym` to the type of `?schema`."
  [alias-sym ?schema]
  (list 'typed.clojure/defalias alias-sym (schema->type ?schema)))

(defn =>-form
  "`(m/=> sym Schema)` form registering `sym`'s function schema (arg -> result)
   in malli's global function-schema registry — the malli-native counterpart of
   `ann-form`, consumable by m/instrument and typedclojure's :=> checking rule."
  ([sym arg-schema] (=>-form sym arg-schema :hive/result))
  ([sym arg-schema result-schema]
   (list 'malli.core/=> sym [:=> arg-schema result-schema])))

(defn registered-types
  "{schema-key validator-type} for every schema currently in the hive registry."
  []
  (into {} (map (fn [k] [k (schema->type k)])) (keys (reg/registered))))
