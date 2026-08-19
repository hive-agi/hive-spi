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
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -iclock-defined? (atom false))

(when (compare-and-set! -iclock-defined? false true)
  (defprotocol IClock
    "The host's view of the current time."

    (clock-millis [this]
      "Milliseconds since the Unix epoch.")

    (clock-stamp [this]
      "The local wall clock as a 14-digit `yyyyMMddHHmmss` string.")

    (clock-iso [this]
      "The current instant as an ISO-8601 string.")))

(def ^:private host-default-candidates
  ['hive-spi.time.clock-jvm/default-clock
   'hive-spi.time.clock-portable/default-clock])

(defonce ^:private host-default
  (delay
    (some (fn [ctor-sym]
            (try
              (when-let [ctor (requiring-resolve ctor-sym)]
                (ctor))
              (catch #?(:clj Exception :cljs :default) _ nil)))
          host-default-candidates)))

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
