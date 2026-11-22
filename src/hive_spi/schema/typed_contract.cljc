(ns hive-spi.schema.typed-contract
  "Typed Clojure annotations derived from the m/=> contracts a namespace
   already declares: one source, two rungs of the verify ladder.

   `(tc/ann-op f)`, placed after `(m/=> f ..)`, reads the function schema that
   contract registered and, when typed.clojure is loaded (a type-checking
   run opted in with the JVM property `-Dhive.spi.typed=true`, as the :typed
   alias does), expands to `(typed.clojure/ann f <FnType projected from it>)`.
   Otherwise it expands to nil, so a namespace using it needs neither
   typed.clojure on its classpath nor any runtime cost, and an AOT build that
   happens to load typed.clojure cannot bake annotations into the classes.

   On the JVM the expansion REFUSES a var with no registered contract, so a
   misplaced or stale annotation fails at load instead of drifting. It
   defines no var. Under ClojureScript it expands to nil.

   Unlike hive-spi.schema.typed-ann, this namespace does not require
   typed.clojure, so any namespace may require it. Type-check from source:
   load typed.clojure first, then the namespace, then check it."
  #?(:clj  (:require [malli.core :as m]
                     [hive-spi.schema.typed :as typed])
     :cljs (:require-macros [hive-spi.schema.typed-contract])))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

#?(:clj
   (defn- qualify
     "`sym` fully qualified against `*ns*` and its aliases."
     [sym]
     (let [nsym (some-> (namespace sym) symbol)]
       (symbol (str (if nsym
                      (or (some-> (get (ns-aliases *ns*) nsym) ns-name) nsym)
                      (ns-name *ns*)))
               (name sym)))))

#?(:clj
   (defn emit?
     "True when ann-op should emit a real annotation: typed.clojure is loaded
      and the run opted in with -Dhive.spi.typed=true."
     []
     (boolean (and (= "true" (System/getProperty "hive.spi.typed"))
                   (find-ns 'typed.clojure)))))

#?(:clj
   (defmacro ann-op
     "Annotate var `sym` with the Typed Clojure type of its m/=> contract.
      With `arg-schema` (and optional `result-schema`, default :hive/result)
      the type comes from those schemas instead, as in typed-ann/ann-op.
      Expands to nil unless `emit?`; on the JVM the 1-arity
      form throws at expansion when `sym` has no registered m/=> contract."
     ([sym]
      (when-not (:ns &env)
        (let [qsym (qualify sym)]
          (when-not (typed/contract-schema qsym)
            (throw (ex-info (str "ann-op: no m/=> contract is registered for " qsym
                                 "; place (tc/ann-op " sym ") after its (m/=> " sym " ..)")
                            {:sym sym :qsym qsym})))
          ;; The type is projected only when it is emitted, so an ordinary
          ;; load pays a registry lookup, not a schema walk.
          (when (emit?) (typed/contract-ann-form qsym)))))
     ([sym arg-schema]
      (when (and (not (:ns &env)) (emit?))
        (typed/ann-form sym arg-schema)))
     ([sym arg-schema result-schema]
      (when (and (not (:ns &env)) (emit?))
        (typed/ann-form sym arg-schema result-schema)))))

#?(:clj
   (do (m/=> qualify [:=> [:cat :symbol] :qualified-symbol])
       (m/=> emit? [:=> [:cat] :boolean])))
