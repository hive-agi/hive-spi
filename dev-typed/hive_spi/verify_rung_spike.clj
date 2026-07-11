(ns ^:typed.clojure hive-spi.verify-rung-spike
  "CARTO-VERIFY spike: what Rung 3 (typed.malli static) catches over Rung 2
   (malli runtime). Models a nilable store-lookup contract — Str -> (Nilable
   Entry), the shape a `get-entry-by-id`-style fn projects from its malli
   schema — then shows a nil-safety bug caught STATICALLY that a runtime
   m/=> contract cannot see.

   check: clojure -M:typed -e \"(require 'typed.clojure)(typed.clojure/check-ns-clj 'hive-spi.verify-rung-spike)\""
  (:require [typed.clojure :as t]
            [hive-spi.schema.typed-ann :as ta]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; Entry: a memory-entry subset — the :map -> HMap projection.
(ta/defalias-schema Entry [:map [:type :keyword] [:id :any]])

;; get-entry-by-id : Str -> (Nilable Entry). Returns nil when nothing is found —
;; exactly why the caller MUST nil-check.
(t/ann get-entry-by-id [t/Str :-> (t/Nilable Entry)])
(defn get-entry-by-id [id]
  (when (seq id) {:type :note :id id}))

;; CORRECT: nil-guarded access. Result typed (Nilable Kw). Accepted.
(t/ann entry-type-safe [t/Str :-> (t/Nilable t/Kw)])
(defn entry-type-safe [id]
  (when-let [e (get-entry-by-id id)]
    (:type e)))

;; Uncomment to make `clojure -M:typed` reject this ns: the unguarded (:type …)
;; is (U Keyword nil), not Kw. Rung-2 malli cannot catch it. Measured finding +
;; error transcript live in hive memory (verify-ladder Rung-3 spike).
;;   (t/ann entry-type-unsafe [t/Str :-> t/Kw])
;;   (defn entry-type-unsafe [id] (:type (get-entry-by-id id)))
