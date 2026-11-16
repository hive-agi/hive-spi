(ns hive-spi.memory.ids-test
  "Identity helpers: the content hash ignores whitespace layout and nothing
   else, ids carry the clock's stamp. Driven through injected clock and
   hasher ports, never the host defaults."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.memory.ids :as ids]
            [hive-spi.time.ports :as time]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(defrecord FrozenClock []
  time/IClock
  (clock-millis [_] 0)
  (clock-stamp [_] "20260101120000")
  (clock-iso [_] "2026-01-01T12:00:00Z"))

(use-fixtures :each
  (fn [f]
    (let [prior (when (time/clock-set?) (time/get-clock))]
      (time/set-clock! (->FrozenClock))
      (try (f) (finally (time/clear-clock!) (when prior (time/set-clock! prior)))))))

(hst/deftrifecta-from-schema content-hash-ignores-whitespace-layout
  hive-spi.memory.ids/content-hash
  {:in  [:cat [:string {:max 12}]]
   :out [:re #"^[0-9a-f]{64}$"]
   :rel (fn [[s] out]
          (= out (ids/content-hash (str "  " (clojure.string/replace s " " " \t ") "\n\n"))))})

(hst/deftrifecta-from-schema generate-id-carries-the-clock-stamp
  hive-spi.memory.ids/generate-id
  {:in  [:cat]
   :out ids/EntryId
   :rel (fn [_ out] (clojure.string/starts-with? out "20260101120000-"))})

(deftest-golden identity-renderings
  "test/golden/hive_spi/memory/ids.edn"
  {:hash-abc   (ids/content-hash "abc")
   :hash-map   (ids/content-hash {:a 1})
   :hash-ws    (= (ids/content-hash "a  b\n\n\nc") (ids/content-hash " a b\nc "))
   :iso        (ids/iso-timestamp)})

(deftest ids-are-distinct
  (is (= 50 (count (set (repeatedly 50 ids/generate-id))))))
