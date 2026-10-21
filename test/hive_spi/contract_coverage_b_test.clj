(ns hive-spi.contract-coverage-b-test
  "Contract-coverage gate over the hive-spi port directories outside
   swarm/, memory/ and schema/ (cov-fleet slug b). Every public fn under
   these paths carries an `m/=>` contract or a reasoned exemption below."
  (:require [hive-schemas.coverage :refer [deftest-contract-coverage]]))

;; SPDX-License-Identifier: MIT

(def paths
  ["src/hive_spi/addon"
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
   "src/hive_spi/time"
   "src/hive_spi/vector"
   "src/hive_spi/wire"
   "src/hive_spi/workflow"])

(def exempt
  {'hive-spi.workflow.engine/workflow-engine?
   "hive-spi.workflow.engine is declared a require-free pure-contract leaf
    (its ns form says so); a malli require would break that invariant. A
    total satisfies? predicate; pinned by a golden in engine-test."
   'hive-spi.workflow.engine/persistent-engine?
   "Same require-free leaf as workflow-engine?; a total satisfies? predicate
    pinned by the same golden."
   'hive-spi.kg.protocol/kg-store?
   "hive-spi.kg.protocol is declared a no-deps leaf (its ns docstring); a
    total satisfies? predicate."
   'hive-spi.kg.protocol/persistent-store?
   "No-deps kg.protocol leaf; a total satisfies? predicate."
   'hive-spi.kg.protocol/temporal-store?
   "No-deps kg.protocol leaf; a total satisfies? predicate."
   'hive-spi.kg.protocol/noop-store
   "No-deps kg.protocol leaf; returns the NoopKGStore record, exercised by
    hive-spi.kg.factory-test."
   'hive-spi.kg.conn-init/atom-conn-init
   "hive-spi.kg.conn-init is declared a no-deps leaf; returns a reified
    IConnInit (a Java object with no data shape)."})

(deftest-contract-coverage contract-coverage-b
  {:paths paths :exempt exempt})
