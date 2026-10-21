(ns hive-spi.time.clock-jvm-test
  "The java.time clock is the JVM host default; its ISO rendering is the
   zoned one every other clock is compared against."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.time.ports :as time]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defn- restore-clock [f]
  (let [installed (when (time/clock-set?) (time/get-clock))]
    (try (f)
         (finally (time/clear-clock!)
                  (when installed (time/set-clock! installed))))))

(use-fixtures :each restore-clock)

(defn- zoned? [iso]
  (some? (try (java.time.ZonedDateTime/parse iso) (catch Exception _ nil))))

(hst/deftrifecta-from-schema default-clock-renders-a-zoned-instant
  hive-spi.time.clock-jvm/default-clock
  {:in  [:cat]
   :out time/Clock
   :rel (fn [_ clock]
          (and (m/validate time/Stamp (time/clock-stamp clock))
               (zoned? (time/clock-iso clock))
               (pos? (time/clock-millis clock))))})

(hst/deftrifecta-from-schema install-makes-the-jvm-clock-active
  hive-spi.time.clock-jvm/install!
  {:in  [:cat]
   :out time/Clock
   :rel (fn [_ clock] (and (time/clock-set?) (identical? clock (time/get-clock))))})
