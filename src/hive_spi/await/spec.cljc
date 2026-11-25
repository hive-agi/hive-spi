(ns hive-spi.await.spec
  "The pure rules every await source and the host apply in the same shape:
   keywordising a JSON spec, closing it against the source's key tree
   (nested maps included), the refusal ex-info, the operator allowlist
   check, the comparison operators a predicate may name, the timing checks,
   and the closed-schema check a parsed-spec schema must pass.

   No fn here does I/O, reads config or keeps state. A source refuses
   through these fns, so a refusal from an addon reads like one from the
   host."
  (:require [clojure.string :as str]
            [hive-spi.await.ports :as ports]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def max-deadline-ms
  "The furthest away a polling awaiter's deadline may be: 48 hours."
  (* 48 60 60 1000))

;; =============================================================================
;; Refusals
;; =============================================================================

(defn refusal
  "The ex-info a source throws when it refuses a subscription: message
   \"await <source>: <message>\", data {:error :await/invalid-subscription
   :source source} merged with DATA. Pure."
  [source message data]
  (ex-info (str "await " (name source) ": " message)
           (merge {:error :await/invalid-subscription :source source} data)))

(defn not-allowed
  "The ex-info for a value outside the operator allowlist under KEY of
   SOURCE's policy. Names the config path to set, never a secret. Pure."
  [source key message]
  (ex-info (str "await " (name source) ": " message
                " (operator allowlist config :await " (pr-str source) " " (pr-str key) ")")
           {:error :await/not-allowed :source source :config [:await source key]}))

(defn allowed!
  "VALUE when it is in the allowlist (get POLICY KEY) of SOURCE, compared by
   string form; else throw not-allowed. A nil or empty list denies
   everything (default deny). WHAT names the value in the message."
  [source policy key value what]
  (let [allow (set (map str (get policy key)))]
    (when-not (contains? allow (str value))
      (throw (not-allowed source key (str what " " (pr-str value) " is not allowlisted"))))
    value))

;; =============================================================================
;; Spec keys
;; =============================================================================

(defn kw-keys
  "X with kebab keyword keys, recursively through nested maps and
   sequences: JSON objects arrive with string keys and snake_case names
   (connection_ref -> :connection-ref). Scalars are kept. Pure."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]]
                             [(if (or (string? k) (keyword? k))
                                (keyword (str/replace (name k) "_" "-"))
                                k)
                              (kw-keys v)]))
                   x)
    (sequential? x) (mapv kw-keys x)
    :else x))

(defn spec-of
  "The :spec map of SUBSCRIPTION, keywordised; {} when absent. Pure."
  [subscription]
  (let [s (kw-keys (:spec subscription))]
    (if (map? s) s {})))

(defn- shape-map
  "SHAPE as a {key -> sub-shape-or-nil} map."
  [shape]
  (if (set? shape) (zipmap shape (repeat nil)) shape))

(defn- key-text [path] (str/join "." (map name path)))

