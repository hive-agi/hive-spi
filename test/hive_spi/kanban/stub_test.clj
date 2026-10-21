(ns hive-spi.kanban.stub-test
  "The recording stub: seeded board, every call recorded, and faithful to
   the kanban conformance cases."
  (:require [clojure.test :refer [deftest is]]
            [hive-schemas.test :as hst]
            [hive-spi.kanban :as k]
            [hive-spi.kanban.stub :as stub]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema recording-kanban-seeds-its-board
  hive-spi.kanban.stub/recording-kanban
  {:in  [:cat [:vector {:max 4} k/Task]]
   :out stub/Stub
   :rel (fn [[tasks] s] (and (= (into {} (map (juxt :id identity)) tasks) (stub/board s))
                             (= [] (stub/calls s))))})

(deftest the-stub-passes-conformance
  (let [s (stub/recording-kanban [{:id "a" :title "A" :status "todo"}])]
    (is (every? :ok? (k/conformance s s)))))

(deftest-golden recorded-calls
  "test/golden/hive_spi/kanban/stub.edn"
  (let [s (stub/recording-kanban [{:id "b" :status "done"} {:id "a" :status "todo"}])]
    (k/list-tasks s {:status :todo})
    (k/get-task s "a")
    (k/create-task! s {:title ""})
    (k/create-task! s {:title "c" :priority "high"})
    {:calls (stub/calls s) :board (into (sorted-map) (stub/board s))}))
