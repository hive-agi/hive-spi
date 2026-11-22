(ns hive-spi.memory.conformance.skip-test
  "A skipped conformance case is decided by a pure `skip-reason` and reported
   through hive-spi.log.ports, never printed to stdout: a host that routes its
   diagnostics through the log port sees the skip, and a test run's stdout
   stays the test runner's."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.log.ports :as log]
            [hive-spi.memory.conformance :as conf]
            [hive-spi.memory.stub :as stub]))

;; SPDX-License-Identifier: MIT

(defrecord RecordingLogger [events]
  log/ILogger
  (log-event [_ level message] (swap! events conj [level message]) nil)
  (logger-levels [_] #{:debug :info :warn :error}))

(defn- restore-logger [f]
  (let [installed (when (log/logger-set?) (log/get-logger))]
    (try (f)
         (finally
           (log/clear-logger!)
           (when installed (log/set-logger! installed))))))

(use-fixtures :each restore-logger)

(hst/deftrifecta-from-schema skip-reason-prefers-the-explicit-skip
  hive-spi.memory.conformance/skip-reason
  {:in  [:cat [:map [:id [:enum :disconnect-then-connect :seal-ready]]
                    [:requires-opt {:optional true} [:enum :connect-config :seal-ready?]]]
              [:maybe [:map
                       [:skip {:optional true} [:map-of [:enum :disconnect-then-connect :seal-ready] [:string {:min 1 :max 6}]]]
                       [:connect-config {:optional true} :map]
                       [:seal-ready? {:optional true} :boolean]]]]
   :out [:maybe :string]
   :rel (fn [[{:keys [id requires-opt]} opts] out]
          (cond
            (get-in opts [:skip id]) (= out (get-in opts [:skip id]))
            (and requires-opt (not (contains? opts requires-opt)))
            (= out (str "opts lack " requires-opt))
            :else (nil? out)))
   :classify (fn [[{:keys [id]} opts] out]
               (cond (nil? out) :runs
                     (get-in opts [:skip id]) :explicit
                     :else :missing-opt))
   :classify-domain [:enum :runs :explicit :missing-opt]})

(deftest a-skip-is-logged-not-printed
  (let [logger (->RecordingLogger (atom []))]
    (log/set-logger! logger)
    (let [out (with-out-str
                (is (= {:skipped :disconnect-then-connect :reason "not today"}
                       (conf/run-case stub/create-store {:skip {:disconnect-then-connect "not today"}}
                                      :disconnect-then-connect)))
                (is (= {:skipped :seal-ready :reason "opts lack :seal-ready?"}
                       (conf/run-case stub/create-store {} :seal-ready))))]
      (is (= "" out) "nothing reaches stdout")
      (is (= [[:info "conformance: skipping :disconnect-then-connect - not today"]
              [:info "conformance: skipping :seal-ready - opts lack :seal-ready?"]]
             @(:events logger))))))
