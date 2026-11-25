(ns hive-spi.await.ports-reload-test
  "Re-evaluating hive-spi.await.ports must not mint new protocols: a source
   built before the reload has to keep satisfying them."
  (:require [clojure.edn]
            [clojure.string]
            [clojure.test :refer [deftest is]]
            [hive-spi.await.ports :as ports]
            [hive-spi.await.stub :as stub]))

;; SPDX-License-Identifier: MIT

(deftest reloading-the-ports-keeps-every-protocol
  (let [src (stub/make-source)
        before [ports/IProbeSource ports/ISecretScheme ports/IPollScheduler]]
    (require 'hive-spi.await.ports :reload)
    (is (every? true? (map identical? before
                           [ports/IProbeSource ports/ISecretScheme ports/IPollScheduler])))
    (is (satisfies? ports/IProbeSource src))))

(deftest await-namespaces-stay-out-of-aot
  (let [aot (:aot/source-namespaces (clojure.edn/read-string (slurp "version.edn")))]
    (is (seq aot))
    (is (not-any? #(clojure.string/starts-with? (str %) "hive-spi.await") aot)
        "an AOT-compiled protocol class breaks satisfies? after a hot reload")))
