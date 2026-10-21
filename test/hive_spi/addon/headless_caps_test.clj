(ns hive-spi.addon.headless-caps-test
  "Capabilities are derived by satisfaction, so a backend that implements a
   capability protocol reports it whether or not it declares it."
  (:require [hive-schemas.test :as hst]
            [hive-spi.addon.headless-caps :as caps]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(def ^:private Backend
  "A backend implementing a generated subset of the four capability
   protocols (hooks and budget combine; the rest stand alone)."
  [:fn {:gen/schema [:enum :none :hooks :checkpoint :subagents :budget :hooks+budget]
        :gen/fmap   (fn [k]
                      (case k
                        :none         (reify Object)
                        :hooks        (reify caps/IHookable
                                        (register-hooks! [_ _ _] {}) (active-hooks [_ _] nil))
                        :checkpoint   (reify caps/ICheckpointable
                                        (checkpoint! [_ _] {}) (rewind! [_ _ _] {}))
                        :subagents    (reify caps/ISubagentHost
                                        (register-subagents! [_ _ _] {}) (list-subagents [_ _] nil))
                        :budget       (reify caps/IBudgetGuardable
                                        (set-budget! [_ _ _] {}) (budget-status [_ _] nil))
                        :hooks+budget (reify
                                        caps/IHookable
                                        (register-hooks! [_ _ _] {}) (active-hooks [_ _] nil)
                                        caps/IBudgetGuardable
                                        (set-budget! [_ _ _] {}) (budget-status [_ _] nil))))}
   some?])

(hst/deftrifecta-from-schema provided-capabilities-follow-satisfaction
  hive-spi.addon.headless-caps/provided-capabilities
  {:in  [:cat Backend]
   :out [:set [:enum :cap/hooks :cap/checkpointing :cap/subagents :cap/budget]]
   :rel (fn [[b] out]
          (= out (set (for [[cap proto] caps/capability-protocols
                            :when (satisfies? proto b)]
                        cap))))})

(deftest-golden capability-table
  "test/golden/hive_spi/addon/headless_caps.edn"
  {:registry (vec (sort (keys caps/capability-protocols)))
   :plain    (vec (sort (caps/provided-capabilities {})))
   :both     (vec (sort (caps/provided-capabilities
                         (reify
                           caps/IHookable
                           (register-hooks! [_ _ _] {}) (active-hooks [_ _] nil)
                           caps/ICheckpointable
                           (checkpoint! [_ _] {}) (rewind! [_ _ _] {})))))})
