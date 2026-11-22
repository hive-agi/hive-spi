(ns hive-spi.memory.entry
  "Pure helpers over the memory-entry map every IMemoryStore shares: type
   tokens, timestamp parsing, expiry predicates, filtering, ordering and
   projection. No I/O. A store that filters or orders post-fetch uses these
   so its semantics equal the reference in-memory store's."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-spi.memory.ids :as ids]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc])
  (:import [java.time Duration Instant LocalDateTime OffsetDateTime ZoneId ZonedDateTime]
           [java.time.format DateTimeParseException]))

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

(def instant-parsers
  "The ISO-8601 forms a timestamp may take, tried in order: [name parse-fn],
   each parse-fn String -> Instant, throwing DateTimeParseException on a form
   it does not read. A new accepted form is a new row here."
  [[:instant Instant/parse]
   [:zoned   #(.toInstant (ZonedDateTime/parse %))]
   [:offset  #(.toInstant (OffsetDateTime/parse %))]
   [:local   #(.toInstant (.atZone (LocalDateTime/parse %) (ZoneId/systemDefault)))]])

(defn try-parse
  "Run S through PARSERS (default `instant-parsers`) in order and answer the
   first that reads it as (r/ok instant). When none does, answer
   (r/err :timestamp/unparseable {:input s :tried [parser-name ...]});
   blank or non-string input is (r/err :timestamp/blank {:input s}).

   Only a DateTimeParseException means 'not this form, try the next'. Any
   other throwable is a defect in a parser and propagates, so a broken
   parser can no longer read as an unparseable timestamp."
  ([s] (try-parse instant-parsers s))
  ([parsers s]
   (if (or (not (string? s)) (str/blank? s))
     (r/err :timestamp/blank {:input s})
     (reduce (fn [res [parser-name parse]]
               (try
                 (reduced (r/ok (parse s)))
                 (catch DateTimeParseException _
                   (update res :tried conj parser-name))))
             (r/err :timestamp/unparseable {:input s :tried []})
             parsers))))

(defn parse-instant
  "Instant for an ISO-8601 timestamp string (Instant, offset, zoned or local
   forms all accepted); nil for blank or unparseable input. The nil-on-miss
   reading of `try-parse`, for callers that only branch on presence."
  [s]
  (:ok (try-parse s)))

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

(def ^:private filter-clauses
  "The filter opts of query-entries, one row each: [opt-key active? holds?].
   A clause whose opt is absent (active? false) does not constrain. holds? is
   (fn [entry opt-value ctx]) with ctx {:tags #{str} :now Instant}. Adding a
   filter opt is adding a row; `matches?` never branches on which opt it is."
  [[:type             some?   (fn [e t _] (= (type-name t) (type-name (:type e))))]
   [:project-id       some?   (fn [e p _] (= (str p) (:project-id e)))]
   [:project-ids      seq     (fn [e ps _] (contains? (set (map str ps)) (:project-id e)))]
   [:tags             seq     (fn [_ ts {:keys [tags]}] (every? #(contains? tags (str %)) ts))]
   [:exclude-tags     seq     (fn [_ ts {:keys [tags]}] (not-any? #(contains? tags (str %)) ts))]
   ;; expired entries are filtered unless the caller opts in
   [:include-expired? not     (fn [e _ {:keys [now]}] (not (expired? e now)))]])

(defn matches?
  "True when ENTRY satisfies the filter opts of query-entries: :type,
   :project-id, :project-ids (OR), :tags (AND), :exclude-tags and
   :include-expired? (default false), evaluated at NOW."
  [entry opts now]
  (let [ctx {:tags (set (map str (:tags entry))) :now now}]
    (every? (fn [[k active? holds?]]
              (let [v (get opts k)]
                (or (not (active? v)) (boolean (holds? entry v ctx)))))
            filter-clauses)))

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

;;; ============================================================================
;;; Contracts
;;; ============================================================================

(def Timestamp
  "An ISO-8601 instant string, as stores stamp :created / :updated / :expires."
  [:re {:gen/fmap (fn [n] (str (Instant/ofEpochSecond n)))
        :gen/schema [:int {:min 0 :max 4102444800}]}
   #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*$"])

(def InstantValue
  "A java.time.Instant."
  [:fn {:error/message "must be a java.time.Instant"
        :gen/schema    [:int {:min 0 :max 4102444800}]
        :gen/fmap      (fn [n] (Instant/ofEpochSecond n))}
   #(instance? Instant %)])

(def TypeToken
  "An entry :type as callers hand it: a keyword, a string, or anything
   `str` renders."
  [:or :keyword :string :symbol])

(def Entry
  "A memory-entry map as the helpers read it (open). Tags are any
   collection (set semantics, compared by `str`); :project-id is compared
   as given; :content is anything `embed-text` renders."
  [:map
   [:id {:optional true} [:maybe :string]]
   [:type {:optional true} [:maybe TypeToken]]
   [:content {:optional true} :any]
   [:tags {:optional true} [:maybe [:or [:sequential :any] [:set :any]]]]
   [:project-id {:optional true} [:maybe :string]]
   [:created {:optional true} [:maybe Timestamp]]
   [:updated {:optional true} [:maybe Timestamp]]
   [:expires {:optional true} [:maybe Timestamp]]])

(def NormalizedEntry
  "What `normalize` answers: the store-side defaults filled."
  [:map
   [:id :string]
   [:type :keyword]
   [:tags [:vector :string]]
   [:content-hash :string]
   [:created :any]
   [:updated :any]])

(def OrderBy
  "A [field direction] pair; any direction but :desc sorts ascending."
  [:maybe [:tuple :keyword :keyword]])

(def QueryOpts
  "The filter opts of query-entries. Ids, tags and project ids are compared
   through `str`, so any value is accepted; collections may be sets."
  [:map
   [:type {:optional true} [:maybe TypeToken]]
   [:project-id {:optional true} :any]
   [:project-ids {:optional true} [:maybe [:or [:sequential :any] [:set :any]]]]
   [:tags {:optional true} [:maybe [:or [:sequential :any] [:set :any]]]]
   [:exclude-tags {:optional true} [:maybe [:or [:sequential :any] [:set :any]]]]
   [:include-expired? {:optional true} :any]
   [:limit {:optional true} nat-int?]
   [:order-by {:optional true} OrderBy]
   [:output-fields {:optional true} [:maybe [:sequential [:or :keyword :string]]]]])

;; nil for nil; keywords by name, anything else by `str`.
(m/=> type-name [:=> [:cat :any] [:maybe :string]])
(tc/ann-op type-name)
(m/=> type-keyword [:=> [:cat :any] [:maybe :keyword]])
(tc/ann-op type-keyword)
;; Total: anything that is not a parseable timestamp string answers nil.
(m/=> parse-instant [:=> [:cat :any] [:maybe InstantValue]])
(tc/ann-op parse-instant)

(def Parser
  "One row of `instant-parsers`: a name and a String -> Instant fn."
  [:tuple :keyword ifn?])

(def ParseResult
  "What `try-parse` answers."
  [:or
   [:map [:ok InstantValue]]
   [:map [:error [:= :timestamp/blank]] [:input :any]]
   [:map [:error [:= :timestamp/unparseable]] [:input :string] [:tried [:vector :keyword]]]])

(m/=> try-parse [:function
                 [:=> [:cat :any] ParseResult]
                 [:=> [:cat [:sequential Parser] :any] ParseResult]])
(tc/ann-op try-parse)
(m/=> expired? [:function
                [:=> [:cat [:maybe :map]] :boolean]
                [:=> [:cat [:maybe :map] InstantValue] :boolean]])
(tc/ann-op expired?)
(m/=> expires-within? [:=> [:cat [:maybe :map] :int InstantValue] :boolean])
(tc/ann-op expires-within?)
(m/=> embed-text [:=> [:cat :any] [:maybe :string]])
(tc/ann-op embed-text)
(m/=> normalize [:=> [:cat [:maybe Entry]] NormalizedEntry])
(tc/ann-op normalize)
(m/=> matches? [:=> [:cat [:maybe Entry] [:maybe QueryOpts] InstantValue] :boolean])
(tc/ann-op matches?)
;; ENTRIES may be nil (a store's (vals {})): every seq fn here accepts it.
(m/=> order-by [:=> [:cat [:maybe [:sequential :map]] OrderBy] [:vector :map]])
(tc/ann-op order-by)
(m/=> project [:=> [:cat :map [:maybe [:sequential [:or :keyword :string]]]] :map])
(tc/ann-op project)
(m/=> query [:=> [:cat [:maybe [:sequential Entry]] [:maybe QueryOpts]] [:vector :map]])
(tc/ann-op query)
