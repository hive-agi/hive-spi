(ns hive-spi.resolve-test
  "A port's host-default ladder must tell an ABSENT adapter (quiet, expected)
   from a BROKEN one (a defect that must be reported). A silent catch reads
   both as absent; these tests pin the distinction."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-schemas.test :as hst]
            [hive-spi.resolve :as resolve]))

;; SPDX-License-Identifier: MIT

;; A constructor that throws, and a namespace that is present but cannot load
;; (it requires a dependency the classpath lacks), both reached by symbol.
(defn throwing-ctor [] (throw (ex-info "ctor boom" {})))
(defn working-ctor [] ::built)

(deftest a-loadable-ctor-constructs
  (is (= {:ok ::built} (resolve/construct `working-ctor))))

(deftest an-absent-namespace-is-absent
  (let [res (resolve/construct 'hive-spi.no-such-adapter/ctor)]
    (is (= :resolve/absent (:error res)))
    (is (resolve/absent? res))
    (is (= 'hive-spi.no-such-adapter/ctor (:sym res)))))

(deftest a-missing-var-is-broken-not-absent
  (let [res (resolve/construct 'hive-spi.resolve/no-such-var)]
    (is (= :resolve/missing-var (:error res)))
    (is (not (resolve/absent? res)))))

(deftest a-throwing-ctor-is-broken-not-absent
  (let [res (resolve/construct `throwing-ctor)]
    (is (= :resolve/ctor-failed (:error res)))
    (is (= "ctor boom" (:message res)))
    (is (not (resolve/absent? res)))))

(deftest a-namespace-that-fails-to-compile-is-broken
  (testing "a ClassCastException root cause is not a classpath miss"
    (is (false? (resolve/absence? (ex-info "outer" {} (ClassCastException. "x"))))))
  (testing "a FileNotFoundException root cause is a classpath miss"
    (is (true? (resolve/absence?
                (ex-info "outer" {} (java.io.FileNotFoundException. "dep")))))))

(deftest the-ladder-stops-at-the-first-success-and-keeps-only-defects
  (let [{:keys [value failures]}
        (resolve/first-available ['hive-spi.no-such-adapter/ctor
                                  `throwing-ctor
                                  `working-ctor
                                  'hive-spi.resolve/never-reached])]
    (is (= ::built value))
    (is (= [:resolve/ctor-failed] (mapv :error failures))
        "the absent rung is dropped, the broken rung is kept")))

(deftest an-exhausted-ladder-answers-nil
  (is (= {:value nil :failures []}
         (resolve/first-available ['hive-spi.no-such-adapter/a
                                   'hive-spi.no-such-adapter/b]))))

(deftest a-failure-renders-one-operator-line
  (let [line (resolve/describe-failure (resolve/construct `throwing-ctor))]
    (is (re-find #"present but unusable \(ctor-failed\)" line))
    (is (re-find #"ctor boom" line))))

(hst/deftrifecta-from-schema absent?-is-only-the-quiet-categories
  hive-spi.resolve/absent?
  {:in  [:cat [:maybe [:map [:error [:enum :resolve/absent :resolve/unsupported
                                     :resolve/broken :resolve/missing-var
                                     :resolve/ctor-failed]]]]]
   :out :boolean
   :rel (fn [[res] out]
          (= out (contains? #{:resolve/absent :resolve/unsupported}
                            (:error res))))})
