(ns hive-spi.working-memory.ports-reload-test
  "Re-evaluating hive-spi.working-memory.ports must not mint new protocols: a
   store built before the reload has to keep satisfying them."
  (:require [clojure.test :refer [deftest is]]
            [hive-spi.working-memory.ports :as ports]
            [hive-spi.working-memory.reference-store :as rs]))

;; SPDX-License-Identifier: MIT

(defn- protocol-vars []
  (->> (ns-publics 'hive-spi.working-memory.ports)
       vals
       (filter #(let [v @%] (and (map? v) (:on-interface v))))
       (sort-by str)))

(deftest reloading-the-ports-keeps-every-protocol
  (let [store (rs/make-store)
        before (into {} (map (juxt str deref)) (protocol-vars))]
    (is (seq before) "the ports namespace declares protocols")
    (require 'hive-spi.working-memory.ports :reload)
    (doseq [v (protocol-vars)]
      (is (identical? (get before (str v)) @v) (str v " survived the reload")))
    (is (satisfies? ports/IWorkingMemoryStore store))
    (is (nil? (ports/get-entry store "w" "k")) "a pre-reload store still answers")))
