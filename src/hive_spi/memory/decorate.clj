(ns hive-spi.memory.decorate
  "Store decorators: behaviour every registered memory store gets, whatever
   its backend.

   A decorator is a map:
     :id        keyword; adding one with the same id replaces it
     :order     number; the lowest wraps first (innermost)
     :decorate  (fn [key store] -> store); returning STORE unchanged opts out

   hive-spi.memory.registry runs every store through the chain inside
   register-store!, and again whenever the chain changes, so neither a
   backend nor the order addons boot in can leave a store undecorated. A
   decorator that throws fails the registration: a store that has to be
   decorated is never installed bare.

   A decorating store implements IStoreDecorator, so the chain is always
   re-applied to the BACKEND store (`base-store`) and never wraps twice.

   The :embed-text capability belongs to this contract. A store declaring it
   in (:capabilities (store-status s)) embeds an entry's :embed-text instead
   of its :content when one is given, and never persists :embed-text.
   Decorators that change content at rest (encryption) need it, or the
   backend would index what they wrote.")

;; SPDX-License-Identifier: MIT

(defonce ^:private -idecorator-defined? (atom false))

(when (compare-and-set! -idecorator-defined? false true)
  (defprotocol IStoreDecorator
    "A memory store that wraps another one."
    (inner-store [this]
      "The store this one decorates.")))

(defn decorator?
  "True when S wraps another store."
  [s]
  (satisfies? IStoreDecorator s))

(defn base-store
  "S with every decorator peeled off: the backend store."
  [s]
  (loop [s s]
    (if (decorator? s)
      (recur (inner-store s))
      s)))

(def embed-text-capability :embed-text)

(defn embed-text-capable?
  "True when STATUS (a store-status map) declares the :embed-text capability."
  [status]
  (boolean (some #(= (name embed-text-capability) (name %)) (:capabilities status))))

;;; ============================================================================
;;; The chain
;;; ============================================================================

(defonce ^:private -decorators (atom {}))

(defn- valid? [{:keys [id order decorate]}]
  (and (keyword? id) (number? order) (fn? decorate)))

(defn add!
  "Add DECORATOR to the chain, replacing any with its :id. Returns the chain.
   Does not touch registered stores: hive-spi.memory.registry/add-decorator!
   does both."
  [decorator]
  (when-not (valid? decorator)
    (throw (ex-info "a store decorator needs :id (keyword), :order (number) and :decorate (fn)"
                    {:decorator (select-keys decorator [:id :order])})))
  (swap! -decorators assoc (:id decorator) decorator))

(defn remove!
  "Drop the decorator with ID. Returns the chain."
  [id]
  (swap! -decorators dissoc id))

(defn chain
  "Decorators in application order: :order ascending, then :id."
  []
  (sort-by (juxt :order (comp str :id)) (vals @-decorators)))

(defn apply-chain
  "The store registered under KEY: (base-store STORE) run through every
   decorator in order."
  [key store]
  (reduce (fn [s {:keys [decorate]}] (decorate key s))
          (base-store store)
          (chain)))

(defn reset-chain!
  "Remove every decorator. Returns nil."
  []
  (reset! -decorators {})
  nil)
