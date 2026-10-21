(ns hive-spi.log.timbre-test
  "The optional timbre adapter: loadable only where timbre is (it is in the
   :test alias), and then the port's preferred host default."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.log.ports :as log]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [installed (when (log/logger-set?) (log/get-logger))]
      (try (f)
           (finally (log/clear-logger!)
                    (when installed (log/set-logger! installed)))))))

(hst/deftrifecta-from-schema default-logger-emits-every-level
  hive-spi.log.timbre/default-logger
  {:in  [:cat]
   :out log/Logger
   :rel (fn [_ l] (= #{:debug :info :warn :error} (log/logger-levels l)))})

(hst/deftrifecta-from-schema install-makes-the-timbre-logger-active
  hive-spi.log.timbre/install!
  {:in  [:cat]
   :out log/Logger
   :rel (fn [_ l] (and (log/logger-set?) (identical? l (log/get-logger))))})
