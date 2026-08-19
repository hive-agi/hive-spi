(ns hive-spi.log.console
  "ILogger over `println` to `*err*` — the fallback that needs no vendor and
   no host beyond a writer, so a diagnostic still reaches an operator on a
   runtime where no logging library loads.

   Renders one line per event: `LEVEL message`, level upper-cased."
  (:require [clojure.string :as str]
            [hive-spi.log.ports :as log]))

;; SPDX-License-Identifier: MIT

(defrecord ConsoleLogger []
  log/ILogger
  (log-event [_ level message]
    (binding [*out* *err*]
      (println (str/upper-case (name level)) message))
    nil)
  (logger-levels [_] #{:debug :info :warn :error}))

(defn default-logger
  "An ILogger that prints to *err*."
  []
  (->ConsoleLogger))

(defn install!
  "Install the console logger as the explicitly active one. Returns it."
  []
  (log/set-logger! (default-logger)))
