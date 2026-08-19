(ns hive-spi.time.clock-jvm
  "`java.time` implementation of the IClock port, including the zoned ISO
   rendering that `java.time.ZonedDateTime` gives and a host without a full
   java.time cannot.

   Loadable only where `java.time.format.DateTimeFormatter` and
   `java.time.ZonedDateTime` exist; the port resolves this namespace softly and
   falls through to `hive-spi.time.clock-portable` where they do not."
  (:require [hive-spi.time.ports :as time])
  (:import (java.time LocalDateTime ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)))

;; SPDX-License-Identifier: MIT

(def ^:private stamp-formatter
  (DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))

(defrecord JavaTimeClock []
  time/IClock
  (clock-millis [_] (System/currentTimeMillis))
  (clock-stamp [_] (.format (LocalDateTime/now) stamp-formatter))
  (clock-iso [_] (str (ZonedDateTime/now (ZoneId/systemDefault)))))

(defn default-clock
  "A java.time-backed IClock."
  []
  (->JavaTimeClock))

(defn install!
  "Install the java.time clock as the explicitly active one. Returns it."
  []
  (time/set-clock! (default-clock)))
