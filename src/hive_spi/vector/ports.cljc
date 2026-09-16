(ns hive-spi.vector.ports
  "The named-vector-collection port.

   `hive-spi.memory.ports` already owns the MEMORY ENTRY: a store that knows
   about entries, ids, staleness and helpfulness. This port is the other half,
   and it is deliberately smaller — a subsystem that owns a collection of its
   own (a plan index, a preset index) and wants nothing from the memory model
   beyond \"put vectors in a named place and query them back\".

   Without it such a subsystem has no seam, so it resolves the vendor's client
   directly and becomes untestable without that vendor running. The operations
   below are the vendor-neutral spelling of what those call sites already do.

   `opts` and the returned records are plain maps. A provider translates them
   to and from whatever its backend takes; a caller must not assume a vendor's
   key spellings leak through.

   Declared with no dependencies beyond the slot, so an implementation never
   has to depend on a host to learn what to implement.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations."
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -vectorcollectionstore-defined? (atom false))

(when (compare-and-set! -vectorcollectionstore-defined? false true)
  (defprotocol IVectorCollectionStore
    "A backend holding named collections of embedded records."

    (-configure [this opts]
      "Apply backend-level configuration. Returns THIS.")

    (-get-collection [this coll-name]
      "The collection named COLL-NAME, or nil when it does not exist.")

    (-create-collection [this coll-name opts]
      "Create COLL-NAME and return it. `opts` may carry :metadata and
       :get-or-create?; with :get-or-create? true an existing collection is
       returned rather than an error.")

    (-delete-collection [this coll]
      "Delete COLL. Returns nil.")

    (-add [this coll records opts]
      "Add RECORDS to COLL. Each record is a map of :id, :embedding,
       :document and :metadata. Returns nil.")

    (-get [this coll opts]
      "Records of COLL selected by `opts` — :ids, :where, :limit. Returns a
       seq of records in the same shape `-add` takes.")

    (-query [this coll embedding opts]
      "Records of COLL nearest to EMBEDDING. `opts` may carry :n-results and
       :where. Returns a seq of records, NEAREST FIRST, each additionally
       carrying :distance — an ASCENDING distance, never a similarity.")

    (-delete [this coll opts]
      "Delete records of COLL selected by `opts` (:ids, :where). Returns nil.")

    (-update [this coll records]
      "Update RECORDS of COLL, matched by :id. Returns nil.")))

(defonce ^:private store-slot
  (slot/single-slot {:validate #(satisfies? IVectorCollectionStore %)}))

(defn set-store!
  "Install STORE as the active vector-collection store. Returns STORE."
  [store]
  (slot/install! store-slot store))

(defn get-store
  "The active vector-collection store, or nil when none is installed."
  []
  (slot/current store-slot))

(defn store-set?
  "True iff a vector-collection store is installed."
  []
  (slot/present? store-slot))

(defn clear-store!
  "Remove the active vector-collection store. Returns nil."
  []
  (slot/clear! store-slot))
