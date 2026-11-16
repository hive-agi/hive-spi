(ns hive-spi.native.schema-test
  "The validating constructors: identity on a conforming value, a typed
   ex-info otherwise."
  (:require [hive-schemas.test :as hst]
            [hive-spi.native.schema :as schema]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema envelope-ctor-is-identity-on-conformant
  hive-spi.native.schema/->envelope
  {:in  [:cat schema/Envelope]
   :out schema/Envelope
   :rel (fn [[e] out] (= e out))})

(hst/deftrifecta-from-schema valid?-agrees-with-the-envelope-schema
  hive-spi.native.schema/valid?
  {:in  [:cat [:= schema/Envelope] [:or schema/Envelope [:map [:ok :int]] :nil]]
   :out :boolean
   :rel (fn [[_ v] out] (= out (and (map? v) (boolean? (:ok v))
                                    (if (:ok v) (contains? v :value) (string? (:error v))))))})

(defn- refusal
  "Whether F accepts X. The refusal is an ex-info from the body or, when
   instrumented, from the contract: both are a refusal."
  [f x]
  (try (f x) :accepted (catch clojure.lang.ExceptionInfo _ :refused)))

(deftest-golden refusals
  "test/golden/hive_spi/native/schema.edn"
  {:envelope (mapv #(refusal schema/->envelope %) [{:ok true :value 1} {:ok false} {}])
   :spec     (mapv #(refusal schema/->library-spec %)
                   [{:native/library "craft" :native/abi :hive-cabi/v1 :native/artifacts {}}
                    {:native/library "craft"}])})
