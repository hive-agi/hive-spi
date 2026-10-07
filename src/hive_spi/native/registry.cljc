(ns hive-spi.native.registry
  "Process-local injection point for the active native loader."
  (:require [hive-spi.native.ports :as ports]
            [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private loader-slot
  (slot/single-slot {:validate #(satisfies? ports/INativeLoader %)}))

(defn register-loader!
  "Install an INativeLoader and return it; rejects non-loaders."
  [l]
  (slot/install! loader-slot l))

(defn unregister-loader!
  "Clear the registry only if l is the exact currently registered loader."
  [l]
  (when (identical? l (slot/current loader-slot))
    (slot/clear! loader-slot))
  nil)

(defn loader
  "Return the current INativeLoader, or nil if none was installed."
  []
  (slot/current loader-slot))
