(ns hive-spi.addon.headless-test
  "These contracts decide which backend a host will dispatch to, so a
   capability predicate that answers wrongly silently routes work to a
   backend that cannot do it."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.addon.headless :as headless]))

;; =============================================================================
;; Doubles
;; =============================================================================

(defrecord PlainBackend []
  headless/IHeadlessBackend
  (headless-id [_] :plain)
  (headless-spawn! [_ _ _] "slave-1")
  (headless-dispatch! [_ _ _] true)
  (headless-status [_ _ _] {:slave/id "slave-1" :slave/status :running})
  (headless-kill! [_ _] {:killed? true :id "slave-1"})
  (headless-interrupt! [_ _] {:success? false :ling-id "slave-1"
                              :reason :not-supported}))

(defrecord CapableBackend []
  headless/IHeadlessBackend
  (headless-id [_] :capable)
  (headless-spawn! [_ _ _] "slave-2")
  (headless-dispatch! [_ _ _] true)
  (headless-status [_ _ _] nil)
  (headless-kill! [_ _] {:killed? true :id "slave-2"})
  (headless-interrupt! [_ _] {:success? true :ling-id "slave-2"})

  headless/IHeadlessCapabilities
  (declared-capabilities [_] #{:cap/hooks :cap/interrupts}))

(defprotocol StubAddon (addon-id [this]))

(defrecord AddonBackend []
  StubAddon
  (addon-id [_] "stub")
  headless/IHeadlessBackend
  (headless-id [_] :addon)
  (headless-spawn! [_ _ _] "slave-3")
  (headless-dispatch! [_ _ _] true)
  (headless-status [_ _ _] nil)
  (headless-kill! [_ _] {:killed? true :id "slave-3"})
  (headless-interrupt! [_ _] {:success? false :reason :not-supported}))

;; =============================================================================
;; Headless backend predicates
;; =============================================================================

(deftest headless-backend-recognises-only-implementations-test
  (is (true? (headless/headless-backend? (->PlainBackend))))
  (is (false? (headless/headless-backend? {:id :nope})))
  (is (false? (headless/headless-backend? nil))))

(deftest capabilities-fall-back-when-undeclared-test
  (is (= headless/default-capabilities (headless/capabilities (->PlainBackend))))
  (is (= #{:cap/hooks :cap/interrupts} (headless/capabilities (->CapableBackend)))))

(deftest default-capabilities-are-the-basic-pair-test
  (is (= #{:cap/streaming :cap/multi-turn} headless/default-capabilities)))

(deftest headless-addon-takes-the-host-protocol-as-a-parameter-test
  (testing "the contract stays a leaf — the caller supplies its addon protocol"
    (is (true? (headless/headless-addon? StubAddon (->AddonBackend))))
    (is (false? (headless/headless-addon? StubAddon (->PlainBackend)))
        "a backend that is not an addon must not pass")))

(deftest an-interrupt-may-be-declined-test
  (is (= :not-supported (:reason (headless/headless-interrupt! (->PlainBackend) {:id "x"})))
      "backends without interrupt support answer, they do not throw"))
