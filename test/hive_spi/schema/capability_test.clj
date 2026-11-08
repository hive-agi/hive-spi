(ns hive-spi.schema.capability-test
  "Capability-manifest projections over a duck-typed manifest: coverage
   counts, the catalog, a command's contract, the per-param merge and the
   flat wire view."
  (:require [hive-schemas.test :as hst]
            [hive-spi.schema.capability :as cap]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(def ^:private Small
  [:map {:gen/max 3}
   [:tool [:enum "t"]]
   [:all-commands [:set {:max 3} [:enum "a" "b" "c"]]]
   [:commands [:vector {:max 2}
               [:map [:command [:enum "a" "b" "d"]]
                [:schema {:optional true} [:enum :hive/ok :string [:map [:x :int]]]]]]]])

(hst/deftrifecta-from-schema coverage-counts-the-manifest
  hive-spi.schema.capability/coverage
  {:in  [:cat Small]
   :out cap/Coverage
   :rel (fn [[m] out]
          (and (= (:total out) (count (:all-commands m)))
               (= (:declared out) (count (set (map :command (:commands m)))))
               (<= (:schematized out) (:declared out))))})

(hst/deftrifecta-from-schema fold-aliases-renames-to-canonical
  hive-spi.schema.capability/fold-aliases
  {:in  [:cat [:= [:map [:qn {:aliases [:function]} :string]]]
         [:map [:function {:optional true} :string] [:qn {:optional true} :string]]]
   :out :map
   :rel (fn [[_ p] out]
          (and (not (contains? out :function))
               (= (:qn out) (or (:qn p) (:function p)))))})

(def ^:private manifest
  {:tool "demo"
   :all-commands #{"read" "write" "ping"}
   :commands [{:command "read" :summary "Read one"
               :schema [:map [:qn {:description "Qualified name" :aliases [:function]} :string]
                        [:limit {:optional true} :int]]}
              {:command "write" :summary "Write"
               :schema [:map [:qn {:description "The qn to write"} :string] [:body :string]]
               :effects #{:effect/write}}]})

(deftest-golden manifest-projections
  "test/golden/hive_spi/schema/capability.edn"
  {:coverage (cap/coverage manifest)
   :catalog  (cap/catalog manifest)
   :contract (cap/command-contract manifest "read")
   :ping     (select-keys (cap/command-contract manifest "ping") [:status :accepted])
   :missing  (cap/command-contract manifest "nope")
   :merge    (cap/merge-params manifest)
   :aliases  (cap/alias-map [:map [:qn {:aliases [:function :q]} :string]])
   :resolved (:required (cap/resolve-params [:map [:a :int] [:b {:optional true} :int]]))
   :flat     (-> (cap/flat-input-schema manifest {:properties-mode :envelope-only})
                 (update :properties #(into (sorted-map) %))
                 (dissoc :x-hive/commands))})
