(ns hive-spi.memory.stub
  "Atom-backed reference implementation of the hive-spi.memory.ports family:
   IMemoryStore plus the Analytics, MetadataWrite, Staleness, Batch and
   Liveness role protocols.

   Its fidelity to the ports is PROVEN by hive-spi.memory.conformance, which
   runs against it in hive-spi's own suite; consumers test their domain code
   against this store and stay portable to every store that passes the same
   suite.

   Semantic search is available only when `create-store` receives an
   `:embedder`, a (fn [text] -> vector of numbers); without one the store
   answers `supports-semantic-search?` false and `search-similar` returns
   `ports/degraded-search-result`."
  (:require [hive-spi.memory.entry :as entry]
            [hive-spi.memory.ids :as ids]
            [hive-spi.memory.ports :as ports])
  (:import [java.time Instant]))

;; SPDX-License-Identifier: MIT

(defn- cosine
  "Cosine similarity of two equal-length numeric vectors, nil when either is
   empty or zero."
  [a b]
  (when (and (seq a) (seq b) (= (count a) (count b)))
    (let [dot (reduce + (map * a b))
          na  (Math/sqrt (reduce + (map * a a)))
          nb  (Math/sqrt (reduce + (map * b b)))]
      (when (and (pos? na) (pos? nb))
        (/ dot (* na nb))))))

(defn- embedder [state] (:embedder (:config @state)))

(defn- put!
  "Normalize ENTRY, embed it when an embedder is configured, store it and
   return the stored entry."
  [state entry]
  (let [e   (entry/normalize entry)
        f   (embedder state)
        txt (entry/embed-text (:content e))
        emb (when (and (fn? f) txt) (f txt))]
    (swap! state (fn [st]
                   (cond-> (assoc-in st [:entries (:id e)] e)
                     emb (assoc-in [:embeddings (:id e)] (vec emb))
                     (not emb) (update :embeddings dissoc (:id e)))))
    e))

(defn- entries [state] (vals (:entries @state)))

