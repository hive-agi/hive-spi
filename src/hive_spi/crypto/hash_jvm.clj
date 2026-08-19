(ns hive-spi.crypto.hash-jvm
  "`java.security.MessageDigest` implementation of the IHasher port.

   Loadable only on a host that carries java.security; the port resolves this
   namespace softly, so a runtime without it degrades to \"no hasher\" rather
   than to a load failure."
  (:require [hive-spi.crypto.hash :as hash])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

;; SPDX-License-Identifier: MIT

(def ^:private algorithm->jvm-name
  {:sha256 "SHA-256"
   :sha1   "SHA-1"
   :md5    "MD5"})

(defn- hex
  [digest-bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) digest-bytes)))

(defrecord MessageDigestHasher []
  hash/IHasher
  (digest-hex [_ algorithm text]
    (let [jvm-name (or (algorithm->jvm-name algorithm)
                       (throw (ex-info "Unsupported digest algorithm"
                                       {:error :hash/unsupported-algorithm
                                        :algorithm algorithm
                                        :supported (set (keys algorithm->jvm-name))})))]
      (hex (.digest (MessageDigest/getInstance jvm-name)
                    (.getBytes ^String text StandardCharsets/UTF_8)))))
  (hasher-algorithms [_] (set (keys algorithm->jvm-name))))

(defn default-hasher
  "A MessageDigest-backed IHasher."
  []
  (->MessageDigestHasher))

(defn install!
  "Install the MessageDigest hasher as the explicitly active one. Returns it."
  []
  (hash/set-hasher! (default-hasher)))
