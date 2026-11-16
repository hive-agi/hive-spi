(ns hive-spi.swarm.guards-test
  "Process-role guards. The role readers depend on the process environment,
   which has no injectable seam (`get-env-var` is private and reads
   System/getenv), so these tests pin the RELATIONS between the readers,
   which hold in any environment, and snapshot the configuration toggles,
   which are environment-free."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.guards :as guards]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [mode    guards/*enforcement-mode*
          enabled guards/*guard-enabled?*
          running (guards/coordinator-running?)]
      (try (f)
           (finally
             (guards/set-enforcement-mode! mode)
             (if enabled (guards/enable-guards!) (guards/disable-guards!))
             (if running
               (guards/mark-coordinator-running!)
               (guards/mark-coordinator-stopped!)))))))

(defn- config-view []
  (select-keys (guards/guard-status) [:enabled? :mode :coordinator-running?]))

(deftest-golden guard-configuration-golden
  "test/golden/hive_spi/swarm/guards/configuration.edn"
  [(do (guards/set-enforcement-mode! :warn) (guards/enable-guards!)
       (guards/mark-coordinator-stopped!) (config-view))
   (do (guards/set-enforcement-mode! :block) (config-view))
   (do (guards/set-enforcement-mode! :not-a-mode) (config-view))
   (do (guards/disable-guards!) (config-view))
   (do (guards/mark-coordinator-running!)
       [(guards/coordinator-running?) (config-view)])
   (guards/when-not-coordinator "golden" :ran)
   (do (guards/mark-coordinator-stopped!)
       (guards/when-not-coordinator "golden" :ran))])

(deftest the-role-readers-agree-in-any-environment-test
  (is (= (guards/coordinator?) (not (guards/child-ling?))))
  (is (= (guards/child-ling?) (= "child-ling" (guards/get-role))))
  (testing "a child's environment is one level deeper than this process"
    (let [env (guards/child-ling-env)]
      (is (= "child-ling" (get env "HIVE_MCP_ROLE")))
      (is (= (str (inc (guards/ling-depth))) (get env "HIVE_LING_DEPTH")))))
  (testing "the status map reports the same readings"
    (let [s (guards/guard-status)]
      (is (= (guards/child-ling?) (:child-ling? s)))
      (is (= (guards/get-role) (:role s)))
      (is (= (guards/ling-depth) (:ling-depth s))))))