(defrecord StubMemoryStore [state]
  ports/IMemoryStore

  (connect! [_ config]
    (swap! state #(-> % (update :config merge config) (assoc :connected? true)))
    {:success? true :errors [] :backend "stub"})

  (disconnect! [_]
    (swap! state assoc :connected? false)
    {:success? true :errors []})

  (connected? [_] (boolean (:connected? @state)))

  (health-check [_]
    (let [up (boolean (:connected? @state))]
      {:healthy? up :backend "stub" :errors (if up [] ["disconnected"])}))

  (add-entry! [_ e] (:id (put! state e)))

  (get-entry [_ id] (get-in @state [:entries id]))

  (update-entry! [_ id updates]
    (when-let [e (get-in @state [:entries id])]
      (put! state (merge e updates {:id id :updated (ids/iso-timestamp)}))))

  (delete-entry! [_ id]
    (swap! state #(-> % (update :entries dissoc id) (update :embeddings dissoc id)))
    true)

  (query-entries [_ opts] (entry/query (entries state) opts))

  (search-similar [_ query-text {:keys [limit] :or {limit 10} :as opts}]
    (if-let [f (embedder state)]
      (let [q    (vec (f (or (entry/embed-text query-text) "")))
            embs (:embeddings @state)
            now  (Instant/now)]
        (->> (entries state)
             (filter #(entry/matches? % (dissoc opts :include-expired?) now))
             (keep (fn [e]
                     (when-let [score (cosine q (get embs (:id e)))]
                       (assoc e :score (double score)))))
             (sort-by :score #(compare %2 %1))
             (take limit)
             vec))
      (ports/degraded-search-result {:reason :no-embedder :backend "stub"})))

  (supports-semantic-search? [_] (fn? (embedder state)))

  (cleanup-expired! [_]
    (let [now       (Instant/now)
          protected (ports/protected-ids)
          ids       (->> (entries state)
                         (filter #(entry/expired? % now))
                         (map :id)
                         (remove protected)
                         vec)]
      (swap! state (fn [st]
                     (-> st
                         (update :entries #(apply dissoc % ids))
                         (update :embeddings #(apply dissoc % ids)))))
      {:count (count ids) :deleted-ids ids}))

  (entries-expiring-soon [_ days opts]
    (let [now (Instant/now)]
      (->> (entries state)
           (filter #(entry/expires-within? % days now))
           (filter #(entry/matches? % (assoc opts :include-expired? true) now))
           vec)))

  (find-duplicate [_ type content-hash {:keys [project-id]}]
    (->> (entries state)
         (filter #(and (= content-hash (:content-hash %))
                       (or (nil? type)
                           (= (entry/type-name type) (entry/type-name (:type %))))
                       (or (nil? project-id) (= (str project-id) (:project-id %)))))
         first))

  (store-status [_]
    {:backend          "stub"
     :configured?      true
     :connected?       (boolean (:connected? @state))
     :entry-count      (count (:entries @state))
     :supports-search? (fn? (embedder state))})

  (reset-store! [_]
    (swap! state assoc :entries {} :embeddings {})
    true)

  ports/IMemoryStoreWithAnalytics

  (log-access! [_ id]
    (when (get-in @state [:entries id])
      (swap! state update-in [:entries id :access-count] (fnil inc 0))
      nil))

  (record-feedback! [_ id feedback]
    (when (get-in @state [:entries id])
      (let [field (case feedback
                    :helpful :helpful-count
                    :unhelpful :unhelpful-count)]
        (swap! state update-in [:entries id field] (fnil inc 0))
        nil)))

  (get-helpfulness-ratio [_ id]
    (let [e         (get-in @state [:entries id])
          helpful   (or (:helpful-count e) 0)
          unhelpful (or (:unhelpful-count e) 0)
          total     (+ helpful unhelpful)]
      {:helpful-count   helpful
       :unhelpful-count unhelpful
       :total           total
       :ratio           (if (pos? total) (double (/ helpful total)) 0.0)}))

  ports/IMemoryStoreMetadataWrite

  (update-metadata! [this id updates]
    (if (or (contains? updates :content) (contains? updates :type))
      (ports/update-entry! this id updates)
      (when-let [e (get-in @state [:entries id])]
        (let [m (merge e updates {:id id :updated (ids/iso-timestamp)})]
          (swap! state assoc-in [:entries id] m)
          m))))

  ports/IMemoryStoreWithStaleness

  (update-staleness! [_ id staleness-opts]
    (when (get-in @state [:entries id])
      (swap! state update-in [:entries id]
             merge (select-keys staleness-opts [:staleness-alpha :staleness-beta]))
      (get-in @state [:entries id])))

  (get-stale-entries [_ threshold _opts]
    (->> (entries state)
         (filter (fn [e]
                   (let [alpha (or (:staleness-alpha e) 1)
                         beta  (or (:staleness-beta e) 1)]
                     (> (/ (double beta) (+ alpha beta)) threshold))))
         vec))

  (propagate-staleness! [_ _source-id _depth] 0)

  ports/IMemoryStoreBatch

  (get-entries [_ ids]
    (into [] (keep #(get-in @state [:entries %])) (distinct ids)))

  ports/IMemoryStoreLiveness

  (-probe! [_] (boolean (:connected? @state)))

  (-kick-reconnect! [_]
    (swap! state assoc :connected? true)
    nil)

  (-await-reconnect! [_ _budget-ms] (boolean (:connected? @state))))

(defn create-store
  "A connected, empty StubMemoryStore. CONFIG may carry `:embedder`, a
   (fn [text] -> numeric vector) that turns semantic search on."
  ([] (create-store {}))
  ([config]
   (->StubMemoryStore (atom {:config     (or config {})
                             :connected? true
                             :entries    {}
                             :embeddings {}}))))
