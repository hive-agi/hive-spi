(ns hive-spi.await.stub-conformance-test
  "The stub source is held to the same conformance suite every real await
   source runs, and the suite itself is shown to catch a source that lets
   an unknown NESTED key through (the persisted-secret defect)."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.await.conformance :as conformance]
            [hive-spi.await.ports :as ports]
            [hive-spi.await.spec :as spec]
            [hive-spi.await.stub :as stub]))

;; SPDX-License-Identifier: MIT

(def policy {:targets ["alpha" "beta" "gamma" "slow" "bad"]})

(def secret-value "s3cr3t-value")

(def scheme (stub/make-scheme {"alpha/secret" secret-value}))

(def ctx {:resolve-secret (fn [ref]
                            (let [[_ id] (re-matches #"test:(.+)" ref)]
                              (ports/resolve! scheme id {:timeout-ms 100})))})

(defn- make-source []
  (stub/make-source {"alpha" 5 "beta" 1
                     ;; a target whose probe! sleeps past any host budget,
                     ;; and one that answers a non-observation
                     "slow" ::stub/sleep
                     "bad"  ::stub/malformed}))

(defn- sub [target op value]
  {:type :stub-probe
   :spec {:target target :ref "test:alpha/secret" :predicate {:op op :value value}}})

(def opts
  {:subscription           (sub "alpha" ">=" 3)
   :policy                 policy
   :ctx                    ctx
   :match-subscription     (sub "alpha" ">=" 3)
   :miss-subscription      (sub "beta" ">=" 3)
   :error-subscription     (sub "gamma" "==" 1)
   :timeout-subscription   (sub "slow" ">=" 3)
   :timeout-ms             200
   :malformed-subscription (sub "bad" "==" 1)
   :secret-canary          secret-value})

(conformance/defconformance stub make-source opts)

(deftest run-conformance-runs-every-case
  (let [r (conformance/run-conformance make-source opts)]
    (is (= conformance/case-ids (:ran r)))
    (is (empty? (:skipped r)))))

(deftest run-conformance-reports-skips-by-name
  (let [r (conformance/run-conformance make-source
                                       {:subscription (sub "alpha" ">=" 3) :policy policy
                                        :skip {:judge-pure "exercised elsewhere"}})]
    (is (= "exercised elsewhere" (get-in r [:skipped :judge-pure])))
    (is (contains? (:skipped r) :probe-match) "an absent :match-subscription skips")
    (is (not (some #{:judge-pure :probe-match} (:ran r))))))

;; A source that closes only the TOP level of its spec: the defect the
;; nested-unknown-key case exists to catch.
(defrecord LeakySource []
  ports/IProbeSource
  (descriptor [_] stub/descriptor-value)
  (parse-spec [_ subscription _policy]
    (let [s (spec/spec-of subscription)
          extra (remove #{:target :ref :predicate} (keys s))]
      (when (seq extra) (throw (spec/refusal :stub-probe "unknown key" {})))
      s))
  (probe! [_ _ _] {:value 1})
  (judge [_ _ _] {:match? false}))

(deftest the-suite-catches-a-nested-unknown-key
  (let [failures (atom [])]
    (with-redefs [clojure.test/do-report
                  (fn [m] (when (#{:fail :error} (:type m)) (swap! failures conj m)))]
      (conformance/run-case ->LeakySource {:subscription (sub "alpha" ">=" 3) :policy policy}
                            :unknown-nested-key-refused))
    (is (seq @failures) "a source that persists predicate.password fails the case")))

(deftest stub-scheme-and-scheduler-behave
  (testing "scheme"
    (is (= "test" (ports/scheme scheme)))
    (is (true? (ports/valid-id? scheme "alpha/secret")))
    (is (false? (ports/valid-id? scheme "../etc")))
    (is (= :await/secret-missing
           (:error (try (ports/resolve! scheme "nope" {}) nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
  (testing "scheduler runs only on tick!, cancels and shuts down"
    (let [s (stub/make-scheduler)
          n (atom 0)
          cancel (ports/schedule-repeating! s #(swap! n inc) 10)]
      (is (= 1 (stub/tick! s)))
      (is (= 1 @n))
      (cancel)
      (is (zero? (stub/tick! s)))
      (ports/schedule-repeating! s #(swap! n inc) 10)
      (is (nil? (ports/shutdown! s)))
      (is (zero? (stub/live-schedules s)))
      (is (thrown? clojure.lang.ExceptionInfo (ports/schedule-repeating! s #(do) 10))))))

(deftest stub-probe-counts-and-observes
  (let [src (make-source)
        p   (ports/parse-spec src (sub "alpha" ">" 7) policy)]
    (is (false? (:match? (ports/judge src p (ports/probe! src p ctx)))))
    (stub/set-observation! src "alpha" 8)
    (is (true? (:match? (ports/judge src p (ports/probe! src p ctx)))))
    (is (= 2 (stub/probe-count src)))))
