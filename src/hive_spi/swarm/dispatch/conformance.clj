(ns hive-spi.swarm.dispatch.conformance
  "Conformance suite for ITaskDispatcher. It ships in src so an implementation
   outside this repo can put its dispatcher through it.

   `cases` is a vector of {:id :doc :run}; each :run is a (fn [harness])
   asserting with clojure.test/is. MAKE-HARNESS is a zero-arg fn returning a
   FRESH harness {:dispatcher impl} for each case. Two ways to run it:

     (defconformance my-dispatcher make-harness)
       one deftest per case, named my-dispatcher-<case-id>.

     (run-conformance make-harness)
       runs every case in the current clojure.test context, answers the ids."
  (:require [clojure.test :as t :refer [is testing]]
            [hive-dsl.result :as r]
            [hive-spi.swarm.dispatch :as d]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defn- task
  ([] (task "task-1"))
  ([id] {:task-id id :title "conformance task"}))

(def cases
  "Every conformance case, in run order."
  [{:id :dispatch-answers-agent-and-run
    :doc "dispatch-task! of a valid task answers ok {:agent-id string :run-id any}."
    :run (fn [{:keys [dispatcher]}]
           (let [res (d/dispatch-task! dispatcher (task) {})]
             (is (r/ok? res) (pr-str res))
             (is (d/valid-dispatched? (:ok res)) (pr-str res))))}

   {:id :dispatch-echoes-run-id
    :doc "A :run-id in OPTS is the :run-id of the answer, so completions land on the wave channel the caller listens on."
    :run (fn [{:keys [dispatcher]}]
           (let [res (d/dispatch-task! dispatcher (task) {:run-id "run-xyz"})]
             (is (r/ok? res) (pr-str res))
             (is (= "run-xyz" (get-in res [:ok :run-id])))))}

   {:id :invalid-task-is-addressing-err
    :doc "A task with no :task-id is an err of kind :addressing, so a chain never falls back on it."
    :run (fn [{:keys [dispatcher]}]
           (doseq [bad [{} {:title "x"} {:task-id ""} {:task-id 7} nil]]
             (let [res (d/dispatch-task! dispatcher bad {})]
               (is (r/err? res) (pr-str bad))
               (is (= :addressing (:error/kind res)) (pr-str res)))))}

   {:id :capabilities-is-a-keyword-set
    :doc "capabilities answers a set of keywords."
    :run (fn [{:keys [dispatcher]}]
           (let [caps (d/capabilities dispatcher)]
             (is (set? caps))
             (is (every? keyword? caps))))}

   {:id :distinct-dispatches-distinct-agents
    :doc "Two dispatches of different tasks never answer the same agent-id."
    :run (fn [{:keys [dispatcher]}]
           (let [a (d/dispatch-task! dispatcher (task "task-a") {})
                 b (d/dispatch-task! dispatcher (task "task-b") {})]
             (is (and (r/ok? a) (r/ok? b)))
             (is (not= (get-in a [:ok :agent-id]) (get-in b [:ok :agent-id])))))}

   {:id :unknown-opts-are-ignored
    :doc "Keys in OPTS the dispatcher does not know change nothing."
    :run (fn [{:keys [dispatcher]}]
           (let [res (d/dispatch-task! dispatcher (task) {:never-heard-of-it 1})]
             (is (r/ok? res) (pr-str res))))}])

(def case-ids
  "The case ids, in run order."
  (mapv :id cases))

(defn run-case
  "Run case ID against a fresh (MAKE-HARNESS) in the current clojure.test
   context. Answers ID."
  [make-harness id]
  (let [{:keys [run doc]} (or (some #(when (= id (:id %)) %) cases)
                              (throw (ex-info "unknown conformance case"
                                              {:id id :known case-ids})))]
    (testing (str (name id) ": " doc)
      (run (make-harness)))
    id))

(defn run-conformance
  "Run every case against fresh harnesses. Answers the ids that ran."
  [make-harness]
  (mapv #(run-case make-harness %) case-ids))

(def MakeHarness
  "A zero-arg fn returning a fresh harness. Generates one over the stub."
  [:fn {:error/message "a zero-arg harness constructor"
        :gen/schema    [:enum :stub]
        :gen/fmap      (fn [_] (requiring-resolve 'hive-spi.swarm.dispatch.stub/harness))}
   ifn?])

(def CaseId (into [:enum] case-ids))

(m/=> run-case [:=> [:cat MakeHarness CaseId] CaseId])
(m/=> run-conformance [:=> [:cat MakeHarness] [:vector CaseId]])

(defmacro defconformance
  "Emit one deftest per case, named PREFIX-<case-id>, each running that case
   against a fresh (MAKE-HARNESS)."
  [prefix make-harness]
  `(do ~@(for [id case-ids]
           `(t/deftest ~(symbol (str (name prefix) "-" (name id)))
              (run-case ~make-harness ~id)))))
