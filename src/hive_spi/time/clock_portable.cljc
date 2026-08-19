(ns hive-spi.time.clock-portable
  "IClock over the smallest calendar surface every hive runtime carries:
   `System/currentTimeMillis` and `java.time.LocalDateTime` field accessors,
   with the renderings built by `format` rather than by a DateTimeFormatter.

   `clock-iso` is local and carries no zone — the host this implementation
   exists for has no zone database. On a JVM the port prefers
   `hive-spi.time.clock-jvm`, whose rendering is zoned."
  (:require [hive-spi.time.ports :as time]))

;; SPDX-License-Identifier: MIT

(defn- now-fields
  []
  (let [d (java.time.LocalDateTime/now)]
    {:year   (.getYear d)
     :month  (.getMonthValue d)
     :day    (.getDayOfMonth d)
     :hour   (.getHour d)
     :minute (.getMinute d)
     :second (.getSecond d)
     :milli  (quot (.getNano d) 1000000)}))

(defn- stamp-of
  [{:keys [year month day hour minute second]}]
  (format "%04d%02d%02d%02d%02d%02d" year month day hour minute second))

(defn- iso-of
  [{:keys [year month day hour minute second milli]}]
  (format "%04d-%02d-%02dT%02d:%02d:%02d.%03d" year month day hour minute second milli))

(defrecord PortableClock []
  time/IClock
  (clock-millis [_] (System/currentTimeMillis))
  (clock-stamp [_] (stamp-of (now-fields)))
  (clock-iso [_] (iso-of (now-fields))))

(defn default-clock
  "A LocalDateTime-backed IClock that needs no DateTimeFormatter."
  []
  (->PortableClock))

(defn install!
  "Install the portable clock as the explicitly active one. Returns it."
  []
  (time/set-clock! (default-clock)))
