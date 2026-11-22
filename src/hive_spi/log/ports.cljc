(ns hive-spi.log.ports
  "The diagnostic-logging port.

   Declared with no host and no vendor dependency, so a library can emit a
   diagnostic without naming a logging backend and without that backend
   deciding whether the library loads at all.

   Contract: LEVEL is a keyword — `:debug`, `:info`, `:warn`, `:error` — and
   MESSAGE is an already-rendered string. The facade fns take timbre-style
   variadic args and render them with `print-str`, so a call site reads the
   same as the `taoensso.timbre` one it replaces.

   Empty-policy: with no logger installed the port resolves the first host
   default it can load — `hive-spi.log.timbre` when timbre is on the
   classpath, else `hive-spi.log.console`. A diagnostic is therefore never
   lost for want of configuration, and never a load-time dependency.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations."
  (:require [hive-spi.slot :as slot]
            [malli.core :as m]
            #?(:clj [hive-spi.resolve :as resolve])
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -ilogger-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -ilogger-defined? false true))
  (defprotocol ILogger
    "A diagnostic sink."

    (log-event [this level message]
      "Emit MESSAGE at LEVEL. Best-effort: an implementation that cannot emit
       returns nil rather than throwing.")

    (logger-levels [this]
      "The set of level keywords this logger emits.")))

;; The whole host-default ladder is JVM-family only. `requiring-resolve` is the
;; primitive that walks it, and ClojureScript resolves its namespaces at compile
;; time and has no runtime require — so neither the candidate list nor the walk
;; exists there, and the empty-policy yields nil.
#?(:clj
   (def ^:private host-default-candidates
     ['hive-spi.log.timbre/default-logger
      'hive-spi.log.console/default-logger]))

(defonce ^:private host-default
  ;; An absent adapter is skipped quietly; a broken one (present but failing
  ;; to load or construct) is reported through whichever logger the ladder
  ;; did resolve, so a defect never masquerades as 'timbre not installed'.
  (delay
    #?(:clj (let [{:keys [value failures]} (resolve/first-available
                                            host-default-candidates)]
              (when value
                (doseq [f failures]
                  (log-event value :warn (str "hive-spi.log.ports: "
                                              (resolve/describe-failure f)))))
              value)
       :cljs nil)))

(defonce ^:private logger-slot
  (slot/single-slot {:validate #(satisfies? ILogger %)
                     :on-empty #(deref host-default)}))

(defn set-logger!
  "Install LOGGER as the active logger. Returns LOGGER."
  [logger]
  (slot/install! logger-slot logger))

(defn get-logger
  "The active logger: the installed one, else the host default, else nil."
  []
  (slot/current logger-slot))

(defn clear-logger!
  "Remove the installed logger, so consumers fall back to the host default.
   Returns nil."
  []
  (slot/clear! logger-slot))

(defn logger-set?
  "True iff a logger is explicitly installed. The host default does not count."
  []
  (slot/present? logger-slot))

(defn log!
  "Emit ARGS at LEVEL through the active logger, rendered with `print-str`.
   Returns nil, and does nothing when no logger is available — a diagnostic
   never becomes the reason a caller fails."
  [level & args]
  (when-let [logger (get-logger)]
    (log-event logger level (apply print-str args)))
  nil)

(defn debug [& args] (apply log! :debug args))
(defn info  [& args] (apply log! :info args))
(defn warn  [& args] (apply log! :warn args))
(defn error [& args] (apply log! :error args))

;; =============================================================================
;; Contracts
;; =============================================================================

(def Logger
  "An ILogger implementation. Generates a silent logger emitting a generated
   set of levels."
  [:fn {:error/message "must satisfy ILogger"
        :gen/schema    [:set {:min 1} [:enum :debug :info :warn :error]]
        :gen/fmap      (fn [levels]
                         (reify ILogger
                           (log-event [_ _ _] nil)
                           (logger-levels [_] levels)))}
   #(satisfies? ILogger %)])

(m/=> set-logger! [:=> [:cat Logger] Logger])
(tc/ann-op set-logger!)
(m/=> get-logger [:=> [:cat] [:maybe Logger]])
(tc/ann-op get-logger)
(m/=> clear-logger! [:=> [:cat] :nil])
(tc/ann-op clear-logger!)
(m/=> logger-set? [:=> [:cat] :boolean])
(tc/ann-op logger-set?)
;; ARGS are timbre-style variadic message parts: anything `print-str` renders.
(m/=> log! [:=> [:cat :keyword [:* :any]] :nil])
(tc/ann-op log!)
(m/=> debug [:=> [:cat [:* :any]] :nil])
(tc/ann-op debug)
(m/=> info [:=> [:cat [:* :any]] :nil])
(tc/ann-op info)
(m/=> warn [:=> [:cat [:* :any]] :nil])
(tc/ann-op warn)
(m/=> error [:=> [:cat [:* :any]] :nil])
(tc/ann-op error)
