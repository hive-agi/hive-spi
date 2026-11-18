(ns hive-spi.projection.ports-test
  "The floor-projection slot and its laws. Whatever is installed decides what
   a host withholds from an inviolable entry, so a slot that drops or swaps
   the projection silently changes what binds on the wire."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.projection.conformance :as conf]
            [hive-spi.projection.ports :as pp]
            [hive-spi.projection.stub :as stub]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [prior (pp/get-projection)]
      (try (f)
           (finally (if prior (pp/set-projection! prior) (pp/clear-projection!)))))))

(conf/defconformance identity-projection stub/harness)
(conf/defconformance truncating-projection stub/truncating-harness)

(hst/deftrifecta-from-schema set-projection-installs-the-projection
  hive-spi.projection.ports/set-projection!
  {:in  [:cat pp/Projection]
   :out pp/Projection
   :rel (fn [[projection] out]
          (and (identical? projection out)
               (identical? projection (pp/get-projection))
               (true? (pp/projection-set?))))})

(deftest clearing-empties-the-slot
  (pp/clear-projection!)
  (is (nil? (pp/get-projection)) "no host default: an absent projection is nil")
  (is (false? (pp/projection-set?))))

(deftest a-non-projection-is-rejected
  (pp/clear-projection!)
  (is (thrown? AssertionError (pp/set-projection! {:not :a-projection})))
  (is (false? (pp/projection-set?))))

(deftest an-empty-slot-is-identity
  (testing "the host fallback: with nothing installed, a floor entry is sent whole"
    (pp/clear-projection!)
    (doseq [e conf/samples]
      (is (= e (pp/project-or-identity e))))))

(deftest the-installed-projection-is-the-one-used
  (pp/set-projection! (stub/truncating))
  (let [e (second conf/samples)
        out (pp/project-or-identity e)]
    (is (not= e out) "the truncating stub withheld something")
    (is (:projected out))
    (is (= "s-why" (:id out)))))

(deftest the-suite-is-not-vacuous
  (testing "a projection that breaks a law fails the suite's checks"
    (let [growing (reify pp/FloorProjection
                    (project-floor [_ e] (assoc e :C (str (:C e) "!") :ref true)))
          e (first conf/samples)
          out (pp/project-floor growing e)]
      (is (> (count (:C out)) (count (:C e))) "grows, which :never-grows rejects")
      (is (:ref out) "pointerises, which :never-a-pointer rejects"))))
