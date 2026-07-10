(ns hive-spi.schema.gen
  "Malli-owned GENERATION over the hive core-op registry.

   MALLI-P4 canonical ownership: malli owns shape + coercion + GENERATION. This
   is the generation half of the single-source lever — kept SEPARATE from
   hive-spi.schema.derive (boundary artifacts) so the lean core stays free of a
   test.check runtime dependency. Requiring THIS namespace pulls malli.generator
   (+ test.check); do so only where you actually generate (tests, property
   oracles). `derive` and the core deps do not force it.

   This is the shape->test.check bridge that carto RBT-B2's property oracle
   (defprop-equiv, tier T2 'Malli :=> via malli.generator') consumes — the same
   registered :hive/* / :carto/* schemas used for validation and MCP inputSchema
   now also drive input fuzzing, one truth per op."
  (:require [malli.generator :as mg]
            [hive-spi.schema.registry :as reg]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn generator
  "test.check generator for `?schema` (a registry key like :carto/search-args or
   an inline malli form), resolving :hive/* named refs through the hive registry.
   Honours malli :gen/* schema properties (e.g. :hive/known-error-category's
   :gen/elements). Pass to clojure.test.check.properties/for-all."
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
