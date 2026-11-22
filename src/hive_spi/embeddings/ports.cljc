(ns hive-spi.embeddings.ports
  "The embedding-provider port.

   Declared with no dependencies so an embedder implementation never has to
   depend on a vector-store backend to learn what to implement.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations."
  (:require [hive-spi.slot :as slot]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -embeddingprovider-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -embeddingprovider-defined? false true))
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

(slot/defslot provider-slot
  {:noun "embedding provider"
   :set set-provider! :get get-provider :clear clear-provider! :present? provider-set?})

;; =============================================================================
;; Contracts
;; =============================================================================

(def Provider
  "An EmbeddingProvider implementation. Generates a constant provider of a
   generated dimension."
  [:fn {:error/message "must satisfy EmbeddingProvider"
        :gen/schema    [:int {:min 1 :max 16}]
        :gen/fmap      (fn [dim]
                         (reify EmbeddingProvider
                           (embed-text [_ _] (vec (repeat dim 0.0)))
                           (embed-batch [_ texts] (mapv (fn [_] (vec (repeat dim 0.0))) texts))
                           (embedding-dimension [_] dim)))}
   #(satisfies? EmbeddingProvider %)])

(m/=> set-provider! [:=> [:cat Provider] Provider])
(m/=> get-provider [:=> [:cat] [:maybe Provider]])
(m/=> provider-set? [:=> [:cat] :boolean])
(m/=> clear-provider! [:=> [:cat] :nil])
