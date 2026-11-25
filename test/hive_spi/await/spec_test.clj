(ns hive-spi.await.spec-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.await.spec :as spec]))

;; SPDX-License-Identifier: MIT

(defn- err [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e)))))

(def shape {:target nil :ref nil :predicate #{:op :value}})

(deftest kw-keys-walks-nested-maps-and-vectors
  (is (= {:connection-ref "x" :predicate {:op-name ">"} :rows [{:a-b 1}]}
         (spec/kw-keys {"connection_ref" "x" "predicate" {"op_name" ">"} "rows" [{"a_b" 1}]}))))

(deftest unknown-keys-sees-every-depth
  (is (= [] (spec/unknown-keys shape {:target "a" :predicate {:op ">" :value 1}})))
  (is (= ["url"] (spec/unknown-keys shape {:target "a" :url "x"})))
  (is (= ["predicate.password"]
         (spec/unknown-keys shape {:predicate {:op ">" :password "p"}})))
  (is (= ["target"] (spec/unknown-keys shape {:target {:password "p"}}))
      "a map under a leaf is unknown as a whole")
  (is (= ["ref"] (spec/unknown-keys shape {:ref [{:password "p"}]}))
      "a map in a vector under a leaf too")
  (is (= ["target"] (spec/unknown-keys shape {:target #{{:password "p"}}}))
      "a map in a SET under a leaf is unknown as a whole")
  (is (= ["predicate.x"] (spec/unknown-keys shape {:predicate [{:op ">"} {:x 1}]}))))

(deftest closed-spec-refuses-a-set-hidden-key
  (let [e (err #(spec/closed-spec :s {:spec {:target #{{:password "s3cr3t-val"}}}} shape))]
    (is (= :await/invalid-subscription (:error e)))
    (is (= ["target"] (:unknown e)))
    (is (not (re-find #"s3cr3t-val" (pr-str e))))))

(deftest closed-spec-refuses-and-never-echoes-values
  (let [e (err #(spec/closed-spec :s {:spec {:predicate {:op ">" :password "s3cr3t"}}} shape))]
    (is (= :await/invalid-subscription (:error e)))
    (is (= ["predicate.password"] (:unknown e)))
    (is (not (re-find #"s3cr3t" (pr-str e)))))
  (is (= {:target "a"} (spec/closed-spec :s {:spec {"target" "a"}} shape))))

(deftest allowed-is-default-deny
  (is (= "a" (spec/allowed! :s {:targets ["a"]} :targets "a" "target")))
  (doseq [policy [nil {} {:targets []} {:targets ["b"]}]]
    (let [e (err #(spec/allowed! :s policy :targets "a" "target"))]
      (is (= :await/not-allowed (:error e)))
      (is (= [:await :s :targets] (:config e))))))

(deftest comparators-and-numbers
  (is (true? (spec/compare-values ">=" "5" 3)))
  (is (true? (spec/compare-values :== 2 "2.0")))
  (is (true? (spec/compare-values "!=" "a" "b")))
  (is (false? (spec/compare-values ">" "a" "b")) "non-numbers only compare for equality")
  (is (false? (spec/compare-values "~" 1 1)) "an unknown op never holds")
  (is (false? (spec/compare-values "==" nil nil)))
  (is (false? (spec/compare-values ">" ##NaN 1)))
  (is (nil? (spec/->number "1e")))
  (is (= 12 (spec/->number " 12 ")))
  (is (= 1.5 (spec/->number "1.5"))))

(deftest timing-errors-require-a-bounded-deadline
  (let [t {:min-poll-ms 10 :default-poll-ms 50 :probe-timeout-ms 100}]
    (is (= [] (spec/timing-errors {:subscription {:poll-ms 20} :deadline 1000} t 0)))
    (is (= 1 (count (spec/timing-errors {:subscription {} :deadline nil} t 0))))
    (is (= 1 (count (spec/timing-errors {:subscription {:poll-ms 5} :deadline 10} t 0))))
    (is (= 1 (count (spec/timing-errors {:subscription {:poll-ms "x"} :deadline 10} t 0))))
    (is (= 1 (count (spec/timing-errors {:subscription {} :deadline (inc spec/max-deadline-ms)} t 0))))
    (is (= 50 (spec/poll-interval {} t)))
    (is (= 20 (spec/poll-interval {:poll-ms 20} t)))))

(deftest closed-schema-check
  (is (true? (spec/closed-schema? [:map {:closed true} [:a [:map {:closed true} [:b :int]]]])))
  (is (false? (spec/closed-schema? [:map {:closed true} [:a [:map [:b :int]]]])))
  (is (false? (spec/closed-schema? [:map [:a :int]])))
  (is (true? (spec/closed-schema? :string))))

(deftest closed-schema-rejects-any-and-map-of
  (is (false? (spec/closed-schema? [:map {:closed true} [:a :any]]))
      ":any admits anything")
  (is (false? (spec/closed-schema? [:map {:closed true} [:a [:map-of :keyword :string]]]))
      ":map-of admits any key")
  (is (false? (spec/closed-schema? [:map {:closed true} [:a [:map {:closed true}
                                                          [:b :any]]]]))
      ":any nested under a closed map is still open")
  (is (false? (spec/closed-schema? [:map {:closed true} [:a [:vector [:map-of :keyword :any]]]]))
      ":map-of nested in a vector is still open")
  (is (false? (spec/closed-schema? :any))
      "a bare :any schema is not closed"))
