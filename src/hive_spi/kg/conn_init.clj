(ns hive-spi.kg.conn-init
  "SPI: single-init capability — at-most-once eval of open-fn under concurrent
   contention (LMDB open is non-idempotent). Leaf — no deps.")
;; SPDX-License-Identifier: MIT

(defprotocol IConnInit
  "At-most-once open under concurrency."
  (open-once! [this open-fn]
    "Run open-fn once; return its result. Subsequent calls return the cached value.")
  (snapshot [this] "Read-only view of the cached value, or nil when uninitialized.")
  (clear! [this] "Reset to uninitialized. Caller closes the previously cached value."))

(defn atom-conn-init
  "Default IConnInit backed by an atom + JVM monitor (double-checked locking)."
  ([] (atom-conn-init (atom nil)))
  ([state-atom]
   (reify IConnInit
     (open-once! [_ open-fn]
       (or @state-atom
           (locking state-atom
             (or @state-atom
                 (let [v (open-fn)]
                   (reset! state-atom v)
                   v)))))
     (snapshot [_] @state-atom)
     (clear! [_] (reset! state-atom nil)))))
