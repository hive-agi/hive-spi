(ns hive-spi.memory.ports
  "Memory-store SPI — the IMemoryStore protocol family as pure contracts.

   Storage backends (Milvus, Chroma, Qdrant, Proximum, in-memory) extend
   these protocols; a host (hive-mcp) consumes them through a registry.
   They live in this SPI leaf so a backend can implement the contract
   WITHOUT compile-depending on any host — the memory-storage DIP seam.

   hive-mcp.protocols.memory and hive-mcp.protocols.memory-liveness
   re-export every protocol and method var below as plain `def` aliases.
   Do NOT re-`defprotocol` these names in a consumer: a second defprotocol
   mints a DISTINCT interface and every record implementing the original
   then fails `satisfies?` silently.

   Reload-safety: each defprotocol is wrapped in a defonce-guarded
   compare-and-set! so re-evaluating this namespace does not mint a fresh
   host interface class per reload.")

;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; IMemoryStore — core CRUD + search + lifecycle
;;; ============================================================================

(defonce ^:private -imemorystore-defined? (atom false))

(when (compare-and-set! -imemorystore-defined? false true)
  (defprotocol IMemoryStore
    "Storage backend protocol for memory entries."

    (connect! [this config]
      "Initialize connection to the storage backend.")

    (disconnect! [this]
      "Close connection and release backend resources.")

    (connected? [this]
      "Check if this store has an active connection.")

    (health-check [this]
      "Verify backend health and reachability.")

    (add-entry! [this entry]
      "Add a new memory entry to the store.")

    (get-entry [this id]
      "Get a memory entry by ID.")

    (update-entry! [this id updates]
      "Update an existing entry's attributes.")

    (delete-entry! [this id]
      "Delete an entry from the store.")

    (query-entries [this opts]
      "Query entries with filtering.

       Opts (map):
         :type             — entry type filter (e.g. \"note\", \"axiom\")
         :project-id       — single project scope
         :project-ids      — collection of project scopes (OR)
         :tags             — required tags (AND)
         :exclude-tags     — excluded tags
         :limit            — max rows returned
         :include-expired? — include expired entries (default false)
         :output-fields    — projection of field names
         :order-by         — [field direction] e.g. [:created :desc] | [:created :asc].
                             When set, returned rows are sorted by `field` in
                             `direction`. Backends without server-side ordering
                             (e.g. Milvus query-scalar) sort post-fetch — caller
                             MUST set `:limit` high enough to cover the desired
                             top-N. Unspecified ⇒ backend-native scan order.")

    (search-similar [this query-text opts]
      "Semantic similarity search.")

    (supports-semantic-search? [this]
      "Check if this store supports semantic search.")

    (cleanup-expired! [this]
      "Delete all expired entries.")

    (entries-expiring-soon [this days opts]
      "Get entries expiring within the given number of days.")

    (find-duplicate [this type content-hash opts]
      "Find entry with matching content-hash.")

    (store-status [this]
      "Get store status and configuration info.")

    (reset-store! [this]
      "Reset the store to empty state.")))

;;; ============================================================================
;;; IMemoryStoreWithAnalytics — optional analytics tracking
;;; ============================================================================

(defonce ^:private -iwithanalytics-defined? (atom false))

(when (compare-and-set! -iwithanalytics-defined? false true)
  (defprotocol IMemoryStoreWithAnalytics
    "Optional extension for analytics tracking."

    (log-access! [this id]
      "Log an access event for an entry.")

    (record-feedback! [this id feedback]
      "Record helpfulness feedback for an entry.")

    (get-helpfulness-ratio [this id]
      "Calculate helpfulness ratio for an entry.")))

;;; ============================================================================
;;; IMemoryStoreMetadataWrite — metadata-only writes that preserve embedding
;;; ============================================================================

(defonce ^:private -imetawrite-defined? (atom false))

(when (compare-and-set! -imetawrite-defined? false true)
  (defprotocol IMemoryStoreMetadataWrite
    "Optional extension for metadata-only writes that preserve the
     existing embedding. Implementors MUST NOT re-run the embedder."

    (update-metadata! [this id updates]
      "Update non-content fields on entry `id` without re-embedding.

       `updates` is a map of fields to merge into the existing entry —
       must NOT contain :content or :type (those change embedding
       identity; use update-entry! instead). Implementations are
       expected to read the existing record (including its :embedding
       vector), merge `updates`, and upsert with the retrieved vector.

       Returns the merged entry on success, nil if id not found.")))

;;; ============================================================================
;;; IMemoryStoreWithStaleness — optional staleness tracking
;;; ============================================================================

(defonce ^:private -iwithstaleness-defined? (atom false))

(when (compare-and-set! -iwithstaleness-defined? false true)
  (defprotocol IMemoryStoreWithStaleness
    "Optional extension for staleness tracking."

    (update-staleness! [this id staleness-opts]
      "Update staleness tracking fields for an entry.")

    (get-stale-entries [this threshold opts]
      "Get entries with staleness probability above threshold.")

    (propagate-staleness! [this source-id depth]
      "Propagate staleness from source entry to dependents.")))

;;; ============================================================================
;;; IMemoryStoreBatch — optional batched reads
;;; ============================================================================

(defonce ^:private -iwithbatch-defined? (atom false))

(when (compare-and-set! -iwithbatch-defined? false true)
  (defprotocol IMemoryStoreBatch
    "Optional extension for batched reads."

    (get-entries [this ids]
      "Fetch multiple entries by ID in a single backend round-trip.
       Returns a seq of entry maps (missing IDs omitted). Order is not
       guaranteed — callers must index by :id.")))

;;; ============================================================================
;;; IMemoryStoreWithRouting — optional multi-container routing
;;; ============================================================================

(defonce ^:private -iwithrouting-defined? (atom false))

(when (compare-and-set! -iwithrouting-defined? false true)
  (defprotocol IMemoryStoreWithRouting
    "Optional extension for backends with multi-container routing.

     Routing rules are owned by the backend; callers ask 'where should this
     entry live according to current config?' and 'move it if it is in the
     wrong place.' Used to recover from embedder/dimension drift without a
     manual collection-by-collection migration."

    (target-collection-for [this entry]
      "Resolve the canonical container (collection / index / shard / table)
       where `entry` should live according to current routing config.
       Returns a backend-specific identifier (string for Milvus, etc.) or
       nil when the backend has no routing concept.")

    (relocate-entry! [this id]
      "If the entry's current physical location differs from its target
       (per `target-collection-for`), move it: read existing, re-vectorize
       under the target's embedder, write to target, delete from source.
       No-op when entry is already canonical or when no entry exists for
       `id`. Returns {:moved? bool :from container :to container :id id}
       for observability.")))

;;; ============================================================================
;;; IMemoryStoreTemporal — bitemporal query extension
;;; ============================================================================

(defonce ^:private -iwithtemporal-defined? (atom false))

(when (compare-and-set! -iwithtemporal-defined? false true)
  (defprotocol IMemoryStoreTemporal
    "Bitemporal query extension for memory stores.
     Provides as-of, history, and between queries over immutable fact logs."

    (asof-entry [this id timestamp]
      "Return the value of entry `id` as it was known at `timestamp`.
       Returns nil if entry did not exist at that time.")

    (history-entry [this id]
      "Return seq of [timestamp value] pairs for all versions of entry `id`,
       ordered oldest-first.")

    (asof-query [this criteria timestamp]
      "Return entries matching `criteria` as they were known at `timestamp`.
       Criteria is a map with optional :type, :tags keys.")

    (between-query [this criteria t1 t2]
      "Return entries matching `criteria` that existed between t1 and t2.
       Criteria is a map with optional :type, :tags keys.")))

;;; ============================================================================
;;; IMemoryStoreLiveness — cross-store resilience / reconnect seam
;;; ============================================================================

(defonce ^:private -iliveness-defined? (atom false))

(when (compare-and-set! -iliveness-defined? false true)
  (defprotocol IMemoryStoreLiveness
    "Optional capability: drives the host resilience layer's reconnect
     path. Implementors own the probe RPC and the heal loop; the resilience
     layer only orchestrates them."

    (-probe! [store]
      "Issue the cheapest possible round-trip RPC to verify reachability.
       Return truthy on success. Throw on transport-fatal failure (the
       resilience layer interprets a throw as 'still dead, keep retrying').
       MUST be idempotent and side-effect-free at the application level —
       a single read-shaped RPC is the only allowed side effect.")

    (-kick-reconnect! [store]
      "Idempotent: drop the dead client, invalidate any liveness cache,
       and signal the store's heal loop to (re)start. Returns nil. Safe
       to call multiple times; subsequent calls while a heal loop is
       running should no-op.")

    (-await-reconnect! [store budget-ms]
      "Block up to `budget-ms` for the heal loop to verify recovery via a
       probe round-trip. Return true if the store is alive at the end of
       the wait, false on timeout. MUST NOT throw — the resilience layer
       wants a clean boolean, never an exception.")))

;;; ============================================================================
;;; Decay-protection registry — synthesis afterlife seam
;;; ============================================================================
;;; A backend's cleanup-expired! consults `protected-ids` before deleting so
;;; that entries a live synthesis references outlive their own :expires. The
;;; provider is owned by the host (which alone can read the KG); the SPI leaf
;;; only holds the seam so backends need no host dependency.

(defonce ^:private -protection-provider (atom nil))

(defn register-protection-provider!
  "Install the host's afterlife provider — a 0-arg fn returning a coll of
   memory-entry ids that must survive expiry-reaping. Idempotent (last writer
   wins). Pass nil to clear. Returns the installed fn."
  [f]
  (reset! -protection-provider f))

(defn protected-ids
  "Set of memory-entry ids currently under synthesis afterlife: expired
   entries in this set MUST NOT be reaped by cleanup-expired!. Never throws —
   no provider, or any provider failure, yields #{} so the reaper still runs."
  []
  (if-let [f @-protection-provider]
    (try (set (f)) (catch Throwable _ #{}))
    #{}))
