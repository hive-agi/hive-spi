(ns hive-spi.schema.gen
  "Malli-owned generation over the hive core-op registry.

   Wraps malli.generator so registered schemas drive test.check generation.
   Requiring this ns pulls malli.generator (+ test.check); require it only where
   you generate (tests, property oracles). derive and the core deps do not
   force it.

   Loading this ns registers the :generator and :cases projections into the
   derivation lever: every subsequent compile-op bundle carries the test facet."
  (:require [malli.generator :as mg]
            [hive-spi.schema.registry :as reg]
            [hive-spi.schema.derive :as derive]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn generator
  "test.check generator for `?schema` (a registry key or an inline malli form),
   resolving named refs through the hive registry. Honours malli :gen/* schema
   properties (e.g. :hive/known-error-category's :gen/elements). Pass to
   clojure.test.check.properties/for-all."
  ([?schema] (mg/generator (reg/schema ?schema)))
  ([?schema opts] (mg/generator (reg/schema ?schema) opts)))

(defn generate
  "One value conforming to `?schema`. `opts` may carry :seed and :size for
   reproducible / sized generation."
  ([?schema] (mg/generate (reg/schema ?schema)))
  ([?schema opts] (mg/generate (reg/schema ?schema) opts)))

(defn sample
  "A sequence of values conforming to `?schema` (malli default 10; pass opts with
   :size to grow the sample). Handy for smoke-fuzzing an op's arg-schema."
  ([?schema] (mg/sample (reg/schema ?schema)))
  ([?schema opts] (mg/sample (reg/schema ?schema) opts)))

(defn seeded-cases
  "{label input} — a reproducible, sorted sample of `n` values conforming to
   `?schema`. Same seed yields the same cases."
  [?schema seed n]
  (into (sorted-map)
        (map-indexed (fn [i v] [(keyword (str "case-" i)) v]))
        (sample ?schema {:size n :seed seed})))

(derive/register-projection! :generator generator)

(derive/register-projection! :cases
  (fn [s] (fn [seed n] (seeded-cases s seed n))))