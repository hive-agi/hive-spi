(ns hive-spi.kg.protocol
  "SPI contracts for Knowledge-Graph storage backends. Leaf — no deps, so any
   backend implements it without pulling hive-mcp. Active-store slot management
   stays in hive-mcp.protocols.kg.")
;; SPDX-License-Identifier: MIT

(defprotocol IKGStore
  "Storage backend protocol for the Knowledge Graph."
  (ensure-conn! [this] "Ensure the connection is initialized.")
  (transact! [this tx-data] "Transact data into the store.")
  (query [this q] [this q inputs] "Datalog query against the current DB snapshot.")
  (entity [this eid] "Entity by id.")
  (entid [this lookup-ref] "Resolve a lookup ref to an entity id.")
  (pull-entity [this pattern eid] "Pull an entity with a pull pattern.")
  (eids-by-attr [this attr] "Lazy seq of entity ids having attr (attribute-first index).")
  (db-snapshot [this] "Current database snapshot value.")
  (reset-conn! [this] "Close and re-open the connection. NON-DESTRUCTIVE.")
  (close! [this] "Close the connection and release resources. NON-DESTRUCTIVE."))

(defprotocol IPersistentKGStore
  "Optional extension for on-disk backends. Ephemeral backends (DataScript) do
   not satisfy this."
  (delete-database! [this confirm]
    "DESTRUCTIVE — delete the on-disk database. confirm must be :i-mean-it or throw."))

(defprotocol ITemporalKGStore
  "Optional extension for temporal queries."
  (history-db [this] "DB containing all historical facts.")
  (as-of-db [this tx-or-time] "DB as of a point in time.")
  (since-db [this tx-or-time] "Facts added since a point in time."))

(defn kg-store? [x] (satisfies? IKGStore x))
(defn persistent-store? [x] (satisfies? IPersistentKGStore x))
(defn temporal-store? [x] (satisfies? ITemporalKGStore x))

(defrecord NoopKGStore []
  IKGStore
  (ensure-conn! [_this] nil)
  (transact! [_this _tx-data] nil)
  (query [_this _q] #{})
  (query [_this _q _inputs] #{})
  (entity [_this _eid] nil)
  (entid [_this _lookup-ref] nil)
  (pull-entity [_this _pattern _eid] nil)
  (eids-by-attr [_this _attr] ())
  (db-snapshot [_this] nil)
  (reset-conn! [_this] nil)
  (close! [_this] nil))

(defn noop-store [] (->NoopKGStore))