(defn unknown-keys
  "Every path in SPEC the closed SHAPE does not name, as sorted dotted
   strings (\"predicate.password\"). A map under a leaf key is unknown as a
   whole, and so is a map inside a sequence under a leaf key; a map or a
   sequence of maps under a nested shape is walked. Pure."
  [shape spec]
  (letfn [(walk [shape v path]
            (cond
              (map? v)
              (if (nil? shape)
                [(key-text path)]
                (let [sm (shape-map shape)]
                  (mapcat (fn [[k sub]]
                            (let [p (conj path k)]
                              (if (and (keyword? k) (contains? sm k))
                                (walk (get sm k) sub p)
                                [(key-text (conj path (if (keyword? k) k (keyword (str k)))))])))
                          v)))

              (sequential? v)
              (mapcat #(walk shape % path) v)

              :else []))]
    (vec (sort (distinct (walk shape spec []))))))

(defn- shape-keys [shape]
  (letfn [(paths [shape prefix]
            (mapcat (fn [[k sub]]
                      (let [p (conj prefix k)]
                        (if sub (paths sub p) [(key-text p)])))
                    (shape-map shape)))]
    (sort (paths shape []))))

(defn closed-spec
  "The keywordised spec of SUBSCRIPTION, refused when any key, at any depth,
   is outside SHAPE (see unknown-keys). A spec is persisted by the host, so
   an unknown key (a url, a password), nested or not, is never accepted. Pure."
  [source subscription shape]
  (let [spec (spec-of subscription)
        extra (unknown-keys shape spec)]
    (when (seq extra)
      (throw (refusal source (str "unknown spec key(s): " (str/join ", " extra)
                                  "; accepted: " (str/join ", " (shape-keys shape)))
                      {:unknown extra})))
    spec))

;; =============================================================================
;; Closed schemas
;; =============================================================================

(defn closed-schema?
  "True when every :map in SCHEMA's form is {:closed true}, so a value that
   validates against it can carry no key the schema does not name. A parsed
   spec is what a host persists; its schema must be closed. Pure."
  [schema]
  (let [form  (m/form schema)
        nodes (tree-seq coll? seq form)
        maps  (filter #(and (vector? %) (= :map (first %))) nodes)]
    (every? (fn [mv]
              (let [p (second mv)]
                (and (map? p) (true? (:closed p)))))
            maps)))

;; =============================================================================
;; Comparators
;; =============================================================================

(def comparators
  "Comparison operators a predicate may name, by their text."
  {">" > ">=" >= "<" < "<=" <= "==" == "=" == "!=" not=})

(defn comparator-of
  "The comparison fn OP names (a string or keyword), or nil. Pure."
  [op]
  (when (or (string? op) (keyword? op))
    (get comparators (str/trim (name op)))))

(defn ->number
  "X as a number when it is one or reads as one, else nil. NaN is nil. Pure."
  [x]
  (cond
    (number? x) (when-not (NaN? x) x)
    (string? x) (let [s (str/trim x)]
                  (cond
                    (re-matches #"[+-]?\d{1,18}" s) (parse-long s)
                    (re-matches #"[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?" s)
                    (let [d (parse-double s)] (when (and d (not (NaN? d))) d))
                    :else nil))
    :else nil))

(defn compare-values
  "True when OBSERVED OP EXPECTED holds. Numbers compare numerically (a
   numeric string is a number); anything else only under = / == / !=, by
   its string form. An unknown op, a NaN or a nil observation never
   holds. Pure."
  [op observed expected]
  (let [f  (comparator-of op)
        on (->number observed)
        en (->number expected)
        o  (when f (str/trim (name op)))]
    (boolean
     (cond
       (or (nil? f) (nil? observed)) false
       (and on en) (if (= "!=" o) (not= (double on) (double en)) (f on en))
       (#{"=" "=="} o) (= (str observed) (str expected))
       (= "!=" o) (not= (str observed) (str expected))
       :else false))))

;; =============================================================================
;; Timing
;; =============================================================================

(defn poll-interval
  "The poll interval SUBSCRIPTION asks for, or TIMING's default. Pure."
  [subscription timing]
  (let [p (:poll-ms subscription)]
    (long (if (integer? p) p (:default-poll-ms timing)))))

(defn timing-errors
  "Why AWAITER ({:subscription :deadline}) cannot be polled under TIMING at
   NOW, as a vector of messages (empty when it can). A deadline is required
   and at most max-deadline-ms away; :poll-ms is an integer at or above the
   minimum. Pure."
  [awaiter timing now]
  (let [p (get-in awaiter [:subscription :poll-ms])
        d (:deadline awaiter)]
    (cond-> []
      (and (some? p) (not (integer? p)))
      (conj ":poll-ms must be an integer (ms)")

      (and (integer? p) (< (long p) (long (:min-poll-ms timing))))
      (conj (str ":poll-ms " p " is under the minimum " (:min-poll-ms timing) " ms"))

      (nil? d)
      (conj "a deadline is required (deadline or timeout_ms): a poll must end")

      (and (some? d) (some? now) (> (- (long d) (long now)) max-deadline-ms))
      (conj (str "the deadline is more than " (quot max-deadline-ms 3600000) " h away")))))

;; =============================================================================
;; Contracts
;; =============================================================================

(def ExInfo
  "An ex-info."
  [:fn {:error/message "must be an ex-info"} #(some? (ex-data %))])

(m/=> refusal [:=> [:cat :keyword :string [:maybe :map]] ExInfo])
(tc/ann-op refusal)
(m/=> not-allowed [:=> [:cat :keyword :any :string] ExInfo])
(tc/ann-op not-allowed)
(m/=> allowed! [:=> [:cat :keyword [:maybe :map] :any :any :string] :any])
(tc/ann-op allowed!)
(m/=> kw-keys [:=> [:cat :any] :any])
(tc/ann-op kw-keys)
(m/=> spec-of [:=> [:cat [:maybe :map]] :map])
(tc/ann-op spec-of)
(m/=> unknown-keys [:=> [:cat ports/Shape :any] [:vector :string]])
(tc/ann-op unknown-keys)
(m/=> closed-spec [:=> [:cat :keyword [:maybe :map] ports/Shape] :map])
(tc/ann-op closed-spec)
(m/=> closed-schema? [:=> [:cat :any] :boolean])
(tc/ann-op closed-schema?)
(m/=> comparator-of [:=> [:cat :any] [:maybe ifn?]])
(tc/ann-op comparator-of)
(m/=> ->number [:=> [:cat :any] [:maybe number?]])
(tc/ann-op ->number)
(m/=> compare-values [:=> [:cat :any :any :any] :boolean])
(tc/ann-op compare-values)
(m/=> poll-interval [:=> [:cat [:maybe :map] ports/Timing] :int])
(tc/ann-op poll-interval)
(m/=> timing-errors [:=> [:cat :map ports/Timing [:maybe :int]] [:vector :string]])
(tc/ann-op timing-errors)
