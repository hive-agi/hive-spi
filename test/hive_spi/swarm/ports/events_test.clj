(ns hive-spi.swarm.ports.events-test
  "The events slot: the all-Noop degrades every port silently; an installed
   recording stub (implementing every protocol, as a host adapter does)
   answers for itself; clearing falls back."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.ports.events :as ev]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [prior (when (ev/events-set?) (ev/get-events))]
      (ev/clear-events!)
      (try (f) (finally (ev/clear-events!) (when prior (ev/set-events! prior)))))))

(defn- recording-events [log]
  (reify
    ev/IEventDispatch
    (dispatch! [_ event-v] (swap! log conj [:dispatch event-v]) event-v)
    (handler-registered? [_ event-id] (= :ling/spawned event-id))
    ev/IAgentEventPublisher
    (publish-event! [_ p] (swap! log conj [:event (:type p)]) nil)
    (publish-shout! [_ p] (swap! log conj [:shout (:event-type p)]) nil)
    ev/IHookTrigger
    (trigger-hooks [_ event _ctx] [{:result event}])
    ev/ISwarmTelemetry
    (set-lings-active! [_ n] (swap! log conj [:gauge n]) nil)
    ev/IDagWaveScheduler
    (start-dag! [_ plan-id _] {:active true :plan-id plan-id})
    (stop-dag! [_] {:stopped true :plan-id "p" :completed-count 0})
    (dag-status [_] {:active true :plan-id "p"})
    ev/IAgentEventBroadcaster
    (emit-agent-event! [_ t _] (swap! log conj [:emit t]) nil)
    ev/IVesselContextSource
    (resolve-agent-context [_ id] (when (= "ling-1" id) {:project-id "hive" :cwd "/w" :session-id "s"}))))

(defn- readings [p]
  {:dispatch   (ev/dispatch! p [:ling/spawned {:id 1}])
   :registered [(ev/handler-registered? p :ling/spawned) (ev/handler-registered? p :other)]
   :publish    [(ev/publish-event! p {:type :agent-spawn}) (ev/publish-shout! p {:event-type :progress})]
   :hooks      (ev/trigger-hooks p :task-complete {})
   :gauge      (ev/set-lings-active! p 3)
   :dag        [(ev/start-dag! p "plan-1" {}) (ev/dag-status p) (ev/stop-dag! p)]
   :emit       (ev/emit-agent-event! p :agent-spawned {})
   :vessel     [(ev/resolve-agent-context p "ling-1") (ev/resolve-agent-context p "ghost")]})

(deftest-golden events-slot-golden
  "test/golden/hive_spi/swarm/ports/events/slot.edn"
  (let [log       (atom [])
        noop-view {:set? (ev/events-set?) :readings (readings (ev/get-events))}
        stub      (recording-events log)
        installed (identical? stub (ev/set-events! stub))
        stub-view {:set? (ev/events-set?) :readings (readings (ev/get-events)) :log @log}]
    {:noop noop-view :installed-returns-impl? installed :stub stub-view
     :cleared [(ev/clear-events!) (ev/events-set?) (identical? ev/noop (ev/get-events))]}))

(deftest a-value-that-is-not-an-event-port-is-refused-test
  ;; The slot's :validate refuses (AssertionError), or the contract first.
  (is (thrown? Throwable (ev/set-events! :nope))))
