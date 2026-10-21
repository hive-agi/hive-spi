;; MIT License - Copyright (c) 2026 Pedro Gomes Branquinho (BuddhiLW)

(ns hive-spi.guard.ports-test
  "The projection hook key is the only join between a vendor library and the
   guard, so key derivation and its inverse are pinned by property."
  (:require [hive-schemas.test :as hst]
            [hive-spi.guard.ports :as p]))

(hst/deftrifecta-from-schema projection-ext-key-round-trips-through-harness-of
  hive-spi.guard.ports/projection-ext-key
  {:in  [:cat :keyword]
   :out :qualified-keyword
   :rel (fn [[h] out]
          (and (p/projection-ext-key? out)
               (= (keyword (name h)) (p/harness-of out))))})

(def ^:private KeyCandidate
  [:or :qualified-keyword :keyword :string :nil
   [:enum :guard-projection/claude-code :guard-projection/mcp :guard/decide]])

(hst/deftrifecta-from-schema projection-ext-key?-reads-the-namespace
  hive-spi.guard.ports/projection-ext-key?
  {:in  [:cat KeyCandidate]
   :out :boolean
   :rel (fn [[k] out]
          (= out (and (keyword? k) (= p/projection-ext-ns (namespace k)))))})

(hst/deftrifecta-from-schema harness-of-is-nil-off-the-namespace
  hive-spi.guard.ports/harness-of
  {:in  [:cat KeyCandidate]
   :out [:maybe :keyword]
   :rel (fn [[k] out]
          (if (p/projection-ext-key? k)
            (= out (keyword (name k)))
            (nil? out)))})
