(ns hive-spi.memory.conformance.readiness-test
  "The conformance suite's data surface: fixture
   entries, and a single case run against the reference stub."
  (:require [clojure.test :refer [deftest is]]
            [hive-schemas.test :as hst]
            [hive-spi.memory.conformance :as conf]
            [hive-spi.memory.ids :as ids]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.stub :as stub]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema make-entry-hashes-its-content
  hive-spi.memory.conformance/make-entry
  {:in  [:cat [:map [:content [:string {:max 10}]]]]
   :out [:map [:content-hash :string]]
   :rel (fn [[o] out] (= (:content-hash out) (ids/content-hash (:content o))))})

(deftest-golden readiness-report
  "test/golden/hive_spi/memory/conformance_readiness.edn"
  {:skipped (conf/run-case stub/create-store {:skip {:reset-empties "not here"}} :reset-empties)
   :needs   (conf/run-case stub/create-store {} :disconnect-then-connect)})

(deftest a-single-case-runs-against-the-stub
  (is (= :ran (conf/run-case stub/create-store {} :add-get-roundtrip)))
  (is (= :ran (conf/run-case #(doto (stub/create-store) (ports/reset-store!))
                             {:connect-config {}} :disconnect-then-connect))))
