(ns hive-spi.kg-workflow-contract-coverage-test
  "Contract-coverage gate (cov-w1): every public fn of the KG storage SPI and
   the workflow-engine ports carries an m/=> contract. A path listed here may
   never lose a contract."
  (:require [hive-schemas.coverage :refer [deftest-contract-coverage]]))

;; SPDX-License-Identifier: MIT

(deftest-contract-coverage kg-and-workflow-engine-contracts
  {:paths  ["src/hive_spi/kg/protocol.clj"
            "src/hive_spi/kg/conn_init.clj"
            "src/hive_spi/workflow/engine.cljc"]
   :exempt {}})
