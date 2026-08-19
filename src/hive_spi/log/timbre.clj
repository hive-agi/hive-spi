(ns hive-spi.log.timbre
  "ILogger over `taoensso.timbre`.

   An OPTIONAL adapter: timbre is deliberately absent from this library's
   :deps, so this namespace loads only where a consumer already carries it.
   The port resolves it softly and first, which keeps timbre-formatted output
   wherever it used to appear while removing it as a load-time dependency.

   Callsite caveat: timbre attributes the event to THIS namespace, not to the
   caller, because the port's facade is functions rather than macros. Messages
   that need to name their origin carry it in the message text."
  (:require [hive-spi.log.ports :as log]
            [taoensso.timbre :as timbre]))

;; SPDX-License-Identifier: MIT

(defrecord TimbreLogger []
  log/ILogger
  (log-event [_ level message]
    (timbre/log! level :p [message])
    nil)
  (logger-levels [_] #{:debug :info :warn :error}))

(defn default-logger
  "An ILogger backed by timbre."
  []
  (->TimbreLogger))

(defn install!
  "Install the timbre logger as the explicitly active one. Returns it."
  []
  (log/set-logger! (default-logger)))
