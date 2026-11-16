(ns hive-spi.memory.registry.slot-test
  "The memory-store registry over the reference stub store (the port's
   conformance-proven implementation): install, decorate, read back, reset."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.memory.decorate :as d]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.registry :as reg]
            [hive-spi.memory.stub :as stub]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [prior (reg/registered-stores)]
      (reg/reset-registry!) (d/reset-chain!)
      (try (f)
           (finally (reg/reset-registry!) (d/reset-chain!)
                    (doseq [[k s] prior] (reg/register-store! k s)))))))

(hst/deftrifecta-from-schema set-store!-installs-the-default
  hive-spi.memory.registry/set-store!
  {:in  [:cat ports/MemoryStore]
   :out ports/MemoryStore
   :rel (fn [[s] out] (and (identical? s out) (identical? s (reg/get-store))
                           (reg/store-set?)))})

(deftest-golden registry-lifecycle
  "test/golden/hive_spi/memory/registry_slot.edn"
  (let [s     (stub/create-store)
        empty [(reg/store-set?) (reg/active-store-healthy?) (reg/active-store-status)]
        _     (reg/register-store! :aux s)
        _     (reg/set-store! s)
        keys1 (vec (sort (keys (reg/registered-stores))))
        conn  (reg/connect-active-store! {})
        hlth  (reg/active-store-healthy?)
        stat  (select-keys (reg/active-store-status) [:backend :entry-count :capabilities])
        _     (reg/add-decorator! {:id :wrap :order 1 :decorate (fn [_ s] s)})
        unk   (try (reg/get-store :nope) (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (reg/remove-decorator! :wrap)
    (reg/unregister-store! :aux)
    (let [keys2 (vec (keys (reg/registered-stores)))]
      (reg/reset-active-store!)
      {:empty empty :keys keys1 :connect conn :healthy hlth :status stat
       :unknown unk :after-unregister keys2 :after-reset (reg/store-set?)
       :disconnected? (not (ports/connected? s))})))
