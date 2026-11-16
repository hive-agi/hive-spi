(ns hive-spi.wire.edn
  "Wire contract for EDN-valued op params.

   EDN atoms (keywords, symbols, logic vars, sets, tagged literals) have no JSON
   encoding, so on a JSON wire an EDN param can only travel as source text. In
   process, a realized EDN value is also valid.

   `edn-param` validates both forms but publishes only the string form."
  (:require [clojure.edn :as edn]
            [hive-dsl.result :as r]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def edn-media-type "application/edn")

(def EdnValue
  "A realized EDN value. Valid in process; never published to the wire."
  [:or [:vector :any] [:sequential :any] [:map-of :any :any] [:set :any]])

(defn wire-json-schema
  "JSON Schema for an EDN param. Always `{:type \"string\"}`.

   opts: :description, :example (a literal the caller can copy verbatim)."
  [{:keys [description example]}]
  (let [base (str (or description "EDN value.")
                  " Pass EDN SOURCE TEXT as a JSON string, not a JSON array/object.")]
    (cond-> {:type             "string"
             :contentMediaType edn-media-type
             :description      base}
      example (assoc :description (str base " Example: " (pr-str example))))))

(defn edn-param
  "Malli value-schema for a param whose value is EDN.

   Validates EDN source text OR a realized EDN value; publishes as a JSON string
   via malli's `:json-schema` property override."
  ([] (edn-param {}))
  ([opts]
   [:maybe {:json-schema (wire-json-schema opts)}
    [:or :string EdnValue]]))

(defn read-edn-param
  "Decode an EDN param at the op boundary. Total: never throws, never evals.

   `clojure.edn/read-string` (not `clojure.core/read-string`) — this parses
   caller-supplied text, and core's reader honours `*read-eval*`. `:readers {}`
   makes tagged literals fail closed."
  [x]
  (cond
    (string? x)
    (try
      (r/ok (edn/read-string {:readers {} :eof nil} x))
      (catch #?(:clj Exception :cljs :default :cljr Exception :lpy python/Exception :default :default) e
        (r/err :edn/unreadable {:input x :cause (ex-message e)})))

    (nil? x)  (r/ok nil)
    (coll? x) (r/ok x)

    :else
    (r/err :edn/unsupported
           {:got  (type x)
            :hint "EDN params travel as a string of EDN source text."})))

;; =============================================================================
;; Contracts
;; =============================================================================

(def WireOpts
  "Options `wire-json-schema` / `edn-param` read."
  [:maybe [:map {:closed false}
           [:description {:optional true} [:maybe :string]]
           [:example {:optional true} :any]]])

(def WireJsonSchema
  "The JSON Schema an EDN param publishes: always a string."
  [:map {:closed true}
   [:type [:= "string"]]
   [:contentMediaType [:= edn-media-type]]
   [:description [:string {:min 1}]]])

(def ParamResult
  "A hive-dsl Result: {:ok value} or {:error category ...}."
  [:or [:map [:ok :any]] [:map [:error [:enum :edn/unreadable :edn/unsupported]]]])

(m/=> wire-json-schema [:=> [:cat WireOpts] WireJsonSchema])
(m/=> edn-param [:function
                 [:=> [:cat] [:cat [:= :maybe] [:map [:json-schema WireJsonSchema]] :any]]
                 [:=> [:cat WireOpts] [:cat [:= :maybe] [:map [:json-schema WireJsonSchema]] :any]]])
(m/=> read-edn-param [:=> [:cat :any] ParamResult])
