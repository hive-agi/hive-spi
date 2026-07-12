(ns wire-edn-probe
  "Dev probe for hive-spi.wire.edn. :dev alias only — not on the published classpath.

   `unencodable-params` is the invariant worth promoting to a test: no op param may
   publish a JSON container whose items are untyped, because an untyped item is
   where EDN atoms hide, and they have no JSON encoding."
  (:require [hive-spi.wire.edn :as w]
            [hive-spi.schema.derive :as der]
            [malli.core :as m]
            [malli.json-schema :as mjs]))

(defn untyped-container?
  "True when a JSON Schema node is an array with untyped items, or an object with
   untyped additional properties. `{:type \"array\" :items {}}` accepts anything —
   including values JSON cannot carry."
  [node]
  (and (map? node)
       (or (and (= "array" (:type node))  (= {} (:items node)))
           (and (= "object" (:type node)) (= {} (:additionalProperties node))))))

(defn unencodable-params
  "Params whose published JSON Schema contains an untyped container.
   An array of declared strings is fine; an array of :any is not."
  [?schema]
  (into {}
        (for [[k v] (:properties (der/input-schema ?schema))
              :let  [bad (filter untyped-container? (tree-seq coll? seq v))]
              :when (seq bad)]
          [k (vec bad)])))

(defn probe
  "Round-trip one edn-param: what it publishes, what it validates, how it decodes."
  [opts]
  (let [s (w/edn-param opts)]
    {:published (mjs/transform s)
     :validates {:string (m/validate s "[:find ?e]")
                 :vector (m/validate s [:find '?e])
                 :map    (m/validate s {:find ['?e]})
                 :nil    (m/validate s nil)}
     :decodes   {:string    (w/read-edn-param "[:find ?qn :where [?e :a ?v]]")
                 :vector    (w/read-edn-param [:find '?e])
                 :malformed (w/read-edn-param "[:find ?qn")}}))

(comment

  (probe {:description "Read-only query." :example "[:find ?e :where [?e :a ?v]]"})
  ;; :published => {:type "string" :contentMediaType "application/edn" ...}

  ;; A param declared [:or :string [:vector :any] [:map-of :any :any]] publishes
  ;; oneOf[string, array, object] — the array branch is the trap.
  (unencodable-params [:map [:q {:optional true} [:maybe [:or :string [:vector :any]]]]])
  ;; => {:q [{:type "array" :items {}}]}

  (unencodable-params [:map [:q {:optional true} (w/edn-param {})]])
  ;; => {}

  ;; An array of declared strings is NOT a defect — it round-trips as JSON.
  (unencodable-params [:map [:paths {:optional true} [:maybe [:vector :string]]]])
  ;; => {}

  )
