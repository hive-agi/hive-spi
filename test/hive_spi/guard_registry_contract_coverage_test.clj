(ns hive-spi.guard-registry-contract-coverage-test
  "Contract-coverage gate (cov2): every public fn of the guard leaf and of the
   memory and lifecycle registries carries an m/=> contract. A path listed
   here may never lose a contract."
  (:require [hive-schemas.coverage :refer [deftest-contract-coverage]]))

;; SPDX-License-Identifier: MIT

(deftest-contract-coverage guard-and-registry-contracts
  {:paths  ["src/hive_spi/guard"
            "src/hive_spi/memory/registry.clj"
            "src/hive_spi/lifecycle/registry.clj"]
   :exempt {}})
