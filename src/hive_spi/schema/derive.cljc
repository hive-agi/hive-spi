(ns hive-spi.schema.derive
  "Single-source derivation over the hive core-op registry.

   One malli schema (key or form) yields every op-boundary artifact:
     :schema           compiled malli schema
     :input-schema     JSON Schema map — drop-in MCP tool :inputSchema
     :validate         x -> boolean
     :explain          x -> malli explanation | nil
     :coerce           x -> coerced x; THROWS ex-info {:error :schema/invalid}
     :coerce->result   x -> hive-dsl Result: {:ok coerced} | {:error :parse/schema-violation ..}

   plus one entry per registered PROJECTION (compiled-schema -> artifact),
   open for extension via register-projection! without touching compile-op:
     :type             Typed Clojure validator-type (as data)   [default]
     :generator        test.check generator                     [hive-spi.schema.gen]
     :cases            (fn [seed n] {label input}) reproducible [hive-spi.schema.gen]

   Coercion applies malli json + string transformers (JSON scalars -> EDN:
   \"30\"->30, \"kw\"->:kw). All artifacts resolve :hive/* named refs through
   the registry."
  (:require [malli.core :as m]
            [malli.json-schema :as mjs]
            [malli.transform :as mt]
            [malli.error :as me]
            [hive-spi.schema.registry :as reg]
            [hive-dsl.result :as r]
            [hive-spi.schema.typed :as typed]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def ^:private coercing-transformer
  (mt/transformer mt/json-transformer mt/string-transformer))

(defn input-schema
  "MCP-ready JSON Schema map for `?schema`. malli emits a $ref into
   :definitions for named/registered schemas; MCP tool :inputSchema needs the
   object at the ROOT, so the referenced root is inlined. Nested refs are
   retained under :definitions."
  [?schema]
  (let [js (mjs/transform (reg/schema ?schema))]
    (if-let [ref (:$ref js)]
      (let [root-name (subs ref (count "#/definitions/"))
            defs      (:definitions js)
            rest-defs (dissoc defs root-name)]
        (cond-> (get defs root-name)
          (seq rest-defs) (assoc :definitions rest-defs)))
      js)))

(defonce projections*
  (atom {:type typed/schema->type}))

(defn register-projection!
  "Extend every subsequent compile-op bundle: `f` (compiled-schema -> artifact)
   lands under `k`. Re-registering a key replaces its projection."
  [k f]
  (swap! projections* assoc k f)
  k)

(defn deregister-projection!
  "Remove projection `k` from subsequent compile-op bundles."
  [k]
  (swap! projections* dissoc k)
  k)

(defn projections
  "Current {k (compiled-schema -> artifact)} projection map."
  []
  @projections*)

(defn compile-op
  "Derive the single-source op bundle from one malli schema: the core artifacts
   below plus one entry per registered projection. See ns docstring."
  [?schema]
  (let [s        (reg/schema ?schema)
        validate (m/validator s)
        decode   (m/decoder s coercing-transformer)
        coerce   (fn [x]
                   (let [d (decode x)]
                     (if (validate d)
                       d
                       (throw (ex-info "Schema coercion failed"
                                       {:error :schema/invalid
                                        :explanation (me/humanize (m/explain s d))})))))]
    (reduce-kv
     (fn [bundle k project] (assoc bundle k (project s)))
     {:schema         s
      :input-schema   (input-schema s)
      :validate       validate
      :explain        (fn [x] (m/explain s x))
      :coerce         coerce
      :coerce->result (fn [x]
                        (let [d (decode x)]
                          (if (validate d)
                            (r/ok d)
                            (r/err :parse/schema-violation
                                   {:explanation (me/humanize (m/explain s d))}))))}
     @projections*)))