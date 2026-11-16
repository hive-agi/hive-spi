(ns hive-spi.native.ports-test
  "Native envelope helpers and tool-name projection, driven by the contracts."
  (:require [hive-schemas.test :as hst]
            [hive-spi.native.ports :as ports]
            [hive-spi.native.schema :as schema]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(hst/deftrifecta-from-schema tool-name-is-a-valid-mcp-name
  hive-spi.native.ports/tool-name
  {:in  [:cat [:string {:min 1 :max 40}] [:string {:min 0 :max 40}]]
   :out schema/ToolName
   :rel (fn [[l op] out]
          (and (<= (count out) 64)
               (= (count out) (min 64 (+ 1 (count l) (count op))))))})

(hst/deftrifecta-from-schema failure-is-never-ok
  hive-spi.native.ports/failure
  {:in  [:cat :string schema/ErrorType]
   :out schema/Envelope
   :rel (fn [[msg t] out] (and (not (ports/ok? out)) (= msg (:error out)) (= t (:error/type out))))})

(deftest-golden envelopes-and-names
  "test/golden/hive_spi/native/ports.edn"
  (let [p (ports/unavailable-port "craft")]
    {:ok?   (mapv ports/ok? [{:ok true :value 1} {:ok false} {:ok "true"} nil])
     :names (mapv #(apply ports/tool-name %) [["craft" "ops"] ["craft" "a.b c"] ["x" ""]])
     :port  [(ports/library p) (ports/host p) (ports/call-op p "ops" {}) (ports/close! p)]}))
