(ns hive-spi.swarm.spawn-store-test
  "The spawn-store slot: empty until the host installs a store, validated on
   install, cleared back to nil. Driven with a recording stub store."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.spawn-store :as ss]))

;; SPDX-License-Identifier: MIT

(defrecord RecordingStore [log]
  ss/ISpawnStore
  (add-slave! [_ slave-id attrs] (swap! log conj [:add slave-id attrs]) nil)
  (remove-slave! [_ slave-id] (swap! log conj [:remove slave-id]) nil)
  (update-slave! [_ slave-id updates] (swap! log conj [:update slave-id updates]) nil)
  (claims-for-slave [_ slave-id] (if (= "ling-1" slave-id) ["src/a.clj"] [])))

(use-fixtures :each
  (fn [f]
    (let [prior (ss/get-store)]
      (ss/clear-store!)
      (try (f) (finally (ss/clear-store!) (when prior (ss/set-store! prior)))))))

(deftest-golden spawn-store-slot-golden
  "test/golden/hive_spi/swarm/spawn_store/slot.edn"
  (let [log   (atom [])
        store (->RecordingStore log)
        empty (ss/get-store)
        same? (identical? store (ss/set-store! store))]
    (ss/add-slave! (ss/get-store) "ling-1" {:cwd "/tmp"})
    (ss/update-slave! (ss/get-store) "ling-1" {:status :working})
    (let [claims [(ss/claims-for-slave (ss/get-store) "ling-1")
                  (ss/claims-for-slave (ss/get-store) "ling-2")]]
      (ss/remove-slave! (ss/get-store) "ling-1")
      {:empty-before empty
       :install-returns-store? same?
       :claims claims
       :calls @log
       :cleared [(ss/clear-store!) (ss/get-store)]})))

(deftest a-value-that-is-not-a-store-is-refused-test
  ;; Refused by the slot's :validate (AssertionError), or first by the
  ;; set-store! contract when it is instrumented.
  (is (thrown? Throwable (ss/set-store! {:not "a store"})))
  (is (nil? (ss/get-store))))
