(ns hive-spi.crypto.ports-test
  "The signer slot: an installed ISigner is what every detached signature in
   the ecosystem is produced with, and an empty slot has no host default."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.crypto.ports :as cp]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [prior (cp/get-signer)]
      (try (f)
           (finally (if prior (cp/set-signer! prior) (cp/clear-signer!)))))))

(hst/deftrifecta-from-schema set-signer-installs-the-signer
  hive-spi.crypto.ports/set-signer!
  {:in  [:cat cp/Signer]
   :out cp/Signer
   :rel (fn [[signer] out]
          (and (identical? signer out)
               (identical? signer (cp/get-signer))
               (true? (cp/signer-set?))
               (= (cp/signer-algorithm signer) (cp/signer-algorithm (cp/get-signer)))))})

(deftest clearing-empties-the-slot
  (cp/clear-signer!)
  (is (nil? (cp/get-signer)))
  (is (false? (cp/signer-set?))))

(deftest a-non-signer-is-rejected
  (cp/clear-signer!)
  (is (thrown? AssertionError (cp/set-signer! {:not :a-signer})))
  (is (false? (cp/signer-set?))))
