(ns hive-spi.swarm.ports.memory-scope-test
  "The memory-scope slot: the Noop answers \"global\" and empty staleness;
   an installed stub answers for itself; clearing falls back. The optional
   IDiscPropagation extension is covered by memory-scope-propagation-test."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.ports.memory-scope :as ms]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [prior (when (ms/memory-scope-set?) (ms/get-memory-scope))]
      (ms/clear-memory-scope!)
      (try (f) (finally (ms/clear-memory-scope!) (when prior (ms/set-memory-scope! prior)))))))

(def ^:private stub-scope
  (reify
    ms/IProjectScope
    (project-id-for-path [_ path] (if (seq path) "hive-spi" "global"))
    (infer-scope-from-path [_ path] (if (seq path) "hive" "global"))
    ms/IDiscStaleness
    (staleness-warnings [_ paths]
      (seq (for [p paths :when (= "stale.clj" p)] {:message (str p " is stale")})))
    (format-staleness-warnings [_ ws] (when (seq ws) (apply str (map :message ws))))
    (kg-first-context [_ paths]
      {:kg-known [] :needs-read (vec paths) :stale []
       :summary {:total (count paths) :known 0 :needs-read (count paths) :stale 0}})))

(defn- readings [port]
  (let [ws (ms/staleness-warnings port ["a.clj" "stale.clj"])]
    {:project-id [(ms/project-id-for-path port "/p/hive-spi") (ms/project-id-for-path port "")]
     :inferred   (ms/infer-scope-from-path port "/p/hive-spi/src")
     :warnings   ws
     :formatted  (ms/format-staleness-warnings port ws)
     :kg-first   (ms/kg-first-context port ["a.clj"])
     :changed    (when (identical? port (ms/get-memory-scope))
                   (ms/content-changed! "a.clj" :hash-mismatch))}))

(deftest-golden memory-scope-slot-golden
  "test/golden/hive_spi/swarm/ports/memory_scope/slot.edn"
  (let [noop-view {:set? (ms/memory-scope-set?) :readings (readings (ms/get-memory-scope))}
        installed (identical? stub-scope (ms/set-memory-scope! stub-scope))
        stub-view {:set? (ms/memory-scope-set?) :readings (readings (ms/get-memory-scope))}]
    {:noop noop-view :installed-returns-impl? installed :stub stub-view
     :cleared [(ms/clear-memory-scope!) (ms/memory-scope-set?)]}))

(deftest a-value-that-is-not-a-scope-port-is-refused-test
  ;; The slot's :validate refuses (AssertionError), or the contract first.
  (is (thrown? Throwable (ms/set-memory-scope! "global"))))
