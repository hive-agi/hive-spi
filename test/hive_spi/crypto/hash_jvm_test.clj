(ns hive-spi.crypto.hash-jvm-test
  "The MessageDigest hasher is the JVM host default for every content hash,
   so its digests are pinned against known answers per algorithm."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.crypto.hash :as hash]
            [hive-spi.crypto.hash-jvm :as hash-jvm]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [installed (when (hash/hasher-set?) (hash/get-hasher))]
      (try (f)
           (finally (hash/clear-hasher!)
                    (when installed (hash/set-hasher! installed)))))))

(hst/deftrifecta-from-schema default-hasher-provides-sha256
  hive-spi.crypto.hash-jvm/default-hasher
  {:in  [:cat]
   :out hash/Hasher
   :rel (fn [_ h]
          (and (= #{:sha256 :sha1 :md5} (hash/hasher-algorithms h))
               (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                  (hash/digest-hex h :sha256 "abc"))))})

(hst/deftrifecta-from-schema install-makes-the-jvm-hasher-active
  hive-spi.crypto.hash-jvm/install!
  {:in  [:cat]
   :out hash/Hasher
   :rel (fn [_ h] (and (hash/hasher-set?) (identical? h (hash/get-hasher))))})

(deftest-golden every-algorithm-over-known-inputs
  "test/golden/hive_spi/crypto/hash_jvm.edn"
  (let [h (hash-jvm/default-hasher)]
    (into (sorted-map)
          (for [alg [:md5 :sha1 :sha256] text ["" "abc" "é"]]
            [(str (name alg) " " (pr-str text)) (hash/digest-hex h alg text)]))))
