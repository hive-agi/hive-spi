(ns hive-spi.workflow.engine-contract-test
  "Contract facets for the workflow-engine capability predicates: each agrees
   with `satisfies?` for every generated value (m/=> [:=> [:cat :any] :boolean])."
  (:require [hive-schemas.test :as hst]
            [hive-spi.workflow.engine :as e]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema workflow-engine-predicate-contract
  hive-spi.workflow.engine/workflow-engine?
  {:in        :any
   :out       :boolean
   :rel       (fn [x out] (= out (satisfies? e/IWorkflowEngine x)))
   :num-tests 100})

(hst/deftrifecta-from-schema persistent-engine-predicate-contract
  hive-spi.workflow.engine/persistent-engine?
  {:in        :any
   :out       :boolean
   :rel       (fn [x out] (= out (satisfies? e/IWorkflowPersistence x)))
   :num-tests 100})
