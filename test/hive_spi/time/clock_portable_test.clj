(ns hive-spi.time.clock-portable-test
  "The portable clock renders by string concatenation instead of a
   DateTimeFormatter, so its padding is the whole contract: a stamp one digit
   short reorders every entry id minted on a runtime without java.time."
  (:require [clojure.test :refer [deftest is]]
            [hive-schemas.test :as hst]
            [hive-spi.time.clock-portable :as portable]
            [hive-spi.time.ports :as time]
            [hive-test.golden :refer [deftest-golden]]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defn- restore-clock [f]
  (let [installed (when (time/clock-set?) (time/get-clock))]
    (try (f)
         (finally (time/clear-clock!)
                  (when installed (time/set-clock! installed))))))

(clojure.test/use-fixtures :each restore-clock)

(hst/deftrifecta-from-schema default-clock-renders-the-port-shapes
  hive-spi.time.clock-portable/default-clock
  {:in  [:cat]
   :out time/Clock
   :rel (fn [_ clock]
          (and (m/validate time/Stamp (time/clock-stamp clock))
               (m/validate [:re #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}$"]
                           (time/clock-iso clock))
               (pos? (time/clock-millis clock))))})

(hst/deftrifecta-from-schema install-makes-the-portable-clock-active
  hive-spi.time.clock-portable/install!
  {:in  [:cat]
   :out time/Clock
   :rel (fn [_ clock] (and (time/clock-set?) (identical? clock (time/get-clock))))})

(def ^:private field-cases
  "Broken-down fields at the padding edges: single-digit month/day/hour, a
   year below 1000, a zero millisecond and a full one."
  [{:year 2026 :month 8 :day 18 :hour 21 :minute 0 :second 0 :milli 0}
   {:year 999 :month 1 :day 1 :hour 0 :minute 0 :second 0 :milli 7}
   {:year 2026 :month 12 :day 31 :hour 23 :minute 59 :second 59 :milli 999}])

(deftest-golden the-renderings-pad-every-field
  "test/golden/hive_spi/time/clock_portable.edn"
  (mapv (fn [f] [(#'portable/stamp-of f) (#'portable/iso-of f)]) field-cases))

(deftest the-padding-width-is-a-floor-not-a-truncation
  (is (= "007" (#'portable/pad 3 7)))
  (is (= "12345" (#'portable/pad 3 12345))))
