(ns hive-spi.native.conformance-test
  "Observations over an unavailable port: every call answers the declared
   failure envelope, so expectations of it match and others do not."
  (:require [hive-schemas.test :as hst]
            [hive-spi.native.conformance :as conf]
            [hive-spi.native.ports :as ports]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema observe-records-a-valid-envelope
  hive-spi.native.conformance/observe
  {:in  [:cat ports/NativePort [:map [:op [:string {:max 6}]] [:expected [:enum :none]]]]
   :out conf/Observation
   :rel (fn [[_ _] out] (and (:valid-envelope? out) (not (:matches? out))))})

(deftest-golden unavailable-port-observations
  "test/golden/hive_spi/native/conformance.edn"
  (let [p   (ports/unavailable-port "craft" "gone")
        exp (ports/failure "gone" :native/unavailable)
        obs (conf/observations p [{:op "ops" :request {} :expected exp}
                                  {:op "ops" :request {} :expected {:ok true :value []}}])]
    {:obs         (mapv #(select-keys % [:valid-envelope? :matches?]) obs)
     :conformant? [(conf/conformant? obs) (conf/conformant? (take 1 obs)) (conf/conformant? [])]}))
