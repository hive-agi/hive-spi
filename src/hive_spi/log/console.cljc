(ns hive-spi.log.console
  "ILogger over `println` to `*err*` — the fallback that needs no vendor and
   no host beyond a writer, so a diagnostic still reaches an operator on a
   runtime where no logging library loads.

   Renders one line per event: `LEVEL message`, level upper-cased."
  (:require [clojure.string :as str]
            [hive-spi.log.ports :as log]))

;; SPDX-License-Identifier: MIT

(defn- err-println
  "Print ARGS as one `println` line on the host's error stream.

   `*err*` is a JVM-family var; ClojureScript names the same stream
   `*print-err-fn*`, and where the host installed none the line falls back to
   the standard stream rather than throwing."
  [& args]
  #?(:clj  (binding [*out* *err*]
             (apply println args))
     :cljs (if *print-err-fn*
             (binding [*print-fn* *print-err-fn*]
               (apply println args))
             (apply println args)))
  nil)

(defrecord ConsoleLogger []
  log/ILogger
  (log-event [_ level message]
    (err-println (str/upper-case (name level)) message)
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
