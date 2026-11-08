(ns hive-spi.contract-coverage-test
  "Contract-coverage gate (cov-fleet): every public fn under these paths
   carries an m/=> contract or a reasoned exemption. Extend :paths as more
   of hive-spi is contracted; a path listed here may never lose a contract."
  (:require [hive-schemas.coverage :refer [deftest-contract-coverage]]))

;; SPDX-License-Identifier: MIT

(def paths
  ["src/hive_spi/provider.cljc"
   "src/hive_spi/slot.cljc"
   "src/hive_spi/notify.cljc"
   "src/hive_spi/vessel.cljc"
   "src/hive_spi/kanban.cljc"
   "src/hive_spi/kanban"
   "src/hive_spi/catchup"
   "src/hive_spi/native"
   "src/hive_spi/swarm"
   "src/hive_spi/addon"
   "src/hive_spi/cider"
   "src/hive_spi/crypto"
   "src/hive_spi/diag"
   "src/hive_spi/editor"
   "src/hive_spi/embeddings"
   "src/hive_spi/guard"
   "src/hive_spi/ingest"
   "src/hive_spi/kg"
   "src/hive_spi/lifecycle"
   "src/hive_spi/log"
   "src/hive_spi/memory"
   ;; schema/ minus typed_ann.clj: it holds macros only (no defn) and needs
   ;; typed.clojure, which lives on the :typed alias, not on :test.
   "src/hive_spi/schema/capability.cljc"
   "src/hive_spi/schema/derive.cljc"
   "src/hive_spi/schema/gen.cljc"
   "src/hive_spi/schema/help.cljc"
   "src/hive_spi/schema/registry.cljc"
   "src/hive_spi/schema/typed.cljc"
   "src/hive_spi/time"
   "src/hive_spi/vector"
   "src/hive_spi/wire"
   "src/hive_spi/workflow"])

;; Empty: kg.protocol, kg.conn-init and workflow.engine now carry malli and
;; their own m/=> contracts (cov-w1), so nothing under :paths is exempt.
(def exempt {})

(deftest-contract-coverage hive-spi-contracts
  {:paths paths :exempt exempt})
