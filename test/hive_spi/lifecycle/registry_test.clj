(ns hive-spi.lifecycle.registry-test
  "The orchestrator runs shutdown hooks in ascending priority and restores a
   snapshot after a test or a hot reload, so ordering and round-trip are
   pinned here."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.lifecycle.ports :as ports]
            [hive-spi.lifecycle.registry :as reg]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [snap (reg/registry-snapshot)]
      (try (f) (finally (reg/restore-all! snap))))))

(hst/deftrifecta-from-schema registered-hooks-come-back-in-priority-order
  hive-spi.lifecycle.registry/register-shutdown!
  {:in  [:cat [:enum :a :b :c] reg/ShutdownHook]
   :out reg/ShutdownHook
   :rel (fn [[k hook] out]
          (let [prios (mapv ports/shutdown-priority (reg/registered-shutdown-hooks))]
            (and (identical? hook out)
                 (= prios (sort prios))
                 (identical? hook (get (:shutdown (reg/registry-snapshot)) k)))))})

;; Keys are keywords here: the contract admits any key, but a generated
;; ##NaN key is never = to itself, which would fail the round-trip on a value
;; the registry itself stores faithfully.
(def ^:private KeyedSnapshot
  [:map
   [:shutdown [:maybe [:map-of :keyword reg/ShutdownHook]]]
   [:sweeps [:maybe [:map-of :keyword reg/Sweeper]]]
   [:owners [:maybe [:map-of :keyword reg/ResourceOwner]]]])

(hst/deftrifecta-from-schema restore-all-round-trips-a-snapshot
  hive-spi.lifecycle.registry/restore-all!
  {:in  [:cat KeyedSnapshot]
   :out :nil
   :rel (fn [[snap] _]
          (= (into {} (map (fn [[k v]] [k (or v {})])) snap)
             (reg/registry-snapshot)))})

(defn- hook [prio] (reify ports/IShutdownHook
                     (shutdown-priority [_] prio)
                     (shutdown-name [_] (str "h" prio))
                     (shutdown! [_ _] nil)))

(deftest-golden shutdown-order-and-owner-lookup
  "test/golden/hive_spi/lifecycle/registry.edn"
  (do (reg/reset-all!)
      (reg/register-shutdown! :store (hook 300))
      (reg/register-shutdown! :svc (hook 10))
      (reg/register-shutdown! :client (hook 200))
      (reg/register-resource-owner! "ling-1" (reify ports/IResourceOwner
                                               (owner-id [_] "ling-1")
                                               (owned-resources [_] {:files 2})
                                               (release-all! [_] nil)))
      {:order   (mapv ports/shutdown-name (reg/registered-shutdown-hooks))
       :owner   (some-> (reg/get-resource-owner "ling-1") ports/owned-resources)
       :missing (reg/get-resource-owner "nobody")
       :after-unregister (do (reg/unregister-shutdown! :svc)
                             (mapv ports/shutdown-name (reg/registered-shutdown-hooks)))}))
