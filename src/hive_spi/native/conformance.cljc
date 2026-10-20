(ns hive-spi.native.conformance
  "Portable observations for an INativePort and a sequence of expected calls."
  (:require [hive-spi.native.ports :as ports]
            [hive-spi.native.schema :as schema]))

;; SPDX-License-Identifier: MIT

(defn observe
  "Run one expectation {:op string :request map :expected envelope} against port.
   Return an observation with :actual, :valid-envelope? and :matches?; a
   throwing adapter is recorded as a failure rather than aborting the suite."
  [port {:keys [op request expected] :as expectation}]
  (let [actual (try
                 (ports/call-op port op request)
                 (catch #?(:clj Throwable :cljs :default :cljr Exception :lpy python/Exception :default :default) e
                   (ports/failure (str e) :native/failed)))]
    (assoc expectation
           :actual actual
           :valid-envelope? (schema/valid? schema/Envelope actual)
           :matches? (= expected actual))))

(defn observations
  "Run expectations in order through a port, yielding portable observations.
   Does not close the port; the caller owns its lifetime."
  [port expectations]
  (mapv #(observe port %) expectations))

(defn conformant?
  "True when every observation has a valid envelope matching its expectation."
  [results]
  (every? #(and (:valid-envelope? %) (:matches? %)) results))
