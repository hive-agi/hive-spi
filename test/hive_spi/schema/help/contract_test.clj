(ns hive-spi.schema.help.contract-test
  "The help-table projection, total by contract: rows sort required-first,
   unresolvable refs answer nil."
  (:require [hive-schemas.test :as hst]
            [hive-spi.schema.help :as help]
            [hive-spi.schema.registry :as reg]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema help-rows-sort-required-first
  hive-spi.schema.help/schema->help-rows
  {:in  [:cat reg/AnyRef]
   :out [:maybe [:vector help/HelpRow]]
   :rel (fn [[_ ] rows]
          (or (nil? rows)
              (= (map :required? rows) (sort-by #(if % 0 1) (map :required? rows)))))})

(deftest-golden help-table
  "test/golden/hive_spi/schema/help_contract.edn"
  {:rows (help/schema->help-rows
          [:map
           [:qn {:description "Qualified name" :aliases [:function]} :string]
           [:limit {:optional true :default 50} [:maybe :int]]
           [:mode {:optional true} [:enum "fast" "full"]]])
   :scalar (help/schema->help-rows :string)
   :unresolved (help/schema->help-rows :covf/nope)})
