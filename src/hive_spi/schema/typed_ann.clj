(ns hive-spi.schema.typed-ann
  "Macros that splice registry-derived Typed Clojure annotations into a
   `^:typed.clojure` namespace. Requires typed.clojure; require only in
   namespaces you type-check."
  (:require [typed.clojure :as t]
            [hive-spi.schema.typed :as typed]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defmacro defalias-schema
  "Expand to `(t/defalias alias-sym <validator-type-of ?schema>)`, sourcing the
   type from the registered malli schema."
  [alias-sym ?schema]
  (typed/defalias-form alias-sym ?schema))

(defmacro ann-op
  "Expand to `(t/ann sym (ArgT :-> ResultT))` for handler `sym`. `arg-schema`
   and optional `result-schema` (default :hive/result) are registry keys or
   inline malli forms."
  ([sym arg-schema]
   (typed/ann-form sym arg-schema))
  ([sym arg-schema result-schema]
   (typed/ann-form sym arg-schema result-schema)))
