(ns hive-spi.swarm.agent-types-test
  "The agent-type registry is the one place a type's depth, capabilities and
   tier are written down. These trifectas drive the contracted lookups over
   every way a caller names a type (keyword, string, symbol, nil, unknown)
   and relate each answer back to the registry value itself."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-schemas.test :as hst]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.agent-types :as at]))

;; SPDX-License-Identifier: MIT

(defn- kw [t] (when (or (keyword? t) (string? t) (symbol? t)) (keyword t)))

(def ^:private Capability
  [:enum :spawn :read :write :kill :eval :dag :commit :delegate :nope "read" "spawn"])

(hst/deftrifecta-from-schema valid-type-contract
  hive-spi.swarm.agent-types/valid-type?
  {:in          at/TypeRef
   :out         :boolean
   :rel         (fn [t out]
                  (= out (boolean (and (or (keyword? t) (string? t))
                                       (contains? at/registry (keyword t))))))
   :golden-path "test/golden/hive_spi/swarm/agent_types/valid-type.edn"
   :num-tests   200})

(hst/deftrifecta-from-schema type-depth-contract
  hive-spi.swarm.agent-types/type-depth
  {:in          at/TypeRef
   :out         :int
   :rel         (fn [t out] (= out (get-in at/registry [(kw t) :depth] 1)))
   :golden-path "test/golden/hive_spi/swarm/agent_types/type-depth.edn"
   :num-tests   200})

(hst/deftrifecta-from-schema default-model-tier-contract
  hive-spi.swarm.agent-types/default-model-tier
  {:in          at/TypeRef
   :out         at/ModelTier
   :rel         (fn [t out] (= out (get-in at/registry [(kw t) :model-tier] :standard)))
   :golden-path "test/golden/hive_spi/swarm/agent_types/default-model-tier.edn"
   :num-tests   200})

(hst/deftrifecta-from-schema has-capability-contract
  hive-spi.swarm.agent-types/has-capability?
  {:in          [:cat at/TypeRef Capability]
   :out         :boolean
   :rel         (fn [[t c] out]
                  (= out (contains? (get-in at/registry [(kw t) :capabilities] #{})
                                    (keyword c))))
   :golden-path "test/golden/hive_spi/swarm/agent_types/has-capability.edn"
   :num-tests   200})

(deftest-golden agent-type-views-golden
  "test/golden/hive_spi/swarm/agent_types/views.edn"
  (into (sorted-map)
        (for [t [:coordinator :ling "ling" :ghost]]
          [(str t)
           {:spawnable?   (at/spawnable? t)
            :slot-limit   (at/slot-limit t)
            :chain?       (at/can-chain-tools? t)
            :can-spawn?   (at/has-permission? t :can-spawn?)
            :describe     (at/describe t)
            :claude-mode? (at/valid-spawn-mode? t :claude)}])))

(deftest depth-resolves-to-a-type-test
  (is (= :coordinator (at/depth->agent-type 0)))
  (testing "every other depth is a ling"
    (is (= [:ling :ling :ling] (mapv at/depth->agent-type [1 7 nil])))))

(deftest the-mcp-enum-is-the-mcp-visible-types-test
  (is (= ["ling"] (at/mcp-enum))))
