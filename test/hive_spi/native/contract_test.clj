(ns hive-spi.native.contract-test
  "Schema-generated trifectas and stubbed laws of the portable native seam."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-schemas.test :as hst]
            [hive-spi.native.schema :as schema]
            [hive-spi.native.ports :as ports]
            [hive-spi.native.registry :as registry]
            [hive-spi.native.conformance :as conf]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema library-spec
  hive-spi.native.schema/->library-spec
  {:in schema/LibrarySpec
   :out schema/LibrarySpec
   :rel (fn [input output] (= input output))
   :mutation true})

(hst/deftrifecta-from-schema envelope
  hive-spi.native.schema/->envelope
  {:in schema/Envelope
   :out schema/Envelope
   :rel (fn [input output] (= input output))
   :mutation true})

(defn- tool-name-of [[library op]] (ports/tool-name library op))

(hst/deftrifecta-from-schema tool-name
  hive-spi.native.contract-test/tool-name-of
  {:in [:tuple :string :string]
   :out schema/ToolName
   :rel (fn [[library op] output]
          (and (<= (count output) 64)
               (= (count output) (min 64 (+ 1 (count library) (count op))))))
   :mutation true})

(deftest tool-name-projects-dotted-craft-ops
  (is (= "photocraft_engine_execute" (ports/tool-name "photocraft" "engine.execute")))
  (is (= "vectorcraft_ui_tool_list" (ports/tool-name "vectorcraft" "ui.tool.list")))
  (is (= "autopdf_audit" (ports/tool-name "autopdf" "audit")))
  (is (schema/valid? schema/ToolName (ports/tool-name "x" (apply str (repeat 100 \.))))))

(deftest unavailable-port-law
  (let [p (ports/unavailable-port "missing" "mount hive-native")
        result (ports/call-op p "ops" {})]
    (is (= "missing" (ports/library p)))
    (is (= :native/unavailable (ports/host p)))
    (is (= {:ok false :error "mount hive-native" :error/type :native/unavailable} result))
    (is (schema/valid? schema/Envelope result))
    (is (false? (ports/ok? result)))
    (is (nil? (ports/close! p)))
    (is (nil? (ports/close! p)))))

(defn- stub-loader [id]
  (reify ports/INativeLoader
    (transports [_] #{:subprocess})
    (open-port [_ spec] (ports/unavailable-port (:native/library spec) (str id)))))

(deftest loader-registry-only-own-law
  (let [first-loader (stub-loader "first")
        second-loader (stub-loader "second")]
    (try
      (is (nil? (registry/loader)))
      (is (identical? first-loader (registry/register-loader! first-loader)))
      (is (identical? first-loader (registry/loader)))
      (is (thrown? AssertionError (registry/register-loader! :not-a-loader)))
      (registry/unregister-loader! second-loader)
      (is (identical? first-loader (registry/loader)))
      (registry/unregister-loader! first-loader)
      (is (nil? (registry/loader)))
      (registry/register-loader! second-loader)
      (is (identical? second-loader (registry/loader)))
      (finally (registry/unregister-loader! first-loader)
               (registry/unregister-loader! second-loader)))))

(deftest portable-stub-conformance
  (let [closed (atom 0)
        port (reify ports/INativePort
               (library [_] "stub")
               (host [_] :subprocess)
               (call-op [_ op request]
                 (case op
                   "echo" {:ok true :value request}
                   "fail" (ports/failure "bad" :native/failed)
                   "throw" (throw (ex-info "transport failed" {}))))
               (close! [_] (swap! closed inc) nil))
        expected [{:op "echo" :request {:x 2} :expected {:ok true :value {:x 2}}}
                  {:op "fail" :request {} :expected {:ok false :error "bad" :error/type :native/failed}}]
        observed (conf/observations port expected)]
    (is (conf/conformant? observed))
    (is (= 2 (count observed)))
    (is (every? :valid-envelope? observed))
    (is (false? (conf/conformant? (conf/observations port
                                   [{:op "echo" :request {} :expected {:ok true :value 4}}]))))
    (is (= :native/failed (:error/type (:actual (conf/observe port {:op "throw" :request {}})))))
    (is (= 0 @closed) "observing does not take ownership of a port")))
