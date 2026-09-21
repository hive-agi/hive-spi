(ns hive-spi.memory.entry
  "Pure helpers over the memory-entry map every IMemoryStore shares: type
   tokens, timestamp parsing, expiry predicates, filtering, ordering and
   projection. No I/O. A store that filters or orders post-fetch uses these
   so its semantics equal the reference in-memory store's."
  (:require [clojure.string :as str]
            [hive-spi.memory.ids :as ids])
  (:import [java.time Duration Instant LocalDateTime OffsetDateTime ZoneId ZonedDateTime]))

;; SPDX-License-Identifier: MIT

(defn type-name
  "The type token of T as a string: keywords by name, anything else by str,
   nil for nil."
  [t]
  (cond (nil? t) nil
        (keyword? t) (name t)
        :else (str t)))

(defn type-keyword
  "The type token of T as a keyword, nil for nil."
  [t]
  (some-> t type-name keyword))

(defn parse-instant
  "Instant for an ISO-8601 timestamp string (Instant, offset, zoned or local
   forms all accepted); nil for blank or unparseable input."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (or (try (Instant/parse s) (catch Exception _ nil))
        (try (.toInstant (ZonedDateTime/parse s)) (catch Exception _ nil))
        (try (.toInstant (OffsetDateTime/parse s)) (catch Exception _ nil))
        (try (.toInstant (.atZone (LocalDateTime/parse s) (ZoneId/systemDefault)))
             (catch Exception _ nil)))))

(defn expired?
  "True when ENTRY carries an :expires timestamp earlier than NOW (default:
   the current instant). Entries without a parseable :expires never expire."
  ([entry] (expired? entry (Instant/now)))
  ([entry now]
   (boolean (some-> (:expires entry) parse-instant (.isBefore now)))))

(defn expires-within?
  "True when ENTRY's :expires lies after NOW and within DAYS days of it."
  [entry days now]
  (boolean
   (when-let [t (some-> (:expires entry) parse-instant)]
     (and (.isAfter t now)
          (.isBefore t (.plus now (Duration/ofDays (long days))))))))

(defn embed-text
  "The text handed to an embedder for CONTENT: strings as-is, other values
   printed; nil for nil or blank."
  [content]
  (cond (nil? content) nil
        (string? content) (when-not (str/blank? content) content)
        :else (pr-str content)))

(defn normalize
  "ENTRY with store-side defaults filled: :id generated when absent, :type as
   a keyword (default :note), :tags as a distinct vector of strings,
   :content-hash computed from :content when absent, :created and :updated
   stamped when absent."
  [entry]
  (let [now (ids/iso-timestamp)]
    (assoc entry
           :id (or (:id entry) (ids/generate-id))
           :type (or (type-keyword (:type entry)) :note)
           :tags (vec (distinct (map str (or (:tags entry) []))))
           :content-hash (or (:content-hash entry)
                             (ids/content-hash (or (:content entry) "")))
           :created (or (:created entry) now)
           :updated (or (:updated entry) now))))

(defn matches?
  "True when ENTRY satisfies the filter opts of query-entries: :type,
   :project-id, :project-ids (OR), :tags (AND), :exclude-tags and
   :include-expired? (default false), evaluated at NOW."
  [entry {:keys [type project-id project-ids tags exclude-tags include-expired?]} now]
  (let [etags (set (map str (:tags entry)))]
    (and (or (nil? type) (= (type-name type) (type-name (:type entry))))
         (or (nil? project-id) (= (str project-id) (:project-id entry)))
         (or (empty? project-ids)
             (contains? (set (map str project-ids)) (:project-id entry)))
         (every? #(contains? etags (str %)) tags)
         (not-any? #(contains? etags (str %)) exclude-tags)
         (or (boolean include-expired?) (not (expired? entry now))))))

(defn order-by
  "ENTRIES sorted by ORDER-BY, a [field direction] pair with direction :asc
   or :desc; unchanged when ORDER-BY is nil."
  [entries order-by]
  (if-let [[field direction] order-by]
    (let [cmp (if (= direction :desc) #(compare %2 %1) compare)]
      (vec (sort-by field cmp entries)))
    (vec entries)))

(defn project
  "ENTRY narrowed to OUTPUT-FIELDS (field names as strings or keywords);
   unchanged when OUTPUT-FIELDS is empty."
  [entry output-fields]
  (if (seq output-fields)
    (select-keys entry (map keyword output-fields))
    entry))

(defn query
  "Reference query-entries over an in-memory seq of ENTRIES: filter with
   `matches?`, order with `order-by`, cut to :limit (default 100), then
   project to :output-fields."
  [entries {:keys [limit output-fields] :or {limit 100} :as opts}]
  (let [now (Instant/now)]
    (->> entries
         (filter #(matches? % opts now))
         (#(order-by % (:order-by opts)))
         (take limit)
         (mapv #(project % output-fields)))))
