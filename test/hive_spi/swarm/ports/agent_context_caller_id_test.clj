(ns hive-spi.swarm.ports.agent-context-caller-id-test
  "ICallerIdContext is an OPTIONAL extension of the agent-context port:
   `current-caller-id` answers the installed host's caller id, and nil for the
   Noop, for a host without the extension, and for a host whose lookup throws."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-spi.swarm.ports.agent-context :as port]))

;; SPDX-License-Identifier: MIT

(defn- restoring-the-slot [f]
  (let [found (when (port/agent-context-set?) (port/get-agent-context))]
    (port/clear-agent-context!)
    (try
      (f)
      (finally
        (if found
          (port/set-agent-context! found)
          (port/clear-agent-context!))))))

(use-fixtures :each restoring-the-slot)

(defn- context-only-port []
  (reify port/IAgentCallContext
    (current-agent-id [_] "agent-1")
    (current-directory [_] "/p")))

(defn- caller-port [caller-id-fn]
  (reify
    port/IAgentCallContext
    (current-agent-id [_] "agent-1")
    (current-directory [_] "/p")
    port/ICallerIdContext
    (-current-caller-id [_] (caller-id-fn))))

(deftest with-nothing-installed-there-is-no-caller
  (is (not (port/agent-context-set?)))
  (is (nil? (port/current-caller-id))))

(deftest the-noop-implements-the-extension-and-answers-nil
  (is (satisfies? port/ICallerIdContext port/noop))
  (is (nil? (port/-current-caller-id port/noop))))

(deftest a-host-without-the-extension-answers-nil
  (port/set-agent-context! (context-only-port))
  (testing "the base readers still work"
    (is (= "agent-1" (port/current-agent-id (port/get-agent-context)))))
  (is (nil? (port/current-caller-id))))

(deftest a-host-with-the-extension-answers-its-caller-id
  (port/set-agent-context! (caller-port (constantly "swarm-ling-7")))
  (is (= "swarm-ling-7" (port/current-caller-id))))

(deftest the-caller-id-is-read-per-call-not-captured-at-install
  (let [caller (atom "first")]
    (port/set-agent-context! (caller-port #(deref caller)))
    (is (= "first" (port/current-caller-id)))
    (reset! caller "second")
    (is (= "second" (port/current-caller-id)))
    (reset! caller nil)
    (is (nil? (port/current-caller-id)))))

(deftest a-host-whose-lookup-throws-degrades-to-no-caller
  (port/set-agent-context! (caller-port #(throw (ex-info "host down" {}))))
  (is (nil? (port/current-caller-id))))

(deftest clearing-the-context-drops-the-caller
  (port/set-agent-context! (caller-port (constantly "c")))
  (port/clear-agent-context!)
  (is (nil? (port/current-caller-id))))
