(ns hive-spi.swarm.dispatch.stub
  "In-memory ITaskDispatcher: records every dispatch and starts nothing.
   The reference implementation the conformance suite is proven against, and
   a stand-in for tests of a scheduler."
  (:require [hive-dsl.result :as r]
            [hive-spi.swarm.dispatch :as d]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defrecord StubDispatcher [calls caps]
  d/ITaskDispatcher
  (dispatch-task! [_ task opts]
    (if-not (d/valid-task? task)
      (d/invalid-task task)
      (let [n        (count (swap! calls conj {:task task :opts opts}))
            agent-id (str "stub-agent-" (:task-id task) "-" n)]
        (r/ok {:agent-id agent-id
               :run-id   (get opts :run-id agent-id)}))))
  (capabilities [_] caps))

(defn ->stub
  "A fresh StubDispatcher advertising CAPS (default: none)."
  ([] (->stub #{}))
  ([caps] (->StubDispatcher (atom []) (set caps))))

(defn calls
  "The [{:task :opts}] a stub has been asked to dispatch, oldest first."
  [stub]
  @(:calls stub))

(defn harness
  "A fresh conformance harness over a stub."
  []
  {:dispatcher (->stub)})

(m/=> ->stub [:function [:=> [:cat] :any] [:=> [:cat [:set :keyword]] :any]])
(m/=> calls [:=> [:cat :any] [:vector :map]])
(m/=> harness [:=> [:cat] [:map [:dispatcher :any]]])
