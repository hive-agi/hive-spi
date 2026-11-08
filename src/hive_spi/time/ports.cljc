(ns hive-spi.time.ports
  "The wall-clock port.

   Declared with no host dependency, so a namespace that stamps an entry stays
   loadable on every runtime and the calendar arrives through an installed
   IClock.

   Contract: `clock-millis` is milliseconds since the Unix epoch;
   `clock-stamp` is the local wall clock as `yyyyMMddHHmmss`, exactly 14
   digits; `clock-iso` is an ISO-8601 rendering of the current instant, which
   carries a zone or offset when the host provides one and is otherwise local.

   Empty-policy: with no clock installed the port resolves the first host
   default it can load — `hive-spi.time.clock-jvm`, else
   `hive-spi.time.clock-portable` — and yields nil when neither is available.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations."
  (:require [hive-spi.slot :as slot]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -iclock-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -iclock-defined? false true))
  (defprotocol IClock
    "The host's view of the current time."

    (clock-millis [this]
      "Milliseconds since the Unix epoch.")

    (clock-stamp [this]
      "The local wall clock as a 14-digit `yyyyMMddHHmmss` string.")

    (clock-iso [this]
      "The current instant as an ISO-8601 string.")))

;; The whole host-default ladder is JVM-family only. `requiring-resolve` is the
;; primitive that walks it, and ClojureScript resolves its namespaces at compile
;; time and has no runtime require — so neither the candidate list nor the walk
;; exists there, and the empty-policy yields nil.
#?(:clj
   (def ^:private host-default-candidates
     ['hive-spi.time.clock-jvm/default-clock
      'hive-spi.time.clock-portable/default-clock]))

(defonce ^:private host-default
  (delay
    #?(:clj (some (fn [ctor-sym]
                    (try
                      (when-let [ctor (requiring-resolve ctor-sym)]
                        (ctor))
                      (catch Exception _ nil)))
                  host-default-candidates)
       :cljs nil)))

(defonce ^:private clock-slot
  (slot/single-slot {:validate #(satisfies? IClock %)
                     :on-empty #(deref host-default)}))

(defn set-clock!
  "Install CLOCK as the active clock. Returns CLOCK."
  [clock]
  (slot/install! clock-slot clock))

(defn get-clock
  "The active clock: the installed one, else the host default, else nil."
  []
  (slot/current clock-slot))

(defn clear-clock!
  "Remove the installed clock, so consumers fall back to the host default.
   Returns nil."
  []
  (slot/clear! clock-slot))

(defn clock-set?
  "True iff a clock is explicitly installed. The host default does not count."
  []
  (slot/present? clock-slot))

(defn- active
  []
  (or (get-clock)
      (throw (ex-info "No IClock installed" {:error :clock/no-clock}))))

(defn now-millis
  "Milliseconds since the Unix epoch, through the active clock."
  []
  (clock-millis (active)))

(defn entry-stamp
  "The local wall clock as `yyyyMMddHHmmss`, through the active clock."
  []
  (clock-stamp (active)))

(defn iso-timestamp
  "The current instant as an ISO-8601 string, through the active clock."
  []
  (clock-iso (active)))

;; =============================================================================
;; Contracts
;; =============================================================================

(def Stamp
  "The 14-digit `yyyyMMddHHmmss` wall-clock stamp."
  [:re #"^\d{14}$"])

(def IsoTimestamp
  "An ISO-8601 instant: date, `T`, time to the second, then any fraction,
   zone or offset the host provides."
  [:re #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*$"])

(def Clock
  "An IClock implementation. Generates a frozen clock at a generated instant."
  [:fn {:error/message "must satisfy IClock"
        :gen/schema    [:int {:min 0 :max 4102444800000}]
        :gen/fmap      (fn [millis]
                         (reify IClock
                           (clock-millis [_] millis)
                           (clock-stamp [_] "19700101000000")
                           (clock-iso [_] "1970-01-01T00:00:00Z")))}
   #(satisfies? IClock %)])

(m/=> set-clock! [:=> [:cat Clock] Clock])
(m/=> get-clock [:=> [:cat] [:maybe Clock]])
(m/=> clear-clock! [:=> [:cat] :nil])
(m/=> clock-set? [:=> [:cat] :boolean])
(m/=> now-millis [:=> [:cat] :int])
(m/=> entry-stamp [:=> [:cat] Stamp])
(m/=> iso-timestamp [:=> [:cat] IsoTimestamp])
