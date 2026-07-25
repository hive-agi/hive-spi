(ns hive-spi.memory.ids
  "Identity helpers every memory store implementation shares: content
   hashing, entry-id generation, timestamps."
  (:require [clojure.string]))

;; SPDX-License-Identifier: MIT

(defn content-hash
  "SHA-256 of CONTENT after normalising whitespace.

   Non-string content is `pr-str`ed first. Normalisation trims, collapses
   runs of spaces/tabs to one space, and collapses blank lines."
  [content]
  (let [content-str (if (string? content) content (pr-str content))
        normalized (-> content-str
                       clojure.string/trim
                       (clojure.string/replace #"[ \t]+" " ")
                       (clojure.string/replace #"\n+" "\n"))
        md (java.security.MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest md (.getBytes normalized "UTF-8"))]
    (apply str (map #(format "%02x" %) hash-bytes))))

(defn generate-id
  "A unique entry id of the form yyyyMMddHHmmss-<8 hex digits>."
  []
  (let [ts (java.time.LocalDateTime/now)
        fmt (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
        random-hex (format "%08x" (rand-int Integer/MAX_VALUE))]
    (str (.format ts fmt) "-" random-hex)))

(defn iso-timestamp
  "The current instant as an ISO-8601 string in the system zone."
  []
  (str (java.time.ZonedDateTime/now (java.time.ZoneId/systemDefault))))
