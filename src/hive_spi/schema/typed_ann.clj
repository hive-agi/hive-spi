;; The typed.clojure require is LOAD-BEARING and lexically invisible: these
;; macros expand to fully-qualified `typed.clojure/defalias` and
;; `typed.clojure/ann` forms (built by hive-spi.schema.typed), so the namespace
;; must be loaded for a consumer's expansion to resolve while no symbol from it
;; appears in this file. Hence the local waiver, not an alias nothing uses.
(ns ^{:clj-kondo/config '{:linters {:unused-namespace {:exclude [typed.clojure]}}}}
  hive-spi.schema.typed-ann
  "Macros that splice registry-derived Typed Clojure annotations into a
   `^:typed.clojure` namespace. Requires typed.clojure; require only in
   namespaces you type-check."
  (:require [typed.clojure]
            [hive-spi.schema.typed :as typed]))

;; SPDX-License-Identifier: MIT
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
