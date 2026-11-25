(ns hive-spi.await.ports-reload-test
  "Re-evaluating hive-spi.await.ports must not mint new protocols: a source
   built before the reload keeps satisfying them, AND a source an addon
   defines AFTER the reload still satisfies them."
  (:require [clojure.edn]
            [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [hive-spi.await.ports :as ports]
            [hive-spi.await.registry :as reg]
            [hive-spi.await.stub :as stub]))

;; SPDX-License-Identifier: MIT

(deftest reloading-the-ports-keeps-every-protocol
  (let [src (stub/make-source)
        before [ports/IProbeSource ports/ISecretScheme ports/IPollScheduler]]
    (require 'hive-spi.await.ports :reload)
    (is (every? true? (map identical? before
                           [ports/IProbeSource ports/ISecretScheme ports/IPollScheduler])))
    (is (satisfies? ports/IProbeSource src))))

(deftest a-source-defined-after-a-reload-still-satisfies
  ;; The finding: an addon that defines its source AFTER the ports ns was
  ;; reloaded must satisfy the protocol and register. The reify is EVAL'd
  ;; here (compiled after the reload), so it binds whatever interface the
  ;; reloaded ports ns hands out -- a guard that only skips the runtime form
  ;; leaves the interface re-minted and this fails.
  (reg/reset-registry!)
  (require 'hive-spi.await.ports :reload)
  (let [src (eval '(reify hive-spi.await.ports/IProbeSource
                     (descriptor [_] {:type :post-reload :spec-shape #{}
                                      :parsed-schema [:map]
                                      :timing {:min-poll-ms 1 :default-poll-ms 1
                                               :probe-timeout-ms 1}})
                     (parse-spec [_ _ _] {})
                     (probe! [_ _ _] {})
                     (judge [_ _ _] {:match? false})))]
    (testing "a source reified after the reload satisfies the protocol"
      (is (satisfies? ports/IProbeSource src)))
    (testing "register-source! accepts it"
      (is (identical? src (reg/register-source! src)))
      (is (identical? src (reg/source :post-reload)))))
  (reg/reset-registry!))

(deftest await-namespaces-stay-out-of-aot
  (let [aot (:aot/source-namespaces (clojure.edn/read-string (slurp "version.edn")))]
    (is (seq aot))
    (is (not-any? #(clojure.string/starts-with? (str %) "hive-spi.await") aot)
        "an AOT-compiled protocol class breaks satisfies? after a hot reload")))
