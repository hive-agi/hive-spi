(ns hive-spi.schema.derive
  "Single-source derivation over the hive core-op registry.

   One malli schema (key or form) yields every op-boundary artifact:
     :schema           compiled malli schema
     :input-schema     JSON Schema map — drop-in MCP tool :inputSchema
     :validate         x -> boolean
     :explain          x -> malli explanation | nil
     :coerce           x -> coerced x; THROWS ex-info {:error :schema/invalid}
     :type             Typed Clojure validator-type (as data)
     :coerce->result   x -> hive-dsl Result: {:ok coerced} | {:error :parse/schema-violation ..}

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

;; SPDX-License-Identifier: AGPL-3.0-or-later
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

(defn compile-op
  "Derive the single-source op bundle from one malli schema. See ns docstring."
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
    {:schema         s
     :input-schema   (input-schema s)
     :validate       validate
     :explain        (fn [x] (m/explain s x))
     :coerce         coerce
     :type           (typed/schema->type s)
     :coerce->result (fn [x]
                       (let [d (decode x)]
                         (if (validate d)
                           (r/ok d)
                           (r/err :parse/schema-violation
                                  {:explanation (me/humanize (m/explain s d))}))))}))
