(ns hive-spi.native.registry-test
  "The loader slot over a generated inert loader: install returns the
   loader, only its owner can unregister it."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.native.registry :as reg]
            [hive-test.golden :refer [deftest-golden]]
            [malli.generator]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f] (let [prior (reg/loader)]
            (try (f) (finally (some-> (reg/loader) reg/unregister-loader!)
                              (when prior (reg/register-loader! prior)))))))

(hst/deftrifecta-from-schema register-loader!-installs-it
  hive-spi.native.registry/register-loader!
  {:in  [:cat reg/NativeLoader]
   :out reg/NativeLoader
   :rel (fn [[l] out] (and (identical? l out) (identical? l (reg/loader))))})

(deftest-golden owner-only-unregister
  "test/golden/hive_spi/native/registry.edn"
  (let [[a b] (repeatedly 2 #(malli.generator/generate reg/NativeLoader))]
    (some-> (reg/loader) reg/unregister-loader!)
    {:empty     (some? (reg/loader))
     :installed (identical? a (reg/register-loader! a))
     :stranger  [(reg/unregister-loader! b) (identical? a (reg/loader))]
     :owner     [(reg/unregister-loader! a) (some? (reg/loader))]}))
