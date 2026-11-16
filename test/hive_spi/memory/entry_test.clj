(ns hive-spi.memory.entry-test
  "The pure entry helpers every store shares, driven by their contracts:
   type tokens, embed text, projection and ordering by property, the
   timestamp and filter semantics by golden."
  (:require [clojure.test :refer [deftest is]]
            [hive-schemas.test :as hst]
            [hive-spi.memory.entry :as e]
            [hive-test.golden :refer [deftest-golden]])
  (:import [java.time Instant]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema type-name-renders-by-name
  hive-spi.memory.entry/type-name
  {:in  [:cat [:maybe [:or :keyword :string :int]]]
   :out [:maybe :string]
   :rel (fn [[t] out]
          (cond (nil? t)     (nil? out)
                (keyword? t) (= out (name t))
                :else        (= out (str t))))})

(hst/deftrifecta-from-schema embed-text-drops-blank
  hive-spi.memory.entry/embed-text
  {:in  [:cat [:maybe [:or :string [:map-of :keyword :int]]]]
   :out [:maybe :string]
   :rel (fn [[c] out]
          (cond (nil? c)                         (nil? out)
                (and (string? c) (clojure.string/blank? c)) (nil? out)
                (string? c)                      (= out c)
                :else                            (= out (pr-str c))))})

(hst/deftrifecta-from-schema project-keeps-only-the-named-fields
  hive-spi.memory.entry/project
  {:in  [:cat [:map-of [:enum :a :b :c :d] :int] [:vector [:enum :a :b "c"]]]
   :out :map
   :rel (fn [[m fields] out]
          (if (seq fields)
            (= out (select-keys m (map keyword fields)))
            (= out m)))})

(hst/deftrifecta-from-schema order-by-sorts-on-the-field
  hive-spi.memory.entry/order-by
  {:in  [:cat [:vector [:map [:n :int]]] [:maybe [:tuple [:= :n] [:enum :asc :desc]]]]
   :out [:vector :map]
   :rel (fn [[rows ob] out]
          (and (= (count rows) (count out))
               (case (second ob)
                 :desc (apply >= (map :n (cons {:n Long/MAX_VALUE} out)))
                 :asc  (apply <= (map :n (cons {:n Long/MIN_VALUE} out)))
                 (= out (vec rows)))))})

(def ^:private now (Instant/parse "2026-06-01T00:00:00Z"))

(def ^:private rows
  [{:id "a" :type :note :tags ["x" "y"] :project-id "p1" :created "2026-01-01T00:00:00Z"}
   {:id "b" :type "convention" :tags #{"y"} :project-id "p2" :created "2026-01-02T00:00:00Z"}
   {:id "c" :type :note :tags [] :project-id "p1" :created "2026-01-03T00:00:00Z"
    :expires "2026-05-01T00:00:00Z"}
   {:id "d" :type :note :tags ["x"] :project-id "p3" :created "2026-01-04T00:00:00Z"
    :expires "2026-06-03T00:00:00Z"}])

(deftest-golden entry-semantics
  "test/golden/hive_spi/memory/entry.edn"
  {:parse       (mapv #(some-> (e/parse-instant %) str)
                      ["2026-01-01T00:00:00Z" "2026-01-01T00:00:00+02:00" "" "nope" nil])
   :expired?    (mapv #(e/expired? % now) rows)
   :within-3d   (mapv #(e/expires-within? % 3 now) rows)
   :type-kw     (mapv e/type-keyword [:note "convention" nil])
   :by-type     (mapv :id (filter #(e/matches? % {:type "note"} now) rows))
   :by-tags     (mapv :id (filter #(e/matches? % {:tags ["y"]} now) rows))
   :by-projects (mapv :id (filter #(e/matches? % {:project-ids #{"p1" "p3"}
                                                  :include-expired? true} now) rows))
   :excluded    (mapv :id (filter #(e/matches? % {:exclude-tags ["x"]} now) rows))
   :query       (e/query rows {:order-by [:created :desc] :limit 2 :output-fields ["id"]})})

(deftest normalize-fills-the-store-defaults
  (let [n (e/normalize {:content "hi" :tags ["a" "a" :b] :type "decision"})]
    (is (string? (:id n)))
    (is (= :decision (:type n)))
    (is (= ["a" ":b"] (:tags n)))
    (is (string? (:content-hash n)))
    (is (= :note (:type (e/normalize {}))))))
