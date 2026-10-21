(ns hive-spi.memory.ports.protection-test
  "The decay-protection seam and the degraded search value: what a backend
   reads before it reaps, and what it answers without an embedder."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.memory.ports :as ports]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f] (try (f) (finally (ports/register-protection-provider! nil)))))

(hst/deftrifecta-from-schema protected-ids-are-the-providers-set
  hive-spi.memory.ports/register-protection-provider!
  {:in  [:cat ports/ProtectionProvider]
   :out ports/ProtectionProvider
   :rel (fn [[f] out] (and (identical? f out) (= (set (f)) (ports/protected-ids))))})

(hst/deftrifecta-from-schema degraded-result-carries-why
  hive-spi.memory.ports/degraded-search-result
  {:in  [:cat [:maybe [:map [:reason [:enum :no-embedder :down]]]]]
   :out [:= []]
   :rel (fn [[why] out] (= why (:hive-spi.memory/degraded (meta out))))})

(deftest-golden protection-degrades-to-empty
  "test/golden/hive_spi/memory/ports_protection.edn"
  {:none      (ports/protected-ids)
   :vector    (do (ports/register-protection-provider! (constantly ["a" "b" "a"]))
                  (ports/protected-ids))
   :throwing  (do (ports/register-protection-provider! #(throw (ex-info "kg down" {})))
                  (ports/protected-ids))
   :cleared   (do (ports/register-protection-provider! nil) (ports/protected-ids))})
