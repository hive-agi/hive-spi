(ns hive-spi.embeddings.ports-test
  "The embedding-provider slot: whatever is installed is what every vector
   writer embeds with, so a slot that drops or swaps the provider silently
   re-dimensions every stored vector."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.embeddings.ports :as ep]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [prior (ep/get-provider)]
      (try (f)
           (finally (if prior (ep/set-provider! prior) (ep/clear-provider!)))))))

(hst/deftrifecta-from-schema set-provider-installs-the-provider
  hive-spi.embeddings.ports/set-provider!
  {:in  [:cat ep/Provider]
   :out ep/Provider
   :rel (fn [[provider] out]
          (and (identical? provider out)
               (identical? provider (ep/get-provider))
               (true? (ep/provider-set?))
               (= (ep/embedding-dimension provider)
                  (count (ep/embed-text (ep/get-provider) "x")))))})

(deftest clearing-empties-the-slot
  (ep/clear-provider!)
  (is (nil? (ep/get-provider)) "no host default: an absent provider is nil")
  (is (false? (ep/provider-set?))))

(deftest a-non-provider-is-rejected
  (ep/clear-provider!)
  (is (thrown? AssertionError (ep/set-provider! {:not :a-provider})))
  (is (false? (ep/provider-set?))))
