(ns hive-spi.schema.typed
  "Derive Typed Clojure type syntax (as data) from a hive-registry malli schema.

   `schema->type` gives the validator-type (values that pass m/validate);
   `schema->parser-type` gives the type of m/parse results, wrapped as
   `(t/U (t/Val :malli.core/invalid) ..)`. Mapping:
     :map -> t/HMap, :multi/:or -> t/U, :maybe -> t/Nilable, :enum -> U of
     t/Val, :and -> first member, :=> -> [Arg.. :-> Ret], :function -> t/IFn,
     :orn -> t/U (tagged '[(t/Val k) T] in parser mode), :* / :+ / :repeat ->
     t/Seqable (t/Vec in parser mode), scalars -> t/Str|Kw|Sym|AnyInteger|
     Bool|UUID, :vector -> t/Vec, :sequential -> t/SequentialColl, :set ->
     t/Set, :map-of -> t/Map. Named registry refs deref through the registry;
     unknown nodes -> t/Any. Output is fully-qualified `typed.clojure/*`
     symbols; requires only malli.core."
  (:require [malli.core :as m]
            [hive-spi.schema.registry :as reg]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def ^:private Any 'typed.clojure/Any)

(declare -walk)

(defn- map->hmap
  "[:map ...] -> (t/HMap :mandatory {..} :optional {..}). Omits empty sections."
  [sch mode]
  (let [{req true opt false}
        (->> (m/children sch)
             (group-by (fn [[_ props _]] (not (:optional props)))))
        pair (fn [[k _ vs]] [k (-walk vs mode)])
        reqm (into {} (map pair) req)
        optm (into {} (map pair) opt)]
    (concat (list 'typed.clojure/HMap)
            (when (seq reqm) [:mandatory reqm])
            (when (seq optm) [:optional optm]))))

(defn- arg-vec
  "Positional arg types from a :cat/:catn/:tuple param schema (args validate)."
  [param]
  (case (m/type param)
    :cat   (mapv #(-walk % :validator) (m/children param))
    :catn  (mapv #(-walk (nth % 2) :validator) (m/children param))
    :tuple (mapv #(-walk % :validator) (m/children param))
    [(-walk param :validator)]))

(defn- fn-arity
  "[:=> param ret] -> `[ArgT.. :-> RetT]`."
  [sch mode]
  (let [[param ret] (m/children sch)]
    (conj (arg-vec param) :-> (-walk ret mode))))

(defn- -dispatch
  [sch mode]
  (case (m/type sch)
    (:any any?)                                                    Any
    (:string string?)                                             'typed.clojure/Str
    (:int integer? int?)                                          'typed.clojure/AnyInteger
    (pos-int? neg-int? nat-int?)                                  'typed.clojure/AnyInteger
    (:double double?)                                             'Double
    (:float float?)                                               (list 'typed.clojure/U 'Double 'Float)
    (:> :< :>= :<= number? pos? neg? zero?
     rational? decimal? ratio?)                                   'typed.clojure/Num
    (:boolean boolean?)                                           'typed.clojure/Bool
    (:keyword keyword? :qualified-keyword qualified-keyword?
     simple-keyword?)                                             'typed.clojure/Kw
    (:symbol symbol? :qualified-symbol qualified-symbol?
     simple-symbol?)                                              'typed.clojure/Sym
    (:nil nil?)                                                   (list 'typed.clojure/Val nil)
    :uuid                                                         'typed.clojure/UUID
    :map                                                          (map->hmap sch mode)
    :map-of                                                       (let [[k v] (map #(-walk % mode) (m/children sch))]
                                                                    (list 'typed.clojure/Map k v))
    :vector                                                       (list 'typed.clojure/Vec (-walk (first (m/children sch)) mode))
    :sequential                                                   (list 'typed.clojure/SequentialColl (-walk (first (m/children sch)) mode))
    :set                                                          (list 'typed.clojure/Set (-walk (first (m/children sch)) mode))
    :tuple                                                        (list 'quote (mapv #(-walk % mode) (m/children sch)))
    :enum                                                         (cons 'typed.clojure/U (map (fn [v] (list 'typed.clojure/Val v)) (m/children sch)))
    :maybe                                                        (list 'typed.clojure/Nilable (-walk (first (m/children sch)) mode))
    :or                                                           (let [ts (mapv #(-walk % mode) (m/children sch))]
                                                                    (if (= 1 (count ts)) (first ts) (cons 'typed.clojure/U ts)))
    :and                                                          (-walk (first (m/children sch)) mode)
    :multi                                                        (let [ts (mapv (fn [c] (-walk (nth c 2) mode)) (m/children sch))]
                                                                    (if (= 1 (count ts)) (first ts) (cons 'typed.clojure/U ts)))
    :=                                                            (list 'typed.clojure/Val (first (m/children sch)))
    :fn                                                           Any
    :=>                                                           (fn-arity sch mode)
    :function                                                     (let [as (mapv #(-walk % mode) (m/children sch))]
                                                                    (if (= 1 (count as)) (first as) (cons 'typed.clojure/IFn as)))
    :orn                                                          (cons 'typed.clojure/U
                                                                        (mapv (fn [[k _ s]]
                                                                                (let [it (-walk s mode)]
                                                                                  (if (= mode :parser)
                                                                                    (list 'quote [(list 'typed.clojure/Val k) it])
                                                                                    it)))
                                                                              (m/children sch)))
    (:* :+ :repeat)                                               (let [it (-walk (first (m/children sch)) mode)]
                                                                    (list (if (= mode :parser) 'typed.clojure/Vec 'typed.clojure/Seqable) it))
    (:ref :schema ::m/schema)                                    (-walk (m/deref sch) mode)
    (if (m/-ref-schema? sch)
      (-walk (m/deref sch) mode)
      Any)))

(defn- -walk
  [?schema mode]
  (-dispatch (reg/schema ?schema) mode))

(defn schema->type
  "Typed Clojure validator-type (as data) for `?schema` — a registry key like
   :hive/result, or an inline malli form. Named registry refs resolve through
   the hive registry."
  [?schema]
  (-walk ?schema :validator))

(defn schema->parser-type
  "Typed Clojure type (as data) of `(m/parse ?schema x)` results:
   `(t/U (t/Val :malli.core/invalid) <parsed-type>)`. :orn branches are tagged
   `'[(t/Val k) T]`; :* / :+ / :repeat become t/Vec."
  [?schema]
  (list 'typed.clojure/U
        (list 'typed.clojure/Val :malli.core/invalid)
        (-walk ?schema :parser)))

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
