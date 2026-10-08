(ns hive-spi.native.ports
  "Portable operation-and-JSON native seam; hosts implement transport, not addon policy."
  (:require [hive-spi.native.schema :as schema]))

;; SPDX-License-Identifier: MIT

(def capability :native/loader)

(defonce ^:private -native-port-defined? (atom false))

(when (compare-and-set! -native-port-defined? false true)
  (defprotocol INativePort
    "One opened library speaking hive-cabi/v1."
    (library [p] "Logical library name used as the tool prefix.")
    (host [p] "Transport keyword used to open this library.")
    (call-op [p op request] "Call an operation with a request map; return an envelope, never throw.")
    (close! [p] "Release resources idempotently; return nil.")))

(defonce ^:private -native-loader-defined? (atom false))

(when (compare-and-set! -native-loader-defined? false true)
  (defprotocol INativeLoader
    "Host-specific factory for native ports."
    (transports [l] "Set of transport keywords usable on this host.")
    (open-port [l library-spec] "Return an INativePort; never throw, including when unavailable.")))

(defn ok?
  "True only for a successful native envelope."
  [envelope]
  (= true (:ok envelope)))

(defn failure
  "Build a failure envelope; optional error type identifies a host failure."
  ([message] {:ok false :error message})
  ([message error-type] {:ok false :error message :error/type error-type}))

(defn tool-name
  "Project a library and native op name into an MCP tool name: \"<library>_<op>\",
   every char outside [A-Za-z0-9_-] replaced by _, truncated to 64 chars."
  [library op]
  (apply str (take 64 (map #(if (schema/tool-char? %) % \_) (str library "_" op)))))

(defn unavailable-port
  "Return a safe, inert port when a library cannot be opened. Calls never throw."
  ([library-name] (unavailable-port library-name "Native library unavailable"))
  ([library-name message]
   (reify INativePort
     (library [_] library-name)
     (host [_] :native/unavailable)
     (call-op [_ _op _request] (failure message :native/unavailable))
     (close! [_] nil))))
