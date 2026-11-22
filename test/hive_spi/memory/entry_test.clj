(ns hive-spi.memory.entry-test
  "The pure entry helpers every store shares, driven by their contracts:
   type tokens, embed text, projection and ordering by property, the
   timestamp and filter semantics by golden."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
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

;; =============================================================================
;; try-parse: the parser vector behind parse-instant
;; =============================================================================

(def ^:private TimestampInput
  "Every input class try-parse distinguishes: each accepted ISO form, blank,
   garbage, and non-strings."
  [:or
   e/Timestamp
   [:enum "2026-01-01T00:00:00+02:00" "2026-01-01T00:00:00+02:00[Europe/Paris]"
    "2026-01-01T10:00:00"]
   [:enum "" "   " nil 7 :kw]
   [:string {:min 1 :max 6}]
   [:enum "nope" "2026-13-45T99:99:99Z" "2026-01-01" "T10:00"]])

(hst/deftrifecta-from-schema try-parse-answers-ok-or-a-categorised-err
  hive-spi.memory.entry/try-parse
  {:in  [:cat TimestampInput]
   :out e/ParseResult
   :rel (fn [[s] out]
          (cond
            (or (not (string? s)) (clojure.string/blank? s))
            (= out {:error :timestamp/blank :input s})

            (:ok out)
            ;; parse-instant is exactly the ok side
            (= (:ok out) (e/parse-instant s))

            :else
            (and (= :timestamp/unparseable (:error out))
                 (= (mapv first e/instant-parsers) (:tried out))
                 (nil? (e/parse-instant s)))))
   :classify (fn [_ out] (or (:error out) :ok))
   :classify-domain [:enum :ok :timestamp/blank :timestamp/unparseable]})

(deftest try-parse-stops-at-the-first-parser-that-reads-the-input
  (let [calls (atom [])
        spy   (fn [nm f] [nm (fn [s] (swap! calls conj nm) (f s))])
        res   (e/try-parse [(spy :never #(throw (java.time.format.DateTimeParseException. "no" % 0)))
                            (spy :instant #(Instant/parse %))
                            (spy :unreached #(Instant/parse %))]
                           "2026-01-01T00:00:00Z")]
    (is (= (Instant/parse "2026-01-01T00:00:00Z") (:ok res)))
    (is (= [:never :instant] @calls))))

(deftest a-broken-parser-is-a-defect-not-an-unparseable-timestamp
  (testing "only DateTimeParseException means 'try the next form'"
    (is (thrown-with-msg? IllegalStateException #"parser bug"
                          (e/try-parse [[:broken (fn [_] (throw (IllegalStateException. "parser bug")))]]
                                       "2026-01-01T00:00:00Z")))))

(deftest matches?-treats-an-absent-opt-as-unconstrained
  (let [row {:id "a" :type :note :tags ["x"] :project-id "p1"}]
    (is (true? (e/matches? row {} now)))
    (is (true? (e/matches? row {:project-ids [] :tags [] :exclude-tags []} now))
        "empty collections constrain nothing, as before")
    (is (false? (e/matches? row {:project-id "p2"} now)))
    (is (false? (e/matches? (assoc row :expires "2026-01-01T00:00:00Z") {} now))
        "expired rows are filtered by default")
    (is (true? (e/matches? (assoc row :expires "2026-01-01T00:00:00Z")
                           {:include-expired? true} now)))))
