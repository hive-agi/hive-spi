(ns hive-spi.wire.edn-test
  "EDN params cross a JSON wire as source text. The decoder is total (never
   throws, never evals) and the published schema is always a string."
  (:require [clojure.test :refer [deftest is]]
            [hive-schemas.test :as hst]
            [hive-spi.wire.edn :as wire]
            [hive-test.golden :refer [deftest-golden]]
            [malli.core :as m]
            [clojure.edn]
            [clojure.string]))

;; SPDX-License-Identifier: MIT

(def ^:private EdnText
  "EDN source text built from a generated EDN value, so the decoder reaches
   its reading branch, not only its unreadable one."
  [:string {:gen/schema [:or [:vector :int] [:map-of :keyword :string] [:set :keyword]]
            :gen/fmap   pr-str}])

(hst/deftrifecta-from-schema read-edn-param-round-trips-printed-edn
  hive-spi.wire.edn/read-edn-param
  {:in  [:cat EdnText]
   :out wire/ParamResult
   :rel (fn [[text] out] (= {:ok (clojure.edn/read-string text)} out))})

(hst/deftrifecta-from-schema wire-json-schema-is-always-a-string
  hive-spi.wire.edn/wire-json-schema
  {:in  [:cat [:map [:description {:optional true} [:string {:min 1}]]
               [:example {:optional true} [:vector :int]]]]
   :out wire/WireJsonSchema
   :rel (fn [[opts] out]
          (and (= "string" (:type out))
               (or (nil? (:example opts))
                   (clojure.string/ends-with? (:description out)
                                              (pr-str (:example opts))))))})

(deftest edn-param-validates-both-forms
  (let [s (wire/edn-param {:description "rule"})]
    (is (m/validate s "{:a 1}"))
    (is (m/validate s {:a 1}))
    (is (m/validate s nil))
    (is (not (m/validate s 42)))))

(deftest-golden decoding-every-branch
  "test/golden/hive_spi/wire/edn.edn"
  (mapv (fn [x] (let [r (wire/read-edn-param x)]
                  (cond-> r (:got r) (update :got str))))
        ["{:a 1}" "[1 2 3]" "" "{:a" "#foo 1" "#=(+ 1 2)" nil [1 2] 42 :kw]))
