(ns hive-spi.time.ports-test
  "Entry ids and timestamps are identity for every memory store, so a clock
   that renders a stamp the wrong width, or a slot that ignores an injected
   clock, corrupts ordering and dedup across the ecosystem."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-spi.time.clock-portable :as portable]
            [hive-spi.time.ports :as time]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- restore-clock
  "Snapshot the installed clock, run F, restore what was installed."
  [f]
  (let [installed (when (time/clock-set?) (time/get-clock))]
    (try
      (f)
      (finally
        (time/clear-clock!)
        (when installed (time/set-clock! installed))))))

(use-fixtures :each restore-clock)

;; =============================================================================
;; Contract: the renderings
;; =============================================================================

(deftest the-host-default-is-the-java-time-clock-test
  (is (some? (time/get-clock)))
  (is (= "hive_spi.time.clock_jvm.JavaTimeClock" (.getName (class (time/get-clock))))
      "on a JVM the port prefers the zoned java.time implementation"))

(deftest the-stamp-is-fourteen-digits-test
  (is (re-matches #"\d{14}" (time/entry-stamp))))

(deftest the-iso-rendering-parses-as-a-timestamp-test
  (let [iso (time/iso-timestamp)]
    (is (re-find #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" iso))
    (is (some? (java.time.ZonedDateTime/parse iso))
        "the JVM clock renders a zoned instant")))

(deftest millis-track-the-host-clock-test
  (let [before (System/currentTimeMillis)
        millis (time/now-millis)
        after (System/currentTimeMillis)]
    (is (<= before millis after))))

;; =============================================================================
;; Contract: the two implementations agree
;; =============================================================================

(deftest the-portable-clock-renders-the-same-stamp-as-the-jvm-one-test
  (testing "a stamp read between two java.time stamps is one of them"
    (let [before (time/entry-stamp)
          portable-stamp (time/clock-stamp (portable/default-clock))
          after (time/entry-stamp)]
      (is (contains? (into #{} [before after]) portable-stamp)
          "the portable rendering must be the same wall clock, not a different one"))))

(deftest the-portable-clock-satisfies-the-whole-contract-test
  (time/set-clock! (portable/default-clock))
  (is (re-matches #"\d{14}" (time/entry-stamp)))
  (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}" (time/iso-timestamp)))
  (is (pos? (time/now-millis))))

;; =============================================================================
;; Contract: the slot routes to an injected implementation
;; =============================================================================

(defrecord FrozenClock [millis stamp iso]
  time/IClock
  (clock-millis [_] millis)
  (clock-stamp [_] stamp)
  (clock-iso [_] iso))

(deftest an-installed-clock-supersedes-the-host-default-test
  (time/set-clock! (->FrozenClock 42 "20260818210000" "2026-08-18T21:00:00Z"))
  (is (true? (time/clock-set?)))
  (is (= 42 (time/now-millis)))
  (is (= "20260818210000" (time/entry-stamp)))
  (is (= "2026-08-18T21:00:00Z" (time/iso-timestamp))))

(deftest clearing-falls-back-to-the-host-default-test
  (time/set-clock! (->FrozenClock 42 "20260818210000" "2026-08-18T21:00:00Z"))
  (time/clear-clock!)
  (is (false? (time/clock-set?)))
  (is (not= 42 (time/now-millis))))

(deftest an-implementation-that-is-not-a-clock-is-rejected-test
  (is (thrown? AssertionError (time/set-clock! {:not :a-clock})))
  (is (false? (time/clock-set?)) "a rejected install leaves the slot empty"))
