(ns hive-spi.time.clock-portable
  "IClock over the smallest calendar surface every hive runtime carries: a
   millisecond epoch reading and broken-down local date/time fields, with the
   renderings built by zero-padded string concatenation rather than by a
   DateTimeFormatter or by `format`.

   Both host arms are declared: the JVM family reads
   `System/currentTimeMillis` and `java.time.LocalDateTime`, ClojureScript
   reads `js/Date`. Neither `System`, `java.time` nor `format` exists on
   ClojureScript, so a namespace that names them unconditionally is not
   portable however its file is spelled.

   `clock-iso` is local and carries no zone — the host this implementation
   exists for has no zone database. On a JVM the port prefers
   `hive-spi.time.clock-jvm`, whose rendering is zoned."
  (:require [hive-spi.time.ports :as time]))

;; SPDX-License-Identifier: MIT

(defn- pad
  "N as a decimal string of at least WIDTH digits, zero-padded on the left."
  [width n]
  (let [s (str n)
        short-by (- width (count s))]
    (if (pos? short-by)
      (str (apply str (repeat short-by \0)) s)
      s)))

(defn- now-fields
  "The current LOCAL wall clock broken into :year :month :day :hour :minute
   :second :milli. Month and day are 1-based."
  []
  #?(:clj  (let [d (java.time.LocalDateTime/now)]
             {:year   (.getYear d)
              :month  (.getMonthValue d)
              :day    (.getDayOfMonth d)
              :hour   (.getHour d)
              :minute (.getMinute d)
              :second (.getSecond d)
              :milli  (quot (.getNano d) 1000000)})
     :cljs (let [d (js/Date.)]
             {:year   (.getFullYear d)
              :month  (inc (.getMonth d))
              :day    (.getDate d)
              :hour   (.getHours d)
              :minute (.getMinutes d)
              :second (.getSeconds d)
              :milli  (.getMilliseconds d)})))

(defn- stamp-of
  "FIELDS as the 14-digit `yyyyMMddHHmmss` wall-clock stamp."
  [{:keys [year month day hour minute second]}]
  (str (pad 4 year) (pad 2 month) (pad 2 day)
       (pad 2 hour) (pad 2 minute) (pad 2 second)))

(defn- iso-of
  "FIELDS as a zoneless ISO-8601 `yyyy-MM-ddTHH:mm:ss.SSS` rendering."
  [{:keys [year month day hour minute second milli]}]
  (str (pad 4 year) "-" (pad 2 month) "-" (pad 2 day)
       "T" (pad 2 hour) ":" (pad 2 minute) ":" (pad 2 second)
       "." (pad 3 milli)))

(defrecord PortableClock []
  time/IClock
  (clock-millis [_] #?(:clj  (System/currentTimeMillis)
                       :cljs (.getTime (js/Date.))))
  (clock-stamp [_] (stamp-of (now-fields)))
  (clock-iso [_] (iso-of (now-fields))))

(defn default-clock
  "A broken-down-fields IClock that needs no DateTimeFormatter."
  []
  (->PortableClock))

(defn install!
  "Install the portable clock as the explicitly active one. Returns it."
  []
  (time/set-clock! (default-clock)))
