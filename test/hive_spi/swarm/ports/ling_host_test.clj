(ns hive-spi.swarm.ports.ling-host-test
  "The ling-host slot: the Noop answers a never-ready readiness and no
   catchup; an installed stub answers for itself; clearing falls back."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [malli.core :as m]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.ports.ling-host :as lh]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [prior (when (lh/ling-host-set?) (lh/get-ling-host))]
      (lh/clear-ling-host!)
      (try (f) (finally (lh/clear-ling-host!) (when prior (lh/set-ling-host! prior)))))))

(def ^:private stub-host
  (reify
    lh/ILingReadiness
    (wait-for-ling-ready [_ agent-id spawn-mode]
      {:ready? (= :headless spawn-mode) :phase :polled :elapsed-ms 5 :attempts 1
       :slave {:slave/id agent-id}})
    lh/ILingCatchup
    (ling-catchup [_ {:keys [task]}] (when task (str "context for " task)))))

(defn- readings [host]
  {:ready-headless (lh/wait-for-ling-ready host "ling-1" :headless)
   :ready-vterm    (lh/wait-for-ling-ready host "ling-1" :vterm)
   :catchup        [(lh/ling-catchup host {:task "fix"}) (lh/ling-catchup host {})]})

(deftest-golden ling-host-slot-golden
  "test/golden/hive_spi/swarm/ports/ling_host/slot.edn"
  (let [noop-view {:set? (lh/ling-host-set?) :readings (readings (lh/get-ling-host))}
        installed (identical? stub-host (lh/set-ling-host! stub-host))
        stub-view {:set? (lh/ling-host-set?) :readings (readings (lh/get-ling-host))}]
    {:noop noop-view :installed-returns-impl? installed :stub stub-view
     :cleared [(lh/clear-ling-host!) (lh/ling-host-set?)]}))

(deftest every-readiness-answer-has-the-documented-keys-test
  (is (m/validate lh/Readiness (lh/wait-for-ling-ready lh/noop "x" :claude)))
  (is (m/validate lh/Readiness (lh/wait-for-ling-ready stub-host "x" :claude))))
