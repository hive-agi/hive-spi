(ns hive-spi.memory.receipt
  "Optional acknowledged-add capability. Kept separate from core CRUD so
   stores and decorators may opt in without changing add-entry!'s id contract.")

(defonce ^:private defined? (atom false))

(when (compare-and-set! defined? false true)
  (defprotocol IMemoryStoreAddReceipt
    "A write receipt is a snapshot of the acknowledged stored representation,
     not the input entry and not a cache. Decorators must apply the same read
     transformation as get-entry, or leave the capability unsupported."
    (-add-entry-returning! [this entry]
      "Write ENTRY once. Success returns {:id string :entry map}, where :entry
       equals get-entry's persisted normalization immediately after this write
       (before subsequent writes), including generated fields and defaults.
       Never expose vectors, ciphertext or transient embed input to a caller
       whose get-entry would not expose them. Queued/refused writes return the
       ordinary add-entry! queued/failure map unchanged, without :entry.
       Unsupported stores must be called via add-entry! followed by get-entry;
       callers must not synthesize a receipt from the input.")))
