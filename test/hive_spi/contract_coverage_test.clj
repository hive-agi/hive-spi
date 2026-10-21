(ns hive-spi.contract-coverage-test
  "Contract-coverage gate (cov-fleet): every public fn under these paths
   carries an m/=> contract or a reasoned exemption. Extend :paths as more
   of hive-spi is contracted; a path listed here may never lose a contract."
  (:require [hive-schemas.coverage :refer [deftest-contract-coverage]]))

;; SPDX-License-Identifier: MIT

(deftest-contract-coverage root-and-swarm-contracts
  {:paths  ["src/hive_spi/provider.cljc"
            "src/hive_spi/slot.cljc"
            "src/hive_spi/notify.cljc"
            "src/hive_spi/vessel.cljc"
            "src/hive_spi/swarm"]
   :exempt {}})
