(ns hive-spi.log.ports-test
  "A library that names a logging backend cannot load where that backend is
   absent — the reason hive-contracts.registry could not load on ClojureWasm.
   The port must therefore route to whatever is installed, fall back rather
   than throw, and never make a diagnostic the reason a caller fails."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-spi.log.console :as console]
            [hive-spi.log.ports :as log]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- restore-logger
  "Snapshot the installed logger, run F, restore what was installed."
  [f]
  (let [installed (when (log/logger-set?) (log/get-logger))]
    (try
      (f)
      (finally
        (log/clear-logger!)
        (when installed (log/set-logger! installed))))))

(use-fixtures :each restore-logger)

(defrecord RecordingLogger [events]
  log/ILogger
  (log-event [_ level message] (swap! events conj [level message]) nil)
  (logger-levels [_] #{:debug :info :warn :error}))

(defn- recording-logger [] (->RecordingLogger (atom [])))

;; =============================================================================
;; Contract: rendering and routing
;; =============================================================================

(deftest args-render-the-way-the-timbre-call-site-rendered-them-test
  (let [rec (recording-logger)]
    (log/set-logger! rec)
    (log/warn "hive-contracts: no provider for" :ICodeIntel "— running degraded")
    (is (= [[:warn "hive-contracts: no provider for :ICodeIntel — running degraded"]]
           @(:events rec))
        "print-str semantics: space-separated, strings unquoted")))

(deftest every-level-reaches-the-logger-test
  (let [rec (recording-logger)]
    (log/set-logger! rec)
    (log/debug "d") (log/info "i") (log/warn "w") (log/error "e")
    (is (= [:debug :info :warn :error] (mapv first @(:events rec))))))

(deftest logging-returns-nil-so-it-never-becomes-a-value-test
  (log/set-logger! (recording-logger))
  (is (nil? (log/warn "x")))
  (is (nil? (log/log! :info "y"))))

;; =============================================================================
;; Contract: the fallback ladder
;; =============================================================================

(deftest a-host-default-is-always-available-test
  (is (some? (log/get-logger))
      "the port resolves timbre or the console logger without configuration")
  (is (contains? (log/logger-levels (log/get-logger)) :warn)))

(deftest the-console-logger-writes-one-line-to-err-test
  (log/set-logger! (console/default-logger))
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (log/warn "no provider for" :ICodeIntel))
    (is (= "WARN no provider for :ICodeIntel" (clojure.string/trim (str err))))))

(deftest an-implementation-that-is-not-a-logger-is-rejected-test
  (is (thrown? AssertionError (log/set-logger! {:not :a-logger})))
  (is (false? (log/logger-set?)) "a rejected install leaves the slot empty"))
