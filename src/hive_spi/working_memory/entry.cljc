(ns hive-spi.working-memory.entry
  "Pure helpers over the working-memory entry every store shares: workspace
   and composite keying, entry construction and normalization, the CAS
   predicate, revision stamping, supersession, and the delta comparison. No
   I/O, no state. A store that keys, stamps or diffs entries itself uses these
   so its semantics equal the reference in-memory store's."
  (:require [hive-spi.memory.ids :as ids]
            [hive-spi.working-memory.lifecycle :as lifecycle]
            [hive-spi.working-memory.ports :as ports]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; =============================================================================
;; Keying
;; =============================================================================

(defn workspace-key
  "The store's composite key for WORKSPACE-ID and KEY: the pair a revision
   and a compare-and-set are scoped to. Pure."
  [workspace-id key]
  [workspace-id key])

(defn workspace-of
  "The workspace-id part of a COMPOSITE key, or nil. Pure."
  [composite]
  (first composite))

(defn key-of
  "The entry-key part of a COMPOSITE key, or nil. Pure."
  [composite]
  (second composite))

;; =============================================================================
;; Construction
;; =============================================================================

(defn- default-id []
  (ids/generate-id))

(defn- now []
  (ids/iso-timestamp))

(defn normalize
  "ENTRY with store-side defaults filled so it conforms to WorkingEntry:
   :id minted when absent, :status defaulted to :draft, :revision defaulted
   to 0, :owner required (a nil owner is left nil so valid-entry? fails
   loudly rather than inventing one), :created and :updated stamped when
   absent. Pure apart from the id and timestamp sources it reads."
  [entry]
  (let [t (now)]
    (assoc entry
           :id (or (:id entry) (default-id))
           :status (or (:status entry) :draft)
           :revision (or (:revision entry) 0)
           :created (or (:created entry) t)
           :updated (or (:updated entry) t))))

(defn valid?
  "True when ENTRY is a conformant WorkingEntry, after `normalize` fills the
   store defaults. Pure; never throws."
  [entry]
  (try
    (m/validate ports/WorkingEntry (normalize entry))
    (catch #?(:clj Exception :cljs :default :cljr Exception :default :default) _ false)))

;; =============================================================================
;; Compare-and-set
;; =============================================================================

(defn cas-ok?
  "True when a write to a key whose current entry is CURRENT may proceed
   under EXPECTED-REVISION (see ports/ExpectedRevision): nil EXPECTED means
   the key must not exist yet, an integer means the key's revision must equal
   it. Pure."
  [current expected-revision]
  (if (nil? expected-revision)
    (nil? current)
    (and (some? current) (= (:revision current) expected-revision))))

(defn next-revision
  "The revision a successful write to a key whose current entry is CURRENT
   should be stamped with: 1 when the key is new, else one past CURRENT's
   revision. Pure."
  [current]
  (if (nil? current) 1 (inc (long (:revision current)))))

(defn stamp
  "ENTRY as the store stores it after a successful write: CURRENT is the entry
   the key held before (or nil). :revision is `next-revision`, :updated is
   refreshed, and the key's IDENTITY is preserved — :id and :created come from
   CURRENT, so a key's id is stable across its revisions whatever :id the
   writer supplied. Pure apart from the timestamp source."
  [entry current]
  (cond-> (assoc entry
                 :revision (next-revision current)
                 :updated (now))
    (some? current) (assoc :id (:id current) :created (:created current))))

;; =============================================================================
;; Supersession and delta
;; =============================================================================

(defn supersedes?
  "True when ENTRY supersedes OLDER: ENTRY's :supersedes names OLDER's :id.
   Pure."
  [entry older]
  (and (some? older)
       (= (:supersedes entry) (:id older))))

(defn changed-since?
  "True when ENTRY is one a reader holding REVISION has not seen: REVISION is
   nil (never read) or lower than ENTRY's :revision. A reader that already saw
   the current revision is not re-sent the entry. Pure."
  [entry revision]
  (or (nil? revision)
      (> (long (:revision entry)) (long revision))))

(defn entry-size
  "The rendered size of ENTRY in characters: the length of its pr-str. The
   unit a :max-chars delta budget is measured in. Pure."
  [entry]
  (count (pr-str entry)))

(defn delta-of
  "The Delta over ENTRIES a reader holding CURSOR (a {key revision} map, or
   nil) has not seen, plus the cursor to carry forward. Every entry the reader
   has not seen is included in full, unless OPTS bounds the answer:
   :max-entries caps the count and :max-chars caps the summed `entry-size`.
   When a cap holds entries back, :truncated? is true and the answered cursor
   advances ONLY over the delivered entries (CURSOR unchanged for the rest),
   so a follow-up delta-of from it delivers every remaining entry exactly
   once. Pure."
  ([workspace-id entries cursor]
   (delta-of workspace-id entries cursor nil))
  ([workspace-id entries cursor opts]
   (let [cursor (or cursor {})
         seen (filterv #(changed-since? % (get cursor (:key %))) entries)
         max-entries (get opts :max-entries)
         max-chars (get opts :max-chars)
         ;; take the prefix that fits both caps; a nil cap is unbounded
         delivered
         (loop [remaining (seq seen) out [] chars 0]
           (if-let [e (first remaining)]
             (let [nchars (+ chars (entry-size e))
                   n (count out)]
               (if (or (and max-entries (>= n max-entries))
                       (and max-chars (> nchars max-chars)))
                 out
                 (recur (next remaining) (conj out e) nchars)))
             out))
         truncated? (and (some? (or max-entries max-chars))
                         (< (count delivered) (count seen)))
         next-cursor (reduce (fn [c e] (assoc c (:key e) (:revision e))) cursor delivered)]
     (cond-> {:workspace-id workspace-id
              :entries delivered
              :cursor next-cursor}
       truncated? (assoc :truncated? true)))))

(defn terminal?
  "True when ENTRY's status is terminal (:settled or :abandoned), read from the
   lifecycle table so there is ONE definition of terminal. A test pins the two
   equal. Pure."
  [entry]
  (lifecycle/terminal? (:status entry)))

;; =============================================================================
;; Contracts
;; =============================================================================

(def CompositeKey
  "A [workspace-id key] pair."
  [:tuple ports/WorkspaceId ports/EntryKey])

(def EntryInput
  "What a caller hands `normalize`: a map that may be missing the store-side
   fields `normalize` fills."
  [:map
   [:workspace-id {:optional true} [:maybe ports/WorkspaceId]]
   [:key {:optional true} [:maybe ports/EntryKey]]
   [:claim {:optional true} [:maybe :string]]
   [:owner {:optional true} [:maybe :map]]
   [:status {:optional true} [:maybe ports/Status]]
   [:revision {:optional true} [:maybe nat-int?]]])

(m/=> workspace-key [:=> [:cat ports/WorkspaceId ports/EntryKey] CompositeKey])
(m/=> workspace-of [:=> [:cat :any] [:maybe :any]])
(m/=> key-of [:=> [:cat :any] [:maybe :any]])
(m/=> normalize [:=> [:cat [:maybe EntryInput]] [:map]])
(m/=> valid? [:=> [:cat [:maybe EntryInput]] :boolean])
(m/=> cas-ok? [:=> [:cat [:maybe :map] ports/ExpectedRevision] :boolean])
(m/=> next-revision [:=> [:cat [:maybe :map]] pos-int?])
(m/=> stamp [:=> [:cat [:maybe EntryInput] [:maybe :map]] [:map]])
(m/=> supersedes? [:=> [:cat [:maybe :map] [:maybe :map]] :boolean])
(m/=> changed-since? [:=> [:cat [:maybe :map] [:maybe nat-int?]] :boolean])
(m/=> entry-size [:=> [:cat [:maybe :map]] nat-int?])
(m/=> delta-of [:function
                [:=> [:cat ports/WorkspaceId [:maybe [:sequential :map]] [:maybe ports/Cursor]] ports/Delta]
                [:=> [:cat ports/WorkspaceId [:maybe [:sequential :map]] [:maybe ports/Cursor] [:maybe ports/DeltaOpts]] ports/Delta]])
(m/=> terminal? [:=> [:cat [:maybe :map]] :boolean])
