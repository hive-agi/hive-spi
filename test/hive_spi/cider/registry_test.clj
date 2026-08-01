(ns hive-spi.cider.registry-test
  "The cider registry is the runtime seam a host uses to inject the
   ICiderPort implementation; a registry accepting a non-conforming impl or
   losing the :default fails at exactly the wrong moment."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-spi.cider.ports :as ports]
            [hive-spi.cider.registry :as reg]))

;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Double
;; =============================================================================

(defrecord FakeCiderPort [calls]
  ports/ICiderPort
  (cider-eval [_ params] (swap! calls conj [:eval params]) {:content [{:type "text" :text "ok"}] :isError false})
  (cider-doc [_ params] (swap! calls conj [:doc params]) {:content [] :isError false})
  (cider-info [_ params] (swap! calls conj [:info params]) {:content [] :isError false})
  (cider-complete [_ params] (swap! calls conj [:complete params]) {:content [] :isError false})
  (cider-apropos [_ params] (swap! calls conj [:apropos params]) {:content [] :isError false})
  (cider-status [_ params] (swap! calls conj [:status params]) {:content [] :isError false})
  (spawn-session [_ params] (swap! calls conj [:spawn params]) {:content [] :isError false})
  (connect-session [_ params] (swap! calls conj [:connect params]) {:content [] :isError false})
  (list-sessions [_ params] (swap! calls conj [:sessions params]) {:content [] :isError false})
  (kill-session [_ params] (swap! calls conj [:kill-session params]) {:content [] :isError false})
  (kill-all-sessions [_ params] (swap! calls conj [:kill-all params]) {:content [] :isError false})
  (ensure-connected [_ project-dir] (swap! calls conj [:ensure-connected project-dir]) "auto-test"))

(defn- fake-port [] (->FakeCiderPort (atom [])))

(use-fixtures :each (fn [f] (reg/reset-registry!) (f) (reg/reset-registry!)))

;; =============================================================================
;; Registry behavior
;; =============================================================================

(deftest register-and-get-default
  (let [p (fake-port)]
    (reg/set-port! p)
    (is (reg/port-set?))
    (is (identical? p (reg/get-port)))
    (is (identical? p (reg/get-port :default)))))

(deftest get-port-throws-when-absent
  (is (not (reg/port-set?)))
  (try
    (reg/get-port)
    (is false "should have thrown")
    (catch clojure.lang.ExceptionInfo e
      (is (re-find #"No default cider port" (ex-message e))))))

(deftest get-port-unknown-key-throws-with-available
  (reg/register-port! :em (fake-port))
  (try
    (reg/get-port :nope)
    (is false "should have thrown")
    (catch clojure.lang.ExceptionInfo e
      (is (= :nope (:port-key (ex-data e))))
      (is (= [:em] (:available (ex-data e)))))))

(deftest register-validates-protocol
  (try
    (reg/register-port! :bad {:not "a port"})
    (is false "should have thrown")
    (catch AssertionError _
      (is true))))

(deftest unregister-removes
  (let [p (fake-port)]
    (reg/set-port! p)
    (reg/unregister-port! :default)
    (is (not (reg/port-set?)))
    (is (empty? (reg/registered-ports)))))

(deftest methods-dispatch-through-registry
  (let [p (fake-port)]
    (reg/set-port! p)
    (ports/cider-eval (reg/get-port) {"code" "(+ 1 2)"})
    (ports/kill-session (reg/get-port) {"session_name" "x"})
    (is (= "auto-test" (ports/ensure-connected (reg/get-port) "/tmp/proj")))
    (is (= [[:eval {"code" "(+ 1 2)"}]
            [:kill-session {"session_name" "x"}]
            [:ensure-connected "/tmp/proj"]]
           @(:calls p)))))
