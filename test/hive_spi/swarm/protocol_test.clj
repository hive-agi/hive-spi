(ns hive-spi.swarm.protocol-test
  "The ISwarmRegistry wrappers delegate to whatever backend is passed in.
   They are driven here through an in-memory stub that is faithful to the
   protocol docstrings (string ids, :slave/* and :task/* rows, a transaction
   report on success, nil when the row is absent), so the wrappers' contracts
   are checked against the documented shapes rather than one backend's."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.protocol :as proto]))

;; SPDX-License-Identifier: MIT

(defrecord MemoryRegistry [state]
  proto/ISwarmRegistry
  (add-slave! [_ slave-id opts]
    (when (get-in @state [:slaves slave-id])
      (throw (ex-info "slave exists" {:slave-id slave-id})))
    (swap! state assoc-in [:slaves slave-id]
           {:slave/id slave-id
            :slave/name (:name opts slave-id)
            :slave/status (:status opts :idle)
            :slave/depth (:depth opts 1)
            :slave/project-id (:project-id opts)})
    {:tempids {slave-id 1}})
  (get-slave [_ slave-id] (get-in @state [:slaves slave-id]))
  (update-slave! [_ slave-id updates]
    (when (get-in @state [:slaves slave-id])
      (swap! state update-in [:slaves slave-id] merge updates)
      {:tempids {}}))
  (remove-slave! [_ slave-id]
    (when (get-in @state [:slaves slave-id])
      (swap! state update :slaves dissoc slave-id)
      {:tempids {}}))
  (get-all-slaves [_] (vec (sort-by :slave/id (vals (:slaves @state)))))
  (get-slaves-by-status [this status]
    (filterv #(= status (:slave/status %)) (proto/get-all-slaves this)))
  (get-slaves-by-project [this project-id]
    (filterv #(= project-id (:slave/project-id %)) (proto/get-all-slaves this)))
  (add-task! [_ task-id slave-id opts]
    (when-not (get-in @state [:slaves slave-id])
      (throw (ex-info "no such slave" {:slave-id slave-id})))
    (let [id (or task-id (str "task-" (count (:tasks @state))))]
      (swap! state assoc-in [:tasks id]
             {:task/id id :task/slave slave-id
              :task/status (:status opts :dispatched) :task/prompt (:prompt opts)})
      {:tempids {id 2}}))
  (get-task [_ task-id] (get-in @state [:tasks task-id]))
  (update-task! [_ task-id updates]
    (when (get-in @state [:tasks task-id])
      (swap! state update-in [:tasks task-id] merge updates)
      {:tempids {}}))
  (get-tasks-for-slave [_ slave-id]
    (->> (vals (:tasks @state)) (filter #(= slave-id (:task/slave %)))
         (sort-by :task/id) vec))
  (get-tasks-for-slave [this slave-id status]
    (filterv #(= status (:task/status %)) (proto/get-tasks-for-slave this slave-id))))

(defn- fresh [] (->MemoryRegistry (atom {})))

(deftest-golden registry-wrappers-golden
  "test/golden/hive_spi/swarm/protocol/registry-wrappers.edn"
  (let [r (fresh)]
    {:add        [(proto/add-slave!* r "ling-1" {:name "Worker" :project-id "hive"})
                  (proto/add-slave!* r "ling-2" {:status :working :project-id "other"})]
     :get        [(proto/get-slave* r "ling-1") (proto/get-slave* r "ghost")]
     :update     [(proto/update-slave!* r "ling-1" {:slave/status :working})
                  (proto/update-slave!* r "ghost" {:slave/status :working})]
     :by-status  (mapv :slave/id (proto/get-slaves-by-status* r :working))
     :by-project (mapv :slave/id (proto/get-slaves-by-project* r "hive"))
     :tasks      [(proto/add-task!* r "t-1" "ling-1" {:prompt "do it"})
                  (proto/add-task!* r nil "ling-1" {:status :queued})
                  (proto/update-task!* r "t-1" {:task/status :completed})
                  (proto/get-task* r "t-1")
                  (mapv :task/id (proto/get-tasks-for-slave* r "ling-1"))
                  (mapv :task/id (proto/get-tasks-for-slave* r "ling-1" :queued))]
     :remove     [(proto/remove-slave!* r "ling-2") (proto/remove-slave!* r "ling-2")]
     :all        (mapv :slave/id (proto/get-all-slaves* r))}))

(deftest the-wrappers-pass-backend-failures-through-test
  (let [r (fresh)]
    (proto/add-slave!* r "ling-1" {})
    (testing "a duplicate slave and a task for an absent slave throw, as documented"
      (is (thrown? clojure.lang.ExceptionInfo (proto/add-slave!* r "ling-1" {})))
      (is (thrown? clojure.lang.ExceptionInfo (proto/add-task!* r "t" "ghost" {}))))))

(deftest the-status-constants-name-the-lifecycles-test
  (is (contains? proto/slave-statuses :idle))
  (is (contains? proto/task-statuses :dispatched)))
