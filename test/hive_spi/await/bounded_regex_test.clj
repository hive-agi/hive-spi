(ns hive-spi.await.bounded-regex-test
  (:require [clojure.test :refer [deftest is]]
            [hive-spi.await.bounded-regex :as brx]))

;; SPDX-License-Identifier: MIT

(defn- refuse [message] (throw (ex-info message {:error :await/invalid-subscription})))

(deftest nested-quantifiers-are-detected
  (is (false? (brx/nested-quantifier? "job completed rows=\\d+")))
  (is (false? (brx/nested-quantifier? "(ab)+c")))
  (is (true? (brx/nested-quantifier? "((ab)+c)*")))
  (is (true? (brx/nested-quantifier? "(a+)+$")))
  (is (true? (brx/nested-quantifier? "(x|xx)*y")))
  (is (false? (brx/nested-quantifier? "\\(a+\\)+")) "escaped parens are not a group"))

(deftest check-pattern-refuses-unbounded-shapes
  (is (instance? java.util.regex.Pattern (brx/check-pattern! "worker done" refuse)))
  (doseq [rx [nil "" "(" "(a+)+$" "(x|xx)*y" "(a)\\1" "(?<n>a)\\k<n>"
              (apply str (repeat (inc brx/max-pattern-chars) "a"))]]
    (is (thrown? clojure.lang.ExceptionInfo (brx/check-pattern! rx refuse)) (pr-str rx))))

(deftest find-in-matches-and-stops-a-runaway
  (is (= "rows=12" (brx/find-in (re-pattern "rows=\\d+") "done rows=12" 1000)))
  (is (nil? (brx/find-in (re-pattern "rows=\\d+") "done" 1000)))
  (let [p (re-pattern "(a|aa)+b")
        s (apply str (repeat 5000 "a"))
        e (try (brx/find-in p s 0) nil
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :await/regex-timeout (:error e))
        "matching stops once its time budget is spent (the charAt check)")))
