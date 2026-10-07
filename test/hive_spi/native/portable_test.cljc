(ns hive-spi.native.portable-test
  "Cross-platform port and registry behavior with injected stub implementations."
  (:require [clojure.test :refer [deftest is]]
            [hive-spi.native.ports :as ports]
            [hive-spi.native.schema :as schema]
            [hive-spi.native.registry :as registry]
            [hive-spi.native.conformance :as conf]))

;; SPDX-License-Identifier: MIT

(deftest unavailable-port-law
  (let [p (ports/unavailable-port "absent" "not installed")
        envelope (ports/call-op p "ops" {})]
    (is (= "absent" (ports/library p)))
    (is (= :native/unavailable (ports/host p)))
    (is (= {:ok false :error "not installed" :error/type :native/unavailable} envelope))
    (is (schema/valid? schema/Envelope envelope))
    (is (nil? (ports/close! p)))))

(deftest registry-only-current-loader-law
  (let [a (reify ports/INativeLoader
            (transports [_] #{:subprocess})
            (open-port [_ spec] (ports/unavailable-port (:native/library spec))))
        b (reify ports/INativeLoader
            (transports [_] #{:node/wasm})
            (open-port [_ spec] (ports/unavailable-port (:native/library spec))))]
    (try
      (registry/register-loader! a)
      (registry/unregister-loader! b)
      (is (identical? a (registry/loader)))
      (registry/register-loader! b)
      (registry/unregister-loader! a)
      (is (identical? b (registry/loader)))
      (finally (registry/unregister-loader! b)))))

(deftest stub-port-observations
  (let [p (reify ports/INativePort
            (library [_] "stub")
            (host [_] :subprocess)
            (call-op [_ _ request] {:ok true :value request})
            (close! [_] nil))
        expected [{:op "echo" :request {:x 1} :expected {:ok true :value {:x 1}}}]
        observed (conf/observations p expected)]
    (is (conf/conformant? observed))
    (is (= expected (mapv #(select-keys % [:op :request :expected]) observed)))))
