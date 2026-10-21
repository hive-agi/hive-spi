(ns hive-spi.contract-coverage-b-test
  "Contract-coverage gate over the hive-spi port directories outside
   swarm/, memory/ and schema/ (cov-fleet slug b). Every public fn under
   these paths carries an `m/=>` contract or a reasoned exemption below."
  (:require [hive-schemas.coverage :refer [deftest-contract-coverage]]))

;; SPDX-License-Identifier: MIT

(def paths
  ["src/hive_spi/time"])

(def exempt
  {})

(deftest-contract-coverage contract-coverage-b
  {:paths paths :exempt exempt})
