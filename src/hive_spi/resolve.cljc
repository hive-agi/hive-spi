(ns hive-spi.resolve
  "Soft resolution of an optional adapter, as a Result that tells ABSENT from
   BROKEN.

   A port's host-default ladder names adapters that may not be on the
   classpath (an optional vendor such as timbre, a JVM-only clock). Absence
   is the expected degraded case and must stay quiet; a namespace that IS
   present but fails to load, a var that is missing from it, or a
   constructor that throws is a defect and must reach an operator. A bare
   `(catch Exception _ nil)` reads both as 'absent'.

   Error categories:
     :resolve/absent       the namespace, or a dependency it requires, is not
                           on the classpath (a FileNotFoundException at the
                           root of the load failure)
     :resolve/missing-var  the namespace loaded but does not define the var
     :resolve/broken       the namespace is present but failed to load
     :resolve/ctor-failed  the constructor threw
     :resolve/unsupported  the runtime has no runtime require (ClojureScript)

   Stratification: this namespace depends on nothing but hive-dsl and malli,
   so the log port itself can use it. Reporting is the CALLER's concern."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT

(defn- root-cause
  "The innermost cause of THROWABLE."
  [t]
  (if-let [c (ex-cause t)] (recur c) t))

(defn- failure-data
  "The Result payload describing why SYM failed with THROWABLE."
  [sym t]
  (let [root (root-cause t)]
    {:sym     sym
     :class   #?(:clj (.getName (class root)) :default (str (type root)))
     :message (or (ex-message root) (ex-message t) (str t))}))

(defn absence?
  "True iff THROWABLE is a classpath miss: its root cause is a
   FileNotFoundException, which is how the JVM reader reports a namespace
   (or a namespace's dependency) that has no source or class on the
   classpath. Off the JVM family nothing is a classpath miss."
  [t]
  #?(:clj  (instance? java.io.FileNotFoundException (root-cause t))
     :default (some? (and false t))))

(defn soft-resolve
  "Resolve the qualified symbol SYM, loading its namespace on demand.
   Answers (ok var), or an err categorised as :resolve/absent,
   :resolve/missing-var, :resolve/broken or :resolve/unsupported."
  [sym]
  #?(:clj
     (try
       (if-let [v (requiring-resolve sym)]
         (r/ok v)
         (r/err :resolve/missing-var {:sym sym}))
       (catch Exception e
         (r/err (if (absence? e) :resolve/absent :resolve/broken)
                (failure-data sym e))))
     :default
     (r/err :resolve/unsupported {:sym sym})))

(defn construct
  "Resolve the zero-arity constructor CTOR-SYM and call it.
   Answers (ok instance), the soft-resolve err, or :resolve/ctor-failed when
   the constructor throws."
  [ctor-sym]
  (r/bind (soft-resolve ctor-sym)
          (fn [ctor]
            (try
              (r/ok (ctor))
              (catch #?(:clj Exception :default :default) e
                (r/err :resolve/ctor-failed (failure-data ctor-sym e)))))))

(defn absent?
  "True iff RESULT is an err that only says the adapter is not installed:
   the quiet, expected outcome of an optional adapter."
  [result]
  (contains? #{:resolve/absent :resolve/unsupported} (:error result)))

(defn first-available
  "Construct each of CTOR-SYMS in order and stop at the first that succeeds.
   Answers {:value instance-or-nil :failures [err ...]} where :failures holds
   every BROKEN attempt made before the answer (absent ones are dropped), so
   the caller can report defects through whatever it resolved."
  [ctor-syms]
  (loop [[sym & more] ctor-syms
         failures     []]
    (if (nil? sym)
      {:value nil :failures failures}
      (let [res (construct sym)]
        (cond
          (r/ok? res)   {:value (:ok res) :failures failures}
          (absent? res) (recur more failures)
          :else         (recur more (conj failures res)))))))

(defn describe-failure
  "One operator-facing line for a broken resolution RESULT."
  [{:keys [error sym class message]}]
  (str "adapter " sym " is present but unusable (" (name error) ")"
       (when class (str ": " class))
       (when message (str " " message))))

;; =============================================================================
;; Contracts
;; =============================================================================

(def ResolveError
  "The err side of a resolution."
  [:map
   [:error [:enum :resolve/absent :resolve/missing-var :resolve/broken
            :resolve/ctor-failed :resolve/unsupported]]
   [:sym :qualified-symbol]
   [:class {:optional true} :string]
   [:message {:optional true} :string]])

(def Resolution
  "What `construct` answers."
  [:or [:map [:ok :any]] ResolveError])

(def Ladder
  "What `first-available` answers."
  [:map
   [:value :any]
   [:failures [:vector ResolveError]]])

(m/=> absence? [:=> [:cat :any] :boolean])
(tc/ann-op absence?)
(m/=> soft-resolve [:=> [:cat :qualified-symbol] Resolution])
(tc/ann-op soft-resolve)
(m/=> construct [:=> [:cat :qualified-symbol] Resolution])
(tc/ann-op construct)
(m/=> absent? [:=> [:cat [:maybe :map]] :boolean])
(tc/ann-op absent?)
(m/=> first-available [:=> [:cat [:sequential :qualified-symbol]] Ladder])
(tc/ann-op first-available)
(m/=> describe-failure [:=> [:cat ResolveError] :string])
(tc/ann-op describe-failure)
