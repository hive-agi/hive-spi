(ns hive-spi.log.console-test
  "The console logger is the fallback that must reach an operator where no
   logging library loads: one upper-cased level, then the message, on *err*."
  (:require [clojure.string :as str]
            [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.log.console :as console]
            [hive-spi.log.ports :as log]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each
  (fn [f]
    (let [installed (when (log/logger-set?) (log/get-logger))]
      (try (f)
           (finally (log/clear-logger!)
                    (when installed (log/set-logger! installed)))))))

(hst/deftrifecta-from-schema default-logger-emits-every-level
  hive-spi.log.console/default-logger
  {:in  [:cat]
   :out log/Logger
   :rel (fn [_ l] (= #{:debug :info :warn :error} (log/logger-levels l)))})

(hst/deftrifecta-from-schema install-makes-the-console-logger-active
  hive-spi.log.console/install!
  {:in  [:cat]
   :out log/Logger
   :rel (fn [_ l] (and (log/logger-set?) (identical? l (log/get-logger))))})

(deftest-golden one-line-per-event-on-err
  "test/golden/hive_spi/log/console.edn"
  (let [l (console/default-logger)]
    (vec (for [level [:debug :info :warn :error]]
           (let [err (java.io.StringWriter.)]
             (binding [*err* err] (log/log-event l level (str "msg " (name level))))
             (str/trim (str err)))))))
