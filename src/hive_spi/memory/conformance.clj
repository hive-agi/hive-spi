(ns hive-spi.memory.conformance
  "Conformance suite for the hive-spi.memory.ports protocol family.

   The suite is data: `cases` is a vector of {:id :section :doc :run}, each
   :run a (fn [ctx]) that asserts with clojure.test/is. Two projections run
   it:

     (defconformance my-store #(make-connected-store) opts)
       emits one deftest per case, named my-store-<case-id>, so any
       clojure.test runner counts and reports them individually.

     (run-conformance make-store opts)
       runs every case inside the current clojure.test context and returns
       {:ran [...] :skipped {...}}.

   MAKE-STORE is a zero-arg fn returning a CONNECTED store; the suite calls
   reset-store! on it before every case and never deletes anything else.
   OPTS:
     :connect-config  map handed to connect! by the lifecycle case; when the
                      key is absent that case is skipped
     :skip            {case-id \"reason\"} cases to skip, reported by name

   IMemoryStore cases run for every store. Role-protocol cases run only when
   (satisfies? Proto store) and pass vacuously otherwise. The semantic case
   checks the declared degraded value when supports-semantic-search? is
   false and the positive shape when it is true.

   Entry comparisons follow the port contract: :type is compared by name
   (a store may return keyword or string), :tags as a set of strings."
  (:require [clojure.test :as t :refer [is testing]]
            [hive-spi.memory.entry :as entry]
            [hive-spi.memory.ids :as ids]
            [hive-spi.memory.ports :as ports])
  (:import [java.time Duration Instant]))

;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; Helpers
;;; ============================================================================

(def ^:private past-ts "2020-01-01T00:00:00Z")
(def ^:private far-ts "2099-01-01T00:00:00Z")

(defn- soon-ts [days]
  (str (.plus (Instant/now) (Duration/ofDays (long days)))))

(defn- token [] (subs (str (random-uuid)) 0 8))

(defn make-entry
  "A valid memory entry with a fresh id and content-hash, OVERRIDES merged in."
  ([] (make-entry {}))
  ([overrides]
   (let [e (merge {:id       (ids/generate-id)
                   :type     :note
                   :content  (str "conformance " (token))
                   :tags     ["conformance"]
                   :duration :medium}
                  overrides)]
     (assoc e :content-hash (ids/content-hash (:content e))))))

(defn- fresh
  "A reset store from the context's make-store."
  [{:keys [make-store]}]
  (let [s (make-store)]
    (ports/reset-store! s)
    s))

(defn- ids-of [rows] (set (map :id rows)))

(defn- type= [a b] (= (entry/type-name a) (entry/type-name b)))

(defn- tags= [a b] (= (set (map str a)) (set (map str b))))

(defn- seed!
  "Add ENTRIES to S and return them keyed by their :id."
  [s entries]
  (doseq [e entries] (ports/add-entry! s e))
  (into {} (map (juxt :id identity)) entries))

(defn- query-fixture
  "Five entries with distinct type, tags, project and :created, one expired."
  []
  (let [tk (token)]
    {:n1 (make-entry {:id (str "n1-" tk) :type :note :tags ["a" "b"] :project-id "p1"
                      :created "2026-01-01T00:00:00Z"})
     :n2 (make-entry {:id (str "n2-" tk) :type :note :tags ["a"] :project-id "p2"
                      :created "2026-01-02T00:00:00Z"})
     :c1 (make-entry {:id (str "c1-" tk) :type :convention :tags ["b" "c"] :project-id "p1"
                      :created "2026-01-03T00:00:00Z"})
     :x1 (make-entry {:id (str "x1-" tk) :type :note :tags ["a"] :project-id "p1"
                      :created "2026-01-04T00:00:00Z" :expires past-ts})
     :n3 (make-entry {:id (str "n3-" tk) :type :note :tags ["a" "b"] :project-id "p3"
                      :created "2026-01-05T00:00:00Z"})}))

(defn- seeded-query-store [ctx]
  (let [s (fresh ctx)
        f (query-fixture)]
    (seed! s (vals f))
    [s (into {} (map (fn [[k e]] [k (:id e)])) f)]))

;;; ============================================================================
;;; Cases
;;; ============================================================================

(def cases
  "Every conformance case, in run order."
  [;; ---- lifecycle ---------------------------------------------------------
   {:id :connected-is-boolean :section :lifecycle
    :doc "connected? answers a boolean."
    :run (fn [ctx] (is (boolean? (ports/connected? (fresh ctx)))))}

   {:id :health-check-shape :section :lifecycle
    :doc "health-check is a map with a boolean :healthy?."
    :run (fn [ctx]
           (let [h (ports/health-check (fresh ctx))]
             (is (map? h))
             (is (boolean? (:healthy? h)))))}

   {:id :store-status-shape :section :lifecycle
    :doc "store-status is a map naming its :backend as a string."
    :run (fn [ctx]
           (let [st (ports/store-status (fresh ctx))]
             (is (map? st))
             (is (string? (:backend st)))))}

   {:id :disconnect-then-connect :section :lifecycle
    :requires-opt :connect-config
    :doc "disconnect! leaves connected? false; connect! answers {:success? ...} and, when successful, connected? true."
    :run (fn [{:keys [opts] :as ctx}]
           (let [s (fresh ctx)]
             (ports/disconnect! s)
             (is (false? (ports/connected? s)))
             (let [r (ports/connect! s (:connect-config opts))]
               (is (map? r))
               (is (contains? r :success?))
               (when (:success? r)
                 (is (true? (ports/connected? s)))))))}

   ;; ---- crud --------------------------------------------------------------
   {:id :add-returns-id :section :crud
    :doc "add-entry! returns the entry's id as a string."
    :run (fn [ctx]
           (let [s (fresh ctx) e (make-entry) r (ports/add-entry! s e)]
             (is (string? r))
             (is (= (:id e) r))))}

   {:id :add-generates-id :section :crud
    :doc "add-entry! mints an id when the entry has none and the entry is readable under it."
    :run (fn [ctx]
           (let [s (fresh ctx)
                 r (ports/add-entry! s (dissoc (make-entry) :id))]
             (is (string? r))
             (is (some? (ports/get-entry s r)))))}

   {:id :add-get-roundtrip :section :crud
    :doc "content, type (by name), tags (as a set), project-id and content-hash round trip; :created and :updated are present after a read."
    :run (fn [ctx]
           (let [s   (fresh ctx)
                 e   (make-entry {:type :convention :content "kebab-case everywhere"
                                  :tags ["style" "naming"] :project-id "proj-rt"})
                 _   (ports/add-entry! s e)
                 got (ports/get-entry s (:id e))]
             (is (some? got))
             (is (= (:id e) (:id got)))
             (is (= (:content e) (:content got)))
             (is (type= (:type e) (:type got)))
             (is (tags= (:tags e) (:tags got)))
             (is (= (:project-id e) (:project-id got)))
             (is (= (:content-hash e) (:content-hash got)))
             (is (string? (:created got)))
             (is (string? (:updated got)))))}

   {:id :get-unknown-is-nil :section :crud
    :doc "get-entry of an unknown id is nil."
    :run (fn [ctx] (is (nil? (ports/get-entry (fresh ctx) (str "missing-" (token))))))}

   {:id :update-merges :section :crud
    :doc "update-entry! merges the given fields, preserves the others and returns a truthy value."
    :run (fn [ctx]
           (let [s   (fresh ctx)
                 e   (make-entry {:type :note :content "original" :tags ["a"] :project-id "p-upd"})
                 _   (ports/add-entry! s e)
                 r   (ports/update-entry! s (:id e) {:content "updated" :tags ["a" "b"]})
                 got (ports/get-entry s (:id e))]
             (is r)
             (is (= "updated" (:content got)))
             (is (tags= ["a" "b"] (:tags got)))
             (is (type= :note (:type got)))
             (is (= "p-upd" (:project-id got)))))}

   {:id :delete-then-get-is-nil :section :crud
    :doc "delete-entry! returns truthy and the entry is unreadable afterwards."
    :run (fn [ctx]
           (let [s (fresh ctx) e (make-entry)]
             (ports/add-entry! s e)
             (is (ports/delete-entry! s (:id e)))
             (is (nil? (ports/get-entry s (:id e))))))}

   {:id :delete-unknown-does-not-throw :section :crud
    :doc "delete-entry! of an unknown id does not throw."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (is (any? (ports/delete-entry! s (str "missing-" (token)))))))}

   {:id :add-delete-count-invariant :section :crud
    :doc "five adds and two deletes leave exactly three readable entries."
    :run (fn [ctx]
           (let [s   (fresh ctx)
                 ids (mapv (fn [i]
                             (let [e (make-entry {:content (str "inv-" i "-" (token))})]
                               (ports/add-entry! s e)
                               (:id e)))
                           (range 5))]
             (ports/delete-entry! s (nth ids 1))
             (ports/delete-entry! s (nth ids 3))
             (is (= 3 (count (keep #(ports/get-entry s %) ids))))))}

   ;; ---- query -------------------------------------------------------------
   {:id :query-by-type :section :query
    :doc ":type filters by name whether given as keyword or string."
    :run (fn [ctx]
           (let [[s ids] (seeded-query-store ctx)
                 kw      (ports/query-entries s {:type :note :limit 100})
                 st      (ports/query-entries s {:type "note" :limit 100})]
             (is (sequential? kw))
             (is (every? #(type= :note (:type %)) kw))
             (is (contains? (ids-of kw) (:n1 ids)))
             (is (contains? (ids-of kw) (:n2 ids)))
             (is (not (contains? (ids-of kw) (:c1 ids))))
             (is (= (ids-of kw) (ids-of st)))))}

   {:id :query-by-project-id :section :query
    :doc ":project-id restricts to one project."
    :run (fn [ctx]
           (let [[s ids] (seeded-query-store ctx)
                 rows    (ids-of (ports/query-entries s {:project-id "p1" :limit 100}))]
             (is (contains? rows (:n1 ids)))
             (is (contains? rows (:c1 ids)))
             (is (not (contains? rows (:n2 ids))))
             (is (not (contains? rows (:n3 ids))))))}

   {:id :query-by-project-ids :section :query
    :doc ":project-ids is an OR over projects."
    :run (fn [ctx]
           (let [[s ids] (seeded-query-store ctx)
                 rows    (ids-of (ports/query-entries s {:project-ids ["p1" "p2"] :limit 100}))]
             (is (contains? rows (:n1 ids)))
             (is (contains? rows (:n2 ids)))
             (is (contains? rows (:c1 ids)))
             (is (not (contains? rows (:n3 ids))))))}

   {:id :query-tags-and :section :query
    :doc ":tags requires every listed tag."
    :run (fn [ctx]
           (let [[s ids] (seeded-query-store ctx)
                 rows    (ids-of (ports/query-entries s {:tags ["a" "b"] :limit 100}))]
             (is (contains? rows (:n1 ids)))
             (is (contains? rows (:n3 ids)))
             (is (not (contains? rows (:n2 ids))))
             (is (not (contains? rows (:c1 ids))))))}

   {:id :query-exclude-tags :section :query
    :doc ":exclude-tags drops entries carrying any listed tag."
    :run (fn [ctx]
           (let [[s ids] (seeded-query-store ctx)
                 rows    (ids-of (ports/query-entries s {:tags ["a"] :exclude-tags ["b"] :limit 100}))]
             (is (contains? rows (:n2 ids)))
             (is (not (contains? rows (:n1 ids))))
             (is (not (contains? rows (:n3 ids))))))}

   {:id :query-limit :section :query
    :doc ":limit caps the row count."
    :run (fn [ctx]
           (let [[s _] (seeded-query-store ctx)
                 rows  (ports/query-entries s {:limit 2 :include-expired? true})]
             (is (pos? (count rows)))
             (is (<= (count rows) 2))))}

   {:id :query-excludes-expired-by-default :section :query
    :doc "expired entries are absent unless :include-expired? is true."
    :run (fn [ctx]
           (let [[s ids] (seeded-query-store ctx)
                 dflt    (ids-of (ports/query-entries s {:type :note :limit 100}))
                 all     (ids-of (ports/query-entries s {:type :note :limit 100 :include-expired? true}))]
             (is (not (contains? dflt (:x1 ids))))
             (is (contains? all (:x1 ids)))
             (is (contains? all (:n1 ids)))))}

   {:id :query-output-fields :section :query
    :doc ":output-fields projects rows: :id survives, :content is dropped when not requested."
    :run (fn [ctx]
           (let [[s _] (seeded-query-store ctx)
                 rows  (ports/query-entries s {:output-fields ["id" "type"] :limit 100 :include-expired? true})]
             (is (pos? (count rows)))
             (is (every? :id rows))
             (is (not-any? #(contains? % :content) rows))))}

   {:id :query-order-by-desc :section :query
    :doc "[:created :desc] orders rows newest first."
    :run (fn [ctx]
           (let [[s _] (seeded-query-store ctx)
                 rows  (ports/query-entries s {:type :note :include-expired? true
                                               :order-by [:created :desc] :limit 100})
                 cs    (map :created rows)]
             (is (>= (count rows) 3))
             (is (= cs (sort #(compare %2 %1) cs)))))}

   {:id :query-order-by-asc :section :query
    :doc "[:created :asc] orders rows oldest first."
    :run (fn [ctx]
           (let [[s _] (seeded-query-store ctx)
                 rows  (ports/query-entries s {:type :note :include-expired? true
                                               :order-by [:created :asc] :limit 100})
                 cs    (map :created rows)]
             (is (>= (count rows) 3))
             (is (= cs (sort cs)))))}

   ;; ---- expiry ------------------------------------------------------------
   {:id :cleanup-expired :section :expiry
    :doc "cleanup-expired! answers {:count n :deleted-ids [...]}, reaps only entries past :expires, and a second run reaps nothing."
    :run (fn [ctx]
           (let [s    (fresh ctx)
                 gone (make-entry {:expires past-ts :content "gone"})
                 far  (make-entry {:expires far-ts :content "far"})
                 keep (make-entry {:content "keep"})
                 _    (seed! s [gone far keep])
                 r    (ports/cleanup-expired! s)]
             (is (map? r))
             (is (integer? (:count r)))
             (is (sequential? (:deleted-ids r)))
             (is (>= (:count r) 1))
             (is (contains? (set (:deleted-ids r)) (:id gone)))
             (is (nil? (ports/get-entry s (:id gone))))
             (is (some? (ports/get-entry s (:id far))))
             (is (some? (ports/get-entry s (:id keep))))
             (is (= 0 (:count (ports/cleanup-expired! s))))))}

   {:id :entries-expiring-soon :section :expiry
    :doc "entries-expiring-soon lists entries whose :expires falls inside the window and no other."
    :run (fn [ctx]
           (let [s    (fresh ctx)
                 soon (make-entry {:expires (soon-ts 3) :content "soon"})
                 far  (make-entry {:expires far-ts :content "far"})
                 none (make-entry {:content "none"})
                 _    (seed! s [soon far none])
                 rows (ports/entries-expiring-soon s 7 {})]
             (is (sequential? rows))
             (is (contains? (ids-of rows) (:id soon)))
             (is (not (contains? (ids-of rows) (:id far))))
             (is (not (contains? (ids-of rows) (:id none))))))}

   ;; ---- duplicates --------------------------------------------------------
   {:id :find-duplicate-hit :section :duplicates
    :doc "find-duplicate returns the entry sharing type and content-hash, with or without a :project-id scope."
    :run (fn [ctx]
           (let [s (fresh ctx)
                 e (make-entry {:type :convention :content (str "dup " (token)) :project-id "p-dup"})]
             (ports/add-entry! s e)
             (is (= (:id e) (:id (ports/find-duplicate s :convention (:content-hash e) {}))))
             (is (= (:id e) (:id (ports/find-duplicate s :convention (:content-hash e)
                                                       {:project-id "p-dup"}))))))}

   {:id :find-duplicate-miss :section :duplicates
    :doc "find-duplicate is nil for an unknown content-hash."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (ports/add-entry! s (make-entry {:type :convention}))
             (is (nil? (ports/find-duplicate s :convention
                                             (ids/content-hash (str "other " (token))) {})))))}

   ;; ---- reset -------------------------------------------------------------
   {:id :reset-empties :section :reset
    :doc "reset-store! leaves no readable entries and the store still usable."
    :run (fn [ctx]
           (let [s  (fresh ctx)
                 es [(make-entry) (make-entry)]]
             (seed! s es)
             (is (ports/reset-store! s))
             (is (empty? (ports/query-entries s {:limit 100 :include-expired? true})))
             (is (every? #(nil? (ports/get-entry s (:id %))) es))
             (is (map? (ports/store-status s)))))}

   ;; ---- semantic ----------------------------------------------------------
   {:id :supports-semantic-search-is-boolean :section :semantic
    :doc "supports-semantic-search? answers a boolean."
    :run (fn [ctx] (is (boolean? (ports/supports-semantic-search? (fresh ctx)))))}

   {:id :search-similar-contract :section :semantic
    :doc "unsupported: search-similar returns an empty sequential and neither throws nor fabricates scores; supported: a sequential of entry maps within :limit whose scores, when present, are numbers."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (seed! s [(make-entry {:content "Clojure is a functional programming language"})
                       (make-entry {:content "Python is popular for data science"})])
             (let [r (ports/search-similar s "functional programming" {:limit 5})]
               (is (sequential? r))
               (if (ports/supports-semantic-search? s)
                 (do (is (<= (count r) 5))
                     (is (every? map? r))
                     (is (every? :id r))
                     (is (every? #(or (nil? (:score %)) (number? (:score %))) r)))
                 (do (is (empty? r))
                     (is (not-any? :score r)))))))}

   ;; ---- role protocols ----------------------------------------------------
   {:id :analytics :section :roles
    :doc "IMemoryStoreWithAnalytics: log-access! counts, record-feedback! counts, get-helpfulness-ratio shape."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (when (satisfies? ports/IMemoryStoreWithAnalytics s)
               (let [e (make-entry {:content "analytics"})]
                 (ports/add-entry! s e)
                 (ports/log-access! s (:id e))
                 (ports/log-access! s (:id e))
                 (is (= 2 (:access-count (ports/get-entry s (:id e)))))
                 (ports/record-feedback! s (:id e) :helpful)
                 (ports/record-feedback! s (:id e) :helpful)
                 (ports/record-feedback! s (:id e) :unhelpful)
                 (let [got (ports/get-entry s (:id e))
                       r   (ports/get-helpfulness-ratio s (:id e))]
                   (is (= 2 (:helpful-count got)))
                   (is (= 1 (:unhelpful-count got)))
                   (is (= 2 (:helpful-count r)))
                   (is (= 1 (:unhelpful-count r)))
                   (is (= 3 (:total r)))
                   (is (< (Math/abs (- (double (:ratio r)) (/ 2.0 3.0))) 0.001)))))))}

   {:id :metadata-write :section :roles
    :doc "IMemoryStoreMetadataWrite: update-metadata! merges non-content fields, keeps :content, returns the merged entry, nil for an unknown id."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (when (satisfies? ports/IMemoryStoreMetadataWrite s)
               (let [e (make-entry {:content "meta" :tags ["a"] :project-id "p-meta"})]
                 (ports/add-entry! s e)
                 (let [r   (ports/update-metadata! s (:id e) {:tags ["a" "z"] :project-id "p-meta2"})
                       got (ports/get-entry s (:id e))]
                   (is (map? r))
                   (is (tags= ["a" "z"] (:tags got)))
                   (is (= "p-meta2" (:project-id got)))
                   (is (= "meta" (:content got))))
                 (is (nil? (ports/update-metadata! s (str "missing-" (token)) {:tags ["q"]})))))))}

   {:id :staleness :section :roles
    :doc "IMemoryStoreWithStaleness: update-staleness! sets alpha and beta; get-stale-entries thresholds on beta/(alpha+beta); propagate-staleness! returns nil or an integer."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (when (satisfies? ports/IMemoryStoreWithStaleness s)
               (let [stale (make-entry {:content "stale"})
                     fresh (make-entry {:content "fresh"})]
                 (seed! s [stale fresh])
                 (ports/update-staleness! s (:id stale) {:staleness-alpha 1 :staleness-beta 9})
                 (ports/update-staleness! s (:id fresh) {:staleness-alpha 9 :staleness-beta 1})
                 (let [got  (ports/get-entry s (:id stale))
                       rows (ids-of (ports/get-stale-entries s 0.5 {}))]
                   (is (= 1 (:staleness-alpha got)))
                   (is (= 9 (:staleness-beta got)))
                   (is (contains? rows (:id stale)))
                   (is (not (contains? rows (:id fresh)))))
                 (let [r (ports/propagate-staleness! s (:id stale) 0)]
                   (is (or (nil? r) (integer? r))))))))}

   {:id :batch :section :roles
    :doc "IMemoryStoreBatch: get-entries returns the known entries and omits missing ids."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (when (satisfies? ports/IMemoryStoreBatch s)
               (let [a (make-entry) b (make-entry)]
                 (seed! s [a b])
                 (let [rows (ports/get-entries s [(:id a) (:id b) (str "missing-" (token))])]
                   (is (sequential? rows))
                   (is (= #{(:id a) (:id b)} (ids-of rows))))
                 (is (empty? (ports/get-entries s [])))))))}

   {:id :routing :section :roles
    :doc "IMemoryStoreWithRouting: target-collection-for is nil or a string; relocate-entry! of an unknown id reports :moved? false."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (when (satisfies? ports/IMemoryStoreWithRouting s)
               (let [t (ports/target-collection-for s (make-entry))]
                 (is (or (nil? t) (string? t))))
               (let [r (ports/relocate-entry! s (str "missing-" (token)))]
                 (is (map? r))
                 (is (false? (boolean (:moved? r))))))))}

   {:id :temporal :section :roles
    :doc "IMemoryStoreTemporal: history-entry is a sequential; asof-entry of an unknown id is nil."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (when (satisfies? ports/IMemoryStoreTemporal s)
               (let [e (make-entry)]
                 (ports/add-entry! s e)
                 (is (sequential? (ports/history-entry s (:id e))))
                 (is (nil? (ports/asof-entry s (str "missing-" (token)) (str (Instant/now)))))))))}

   {:id :liveness :section :roles
    :doc "IMemoryStoreLiveness: -probe! is truthy on a connected store; -await-reconnect! answers a boolean without throwing."
    :run (fn [ctx]
           (let [s (fresh ctx)]
             (when (satisfies? ports/IMemoryStoreLiveness s)
               (is (ports/-probe! s))
               (is (boolean? (ports/-await-reconnect! s 10))))))}])

(def case-ids
  "The case ids, in run order."
  (mapv :id cases))

;;; ============================================================================
;;; Runners
;;; ============================================================================

(defn- case-by-id [id]
  (or (some #(when (= id (:id %)) %) cases)
      (throw (ex-info (str "Unknown conformance case: " id) {:id id :known case-ids}))))

(defn run-case
  "Run the case ID against (MAKE-STORE) with OPTS inside the current
   clojure.test context. Returns :ran, or a {:skipped id :reason ...} map
   when OPTS skips it or its :requires-opt key is absent."
  [make-store opts id]
  (let [{:keys [requires-opt run doc]} (case-by-id id)
        skip-reason (get-in opts [:skip id])]
    (cond
      skip-reason
      (do (println "conformance: skipping" id "-" skip-reason)
          {:skipped id :reason skip-reason})

      (and requires-opt (not (contains? opts requires-opt)))
      (do (println "conformance: skipping" id "- opts lack" requires-opt)
          {:skipped id :reason (str "opts lack " requires-opt)})

      :else
      (do (testing (str (name id) ": " doc)
            (run {:make-store make-store :opts opts}))
          :ran))))

(defn run-conformance
  "Run every case against (MAKE-STORE) with OPTS inside the current
   clojure.test context. Returns {:ran [ids] :skipped {id reason}}."
  [make-store opts]
  (reduce (fn [acc id]
            (let [r (run-case make-store opts id)]
              (if (= :ran r)
                (update acc :ran conj id)
                (assoc-in acc [:skipped id] (:reason r)))))
          {:ran [] :skipped {}}
          case-ids))

(defmacro defconformance
  "Emit one clojure.test deftest per case, named PREFIX-<case-id>, each
   running that case against (MAKE-STORE) with OPTS."
  [prefix make-store opts]
  `(do ~@(for [id case-ids]
           `(t/deftest ~(symbol (str (name prefix) "-" (name id)))
              (run-case ~make-store ~opts ~id)))))
