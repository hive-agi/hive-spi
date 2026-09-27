(ns hive-spi.memory.ports-reload-test
  "Re-evaluating hive-spi.memory.ports must not mint new protocols: a store
   built before the reload has to keep satisfying them."
  (:require [clojure.test :refer [deftest is]]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.stub :as stub]))

(defn- protocol-vars []
  (->> (ns-publics 'hive-spi.memory.ports)
       vals
       (filter #(let [v @%] (and (map? v) (:on-interface v))))
       (sort-by str)))

(deftest reloading-the-ports-keeps-every-protocol
  (let [store  (stub/create-store)
        before (into {} (map (juxt str deref)) (protocol-vars))]
    (is (seq before) "the ports namespace declares protocols")
    (require 'hive-spi.memory.ports :reload)
    (doseq [v (protocol-vars)]
      (is (identical? (get before (str v)) @v) (str v " survived the reload")))
    (is (satisfies? ports/IMemoryStore store))
    (is (= [] (ports/query-entries store {})) "a pre-reload store still answers")))
