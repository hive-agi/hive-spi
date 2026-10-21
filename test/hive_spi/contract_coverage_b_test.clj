(ns hive-spi.contract-coverage-b-test
  "Contract-coverage gate over the hive-spi port directories outside
   swarm/, memory/ and schema/ (cov-fleet slug b). Every public fn under
   these paths carries an `m/=>` contract or a reasoned exemption below."
  (:require [hive-schemas.coverage :refer [deftest-contract-coverage]]))

;; SPDX-License-Identifier: MIT

(def paths
  ["src/hive_spi/crypto"
   "src/hive_spi/embeddings"
   "src/hive_spi/log"
   "src/hive_spi/time"
   "src/hive_spi/vector"])

(def exempt
  {})

(deftest-contract-coverage contract-coverage-b
  {:paths paths :exempt exempt})
