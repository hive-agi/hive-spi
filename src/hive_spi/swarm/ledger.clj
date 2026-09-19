(ns hive-spi.swarm.ledger
  "ILedgerStore: the durable, append-only swarm ledger. A flat monotonic
   chain of events {:ledger/seq :ledger/ts :ledger/type :ledger/stream
   :ledger/payload}, read on demand by seq cursor, type, time range or tail.
   Reads are bounded by :limit (default `default-limit`).

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded. Re-evaluating this namespace will not orphan existing
   implementations. Consumers must NOT re-defprotocol this name.")

;; SPDX-License-Identifier: MIT

(def ^:const default-limit
  "Cap on entries pulled per read. Override via :limit; pass :limit nil to
   disable."
  500)

(defonce ^:private -iledgerstore-defined? (atom false))

(when (compare-and-set! -iledgerstore-defined? false true)
  (defprotocol ILedgerStore
    "Append-only durable ledger. Reads are bounded and ordered by :ledger/seq."
    (append! [store event]
      "event = {:type keyword (required) :payload map (required) :stream string (optional)}.
     Allocates the next monotonic :ledger/seq, persists write-through.
     Returns {:seq long :type kw :ts long} or {:error ...}.")
    (read-since [store cursor-seq opts]
      "Entries with :ledger/seq > cursor-seq, ascending, capped by (:limit opts).
     (:type opts) optionally filters by event type. Returns a vector of decoded
     entries (payload parsed) or [].")
    (read-time-range [store start-ms end-ms opts]
      "Entries with start-ms <= :ledger/ts <= end-ms, capped by (:limit opts).")
    (read-tail [store n]
      "The most recent ~n entries, ascending by seq.")
    (latest-seq [store]
      "Highest seq allocated by this store instance (long).")
    (close! [store]
      "Release the store's resources. Returns {:closed? true}.")))
