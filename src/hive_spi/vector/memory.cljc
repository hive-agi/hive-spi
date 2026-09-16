(ns hive-spi.vector.memory
  "An in-memory `IVectorCollectionStore`.

   This exists so a subsystem that owns a vector collection can be tested with
   no vendor running. It is the reference implementation the port is specified
   against, not a production store: everything lives in one atom and nothing is
   persisted.

   It is deliberately HONEST about the two things a fake usually gets wrong,
   because those are exactly what a caller regresses on when the fake is too
   forgiving:

   - `-query` returns records NEAREST FIRST with an ASCENDING `:distance`,
     never a similarity. A store that quietly passed a cosine SIMILARITY
     through the `:distance` key would order results backwards, and a fake
     that returned insertion order would never catch it.
   - `-create-collection` without `:get-or-create?` REFUSES an existing name
     rather than silently returning it."
  (:require [hive-spi.vector.ports :as vp]))

;; SPDX-License-Identifier: MIT

(defn- l2-distance
  "Euclidean distance between two vectors. Vectors of differing length are a
   caller error, not something to pad around: a dimension mismatch is how a
   wrong embedding provider announces itself."
  [a b]
  (when (not= (count a) (count b))
    (throw (ex-info "Embedding dimension mismatch"
                    {:reason ::dimension-mismatch
                     :expected (count a) :actual (count b)})))
  (Math/sqrt (reduce + 0.0 (map (fn [x y] (let [d (- x y)] (* d d))) a b))))

(defn- matches-where?
  "True when RECORD's :metadata satisfies WHERE, a flat map of equality
   constraints. nil WHERE matches everything."
  [where record]
  (or (nil? where)
      (every? (fn [[k v]] (= v (get (:metadata record) k)))
              where)))

(defn- select-records
  [records {:keys [ids where limit]}]
  (cond->> (vals records)
    (seq ids) (filter #(contains? (set ids) (:id %)))
    where     (filter #(matches-where? where %))
    limit     (take limit)))

(defrecord InMemoryVectorStore [state]
  vp/IVectorCollectionStore
  (-configure [this _opts] this)

  (-get-collection [_ coll-name]
    (when (contains? @state coll-name) coll-name))

  (-create-collection [_ coll-name opts]
    (let [exists? (contains? @state coll-name)]
      (when (and exists? (not (:get-or-create? opts)))
        (throw (ex-info "Collection already exists"
                        {:reason ::collection-exists :collection coll-name})))
      (when-not exists?
        (swap! state assoc coll-name {}))
      coll-name))

  (-delete-collection [_ coll]
    (swap! state dissoc coll)
    nil)

  (-add [_ coll records _opts]
    (swap! state update coll
           (fn [existing]
             (reduce (fn [acc r] (assoc acc (:id r) r)) (or existing {}) records)))
    nil)

  (-get [_ coll opts]
    (vec (select-records (get @state coll) opts)))

  (-query [_ coll embedding {:keys [n-results where] :as _opts}]
    (let [candidates (select-records (get @state coll) {:where where})]
      (cond->> candidates
        true (keep (fn [r]
                     (when-let [e (:embedding r)]
                       (assoc r :distance (l2-distance embedding e)))))
        true (sort-by :distance)
        n-results (take n-results)
        true vec)))

  (-delete [_ coll opts]
    (let [doomed (set (map :id (select-records (get @state coll) opts)))]
      (swap! state update coll #(apply dissoc % doomed)))
    nil)

  (-update [_ coll records]
    (swap! state update coll
           (fn [existing]
             (reduce (fn [acc r]
                       (if (contains? acc (:id r))
                         (update acc (:id r) merge r)
                         acc))
                     (or existing {}) records)))
    nil))

(defn in-memory-store
  "A fresh in-memory `IVectorCollectionStore`. Each call is independent, so a
   fixture can hand every test its own."
  []
  (->InMemoryVectorStore (atom {})))
