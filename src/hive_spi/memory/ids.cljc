(ns hive-spi.memory.ids
  "Identity helpers every memory store implementation shares: content
   hashing, entry-id generation, timestamps."
  (:require [clojure.string]
            [hive-spi.crypto.hash :as hash]
            [hive-spi.time.ports :as time]))

;; SPDX-License-Identifier: MIT

(defn content-hash
  "SHA-256 of CONTENT after normalising whitespace.

   Non-string content is `pr-str`ed first. Normalisation trims, collapses
   runs of spaces/tabs to one space, and collapses blank lines. The digest
   itself comes from the active `hive-spi.crypto.hash` IHasher."
  [content]
  (let [content-str (if (string? content) content (pr-str content))
        normalized (-> content-str
                       clojure.string/trim
                       (clojure.string/replace #"[ \t]+" " ")
                       (clojure.string/replace #"\n+" "\n"))]
    (hash/sha256 normalized)))

(defn- rand-hex8
  "Exactly eight lowercase hex digits of a random non-negative int, zero-padded
   on the left.

   `format` is a JVM-family primitive that ClojureScript's core does not carry,
   so the ClojureScript arm renders and pads the digits itself."
  []
  (let [n (rand-int 0x7fffffff)]
    #?(:clj  (format "%08x" n)
       :cljs (let [s (.toString n 16)]
               (str (subs "00000000" (min 8 (count s))) s)))))

(defn generate-id
  "A unique entry id of the form yyyyMMddHHmmss-<8 hex digits>.

   The wall-clock stamp comes from the active `hive-spi.time.ports` IClock."
  []
  (str (time/entry-stamp) "-" (rand-hex8)))

(defn iso-timestamp
  "The current instant as an ISO-8601 string, in the system zone where the
   active `hive-spi.time.ports` IClock knows one."
  []
  (time/iso-timestamp))
