(ns hive-spi.working-memory.entry
  "Pure helpers over the working-memory entry every store shares: workspace
   and composite keying, entry construction and normalization, the CAS
   predicate, revision stamping, supersession, and the delta comparison. No
   I/O, no state. A store that keys, stamps or diffs entries itself uses these
   so its semantics equal the reference in-memory store's."
  (:require [hive-spi.memory.ids :as ids]
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
  #?(:clj (ids/generate-id)
     :cljs (ids/generate-id)))

(defn- now []
  #?(:clj (ids/iso-timestamp)
     :cljs (ids/iso-timestamp)))

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
   the key held before (or nil), so :revision is `next-revision` and :updated
   is refreshed. Pure apart from the timestamp source."
  [entry current]
  (assoc entry
         :revision (next-revision current)
         :updated (now)))

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

(defn delta-of
  "The Delta over ENTRIES a reader holding CURSOR (a {key revision} map, or
   nil) has not seen, plus the cursor to carry forward. Every entry the reader
   has not seen is included in full; the answered cursor is CURSOR advanced
   with each entry's current revision. Pure."
  [workspace-id entries cursor]
  (let [cursor (or cursor {})
        seen (filter #(changed-since? % (get cursor (:key %))) entries)
        next-cursor (reduce (fn [c e] (assoc c (:key e) (:revision e))) cursor entries)]
    {:workspace-id workspace-id
     :entries (vec seen)
     :cursor next-cursor}))

(defn terminal?
  "True when ENTRY's status is terminal (:settled or :abandoned). A local
   re-statement of lifecycle/terminal? so this namespace stays a leaf that
   does not require the lifecycle table. Pure."
  [entry]
  (contains? #{:settled :abandoned} (:status entry)))

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
(m/=> delta-of [:=> [:cat ports/WorkspaceId [:maybe [:sequential :map]] [:maybe ports/Cursor]] ports/Delta])
(m/=> terminal? [:=> [:cat [:maybe :map]] :boolean])
