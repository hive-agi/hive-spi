(ns hive-spi.kanban-test
  "The kanban port: degraded answers without a provider, a recording stub
   that is proven faithful by the same conformance cases the Noop passes,
   and call-time resolution through the registry."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [malli.core :as m]
            [hive-spi.kanban :as k]
            [hive-spi.kanban.registry :as r]
            [hive-spi.kanban.stub :as stub]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each (fn [t] (r/reset-providers!) (t) (r/reset-providers!)))

(def ^:private seed
  [{:id "a" :title "Setup" :status "todo" :priority "high"}
   {:id "b" :title "Ship" :status "inprogress" :priority "medium"}])

;; =============================================================================
;; Degraded without a provider
;; =============================================================================

(deftest degrades-without-a-provider
  (testing "reads are empty, writes are an :err value, nothing throws"
    (is (= [] (r/list-tasks {:status "todo"})))
    (is (nil? (r/get-task "a")))
    (let [tr (r/transition! {:task-id "a" :new-status "done"})
          cr (r/create-task! {:title "x"})]
      (is (m/validate k/TransitionResult tr))
      (is (m/validate k/CreateResult cr))
      (is (= :kanban/provider-unavailable (get-in tr [:err :error])))
      (is (= :kanban/provider-unavailable (get-in cr [:err :error]))))))

(deftest noops-satisfy-their-protocols
  (is (satisfies? k/IKanbanRead (get r/noops :IKanbanRead)))
  (is (satisfies? k/IKanbanWrite (get r/noops :IKanbanWrite)))
  (is (= k/IKanbanRead (get r/port->protocol :IKanbanRead)))
  (is (= k/IKanbanWrite (get r/port->protocol :IKanbanWrite)))
  (is (r/noop? (r/provider :IKanbanRead))))

;; =============================================================================
;; Conformance: Noop and stub pass the same cases
;; =============================================================================

(deftest noop-passes-conformance
  (let [report (k/conformance (get r/noops :IKanbanRead) (get r/noops :IKanbanWrite))]
    (is (every? :ok? report) (pr-str (remove :ok? report)))))

(deftest recording-stub-passes-conformance
  (let [s (stub/recording-kanban seed)
        report (k/conformance s s)]
    (is (every? :ok? report) (pr-str (remove :ok? report)))
    (is (= 5 (count report)) "every case ran")))

;; =============================================================================
;; DIP: registering swaps behaviour, resolved at call time
;; =============================================================================

(deftest register-swaps-behaviour-per-role
  (let [s (stub/recording-kanban seed)]
    (r/register! :IKanbanRead s)
    (testing "read side is live, write side still degraded"
      (is (= ["a"] (mapv :id (r/list-tasks {:status "todo"}))))
      (is (= "Ship" (:title (r/get-task "b"))))
      (is (= :kanban/provider-unavailable
             (get-in (r/transition! {:task-id "a" :new-status "done"}) [:err :error]))))
    (r/register! :IKanbanWrite s)
    (testing "write side is live and visible through the read side"
      (is (= "done" (get-in (r/transition! {:task-id "a" :new-status "done"}) [:ok :status])))
      (is (= "done" (:status (r/get-task "a"))))
      (let [{:keys [ok]} (r/create-task! {:title "New" :priority "low"})]
        (is (string? (:id ok)))
        (is (= "New" (:title (r/get-task (:id ok)))))))
    (testing "the stub recorded every call, in order"
      (is (= [:list-tasks :get-task :transition! :get-task :create-task! :get-task]
             (mapv first (stub/calls s)))))))

(deftest install-covers-both-ports-and-uninstall-degrades-again
  (let [s (stub/install! (stub/recording-kanban seed))]
    (is (identical? s (r/provider :IKanbanRead)))
    (is (identical? s (r/provider :IKanbanWrite)))
    (stub/uninstall!)
    (is (not (r/registered? :IKanbanRead)))
    (is (not (r/registered? :IKanbanWrite)))
    (is (= [] (r/list-tasks {})))))

(deftest a-later-registration-wins-at-call-time
  (let [first-stub (stub/recording-kanban seed)
        second-stub (stub/recording-kanban [{:id "z" :title "Other" :status "todo"}])]
    (r/register! :IKanbanRead first-stub)
    (is (= ["a"] (mapv :id (r/list-tasks {:status "todo"}))))
    (r/register! :IKanbanRead second-stub)
    (is (= ["z"] (mapv :id (r/list-tasks {:status "todo"})))
        "the facade reads the registry on every call, never a captured impl")
    (r/unregister! :IKanbanRead)
    (is (= [] (r/list-tasks {:status "todo"})))))

(deftest register-rejects-a-non-kanban-impl
  (is (thrown? clojure.lang.ExceptionInfo (r/register! :IKanbanRead {:not "a board"})))
  (is (thrown? clojure.lang.ExceptionInfo (r/register! :IKanbanNope (stub/recording-kanban))))
  (is (thrown? clojure.lang.ExceptionInfo
               (r/register! :IKanbanWrite (reify k/IKanbanRead
                                            (list-tasks [_ _] [])
                                            (get-task [_ _] nil))))
      "a read-only impl cannot fill the write port"))

(deftest schemas-describe-the-shapes
  (is (m/validate k/Task {:id "a" :title "t" :status "todo" :priority "high" :extra 1})
      "Task is open")
  (is (not (m/validate k/Task {:id "a" :status "doing"}))
      "storage-side spellings are not the wire vocabulary")
  (is (m/validate k/ListQuery {}))
  (is (m/validate k/TransitionRequest {:task-id "a" :new-status "inreview"}))
  (is (not (m/validate k/TransitionRequest {:task-id "a" :new-status "review"})))
  (is (m/validate k/CreateRequest {:title "t"})))
