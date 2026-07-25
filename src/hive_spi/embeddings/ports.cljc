(ns hive-spi.embeddings.ports
  "The embedding-provider port.

   Declared with no dependencies so an embedder implementation never has to
   depend on a vector-store backend to learn what to implement.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations."
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -embeddingprovider-defined? (atom false))

(when (compare-and-set! -embeddingprovider-defined? false true)
  (defprotocol EmbeddingProvider
    "Turns text into embedding vectors."

    (embed-text [this text]
      "The embedding vector for TEXT.")

    (embed-batch [this texts]
      "One embedding vector per entry of TEXTS, in order.")

    (embedding-dimension [this]
      "The dimension of the vectors this provider produces.")))

(defonce ^:private provider-slot
  (slot/single-slot {:validate #(satisfies? EmbeddingProvider %)}))

(defn set-provider!
  "Install PROVIDER as the active embedding provider. Returns PROVIDER."
  [provider]
  (slot/install! provider-slot provider))

(defn get-provider
  "The active embedding provider, or nil when none is installed."
  []
  (slot/current provider-slot))

(defn provider-set?
  "True iff an embedding provider is installed."
  []
  (slot/present? provider-slot))

(defn clear-provider!
  "Remove the active embedding provider. Returns nil."
  []
  (slot/clear! provider-slot))
