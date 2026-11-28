(ns hive-spi.working-memory.entry
  "Pure helpers over the working-memory entry every store shares: workspace
   and composite keying, entry construction and normalization, the CAS
   predicate, revision stamping, supersession, and the delta comparison. No
   I/O, no state. A store that keys, stamps or diffs entries itself uses these
   so its semantics equal the reference in-memory store's."
  (:require [hive-spi.memory.ids :as ids]
            [hive-spi.schema.typed-contract :as tc]
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
   :status defaulted to :draft, :revision defaulted to 0, :created and
   :updated stamped when absent.

   :id is MINTED, never taken from the writer: whatever ENTRY carries is
   replaced, so identity is the store's to grant and no writer can make two
   keys share one EntryId. `stamp` then keeps the key's id from the entry the
   key already holds, which is what makes an id stable across a key's
   revisions.

   The result always conforms, or this REFUSES: an ENTRY missing a field the
   store cannot invent (:workspace-id, :key, :claim, :owner) throws ex-info
   {:error :wm/invalid-entry}. Answering a map the store then has to reject
   would only move the failure later. Pure apart from the id and timestamp
   sources it reads."
  [entry]
  (let [t (now)
        e (assoc entry
                 :id (default-id)
                 :status (or (:status entry) :draft)
                 :revision (or (:revision entry) 0)
                 :created (or (:created entry) t)
                 :updated (or (:updated entry) t))]
    (if (m/validate ports/WorkingEntry e)
      e
      (throw (ports/refusal
              :wm/invalid-entry "entry does not conform"
              {:explanation (try (m/explain ports/WorkingEntry e)
                                 (catch #?(:clj Exception :cljs :default :cljr Exception :default :default) _ nil))})))))

(defn valid?
  "True when ENTRY is a conformant WorkingEntry once the store-side fields are
   filled. Accepts a partial map: the id and timestamps it needs are filled in
   locally, so validity is judged on what the writer supplied, not on what it
   left to the store. Pure; never throws."
  [entry]
  (try
    (let [t (ids/iso-timestamp)]
      (m/validate ports/WorkingEntry
                  (-> entry
                      (assoc :id (or (:id entry) (default-id))
                             :created (or (:created entry) t)
                             :updated (or (:updated entry) t))
                      (update :status #(or % :draft))
                      (update :revision #(or % 0)))))
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
   revision.

   This is a FENCING COUNTER, not a capability. It is small and contiguous on
   purpose: a monotonically increasing integer is what lets a store and its
   clients order two writes, and an adapter carrying its own counter
   (JetStream KV) stays compatible because only 'strictly greater' is
   required. It is therefore GUESSABLE, and the contract does not pretend
   otherwise — compare-and-set works because a caller must hand back a
   revision it READ, not because the number is hard to guess. Pure."
  [current]
  (if (nil? current) 1 (inc (long (:revision current)))))

(defn stamp
  "ENTRY as the store stores it after a successful write: CURRENT is the entry
   the key held before (or nil).

   :revision is `next-revision` and :updated is refreshed. Identity belongs to
   the KEY, not the writer: on an update both :id and :created come from
   CURRENT, so a key's id is stable across its revisions; on a CREATE the id
   is minted here and :created stamped, so two keys can never be made to share
   one EntryId. Pure apart from the id and timestamp sources."
  [entry current]
  (cond-> (assoc entry
                 :revision (next-revision current)
                 :updated (now))
    (some? current) (assoc :id (:id current) :created (:created current))
    (nil? current) (assoc :id (default-id) :created (or (:created entry) (now)))))

;; =============================================================================
;; Supersession and delta
;; =============================================================================

(defn supersedes?
  "True when ENTRY names OLDER's :id in :supersedes. Pure.

   This is a NAME test only, and deliberately NOT a permission: a store must
   never read it as 'this write may pass the terminal gate'. At a given key
   CURRENT is that key's own entry, so :supersedes naming CURRENT's id is a
   SAME-KEY self-supersession — the in-place mutation the contract forbids. A
   legitimate supersession lands at its OWN key, where CURRENT is nil or live,
   and names a DIFFERENT key's entry."
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

   PROGRESS IS GUARANTEED: when a cap is in force and nothing fits — the first
   unseen entry alone exceeds :max-chars, or :max-entries is 0 — the delta
   still delivers THAT ONE entry and advances the cursor past it. A reader
   following :truncated? therefore always advances and always terminates,
   however badly a store has sized its entries. When a cap holds entries back,
   :truncated? is true and the answered cursor advances ONLY over the
   delivered entries (CURSOR unchanged for the rest), so a follow-up delta-of
   from it delivers every remaining entry exactly once. Pure."
  ([workspace-id entries cursor]
   (delta-of workspace-id entries cursor nil))
  ([workspace-id entries cursor opts]
   (let [cursor (or cursor {})
         seen (filterv #(changed-since? % (get cursor (:key %))) entries)
         max-entries (get opts :max-entries)
         max-chars (get opts :max-chars)
         bounded? (or (some? max-entries) (some? max-chars))
         ;; the prefix that fits both caps; a nil cap is unbounded
         fitted (loop [remaining (seq seen) out [] chars 0]
                  (if-let [e (first remaining)]
                    (let [nchars (+ chars (entry-size e))
                          n (count out)]
                      (if (or (and max-entries (>= n max-entries))
                              (and max-chars (> nchars max-chars)))
                        out
                        (recur (next remaining) (conj out e) nchars)))
                    out))
         ;; The progress floor. A bounded delta over a non-empty backlog never
         ;; answers nothing: without this an entry larger than :max-chars (or a
         ;; :max-entries of 0) delivered nothing, reported :truncated? true and
         ;; left the cursor unmoved, so a drain following :truncated? spun.
         delivered (if (and bounded? (empty? fitted) (seq seen))
                     [(first seen)]
                     fitted)
         truncated? (and bounded? (< (count delivered) (count seen)))
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
  "What a caller hands `normalize`/`stamp`: a map that may be missing the
   store-side fields the store fills, and whose :id the store ignores."
  [:map
   [:id {:optional true} [:maybe :string]]
   [:workspace-id {:optional true} [:maybe ports/WorkspaceId]]
   [:key {:optional true} [:maybe ports/EntryKey]]
   [:claim {:optional true} [:maybe :string]]
   [:owner {:optional true} [:maybe :map]]
   [:status {:optional true} [:maybe ports/Status]]
   [:revision {:optional true} [:maybe nat-int?]]
   [:supersedes {:optional true} [:maybe :string]]
   [:sha {:optional true} [:maybe :string]]
   [:branch {:optional true} [:maybe :string]]
   [:provenance {:optional true} [:maybe :map]]
   [:created {:optional true} [:maybe :string]]
   [:updated {:optional true} [:maybe :string]]])

(def CurrentEntry
  "The entry a key already holds, as `stamp` and `cas-ok?` read it: the
   identity to keep and the revision to judge or advance."
  [:map [:id :string] [:created :string] [:revision nat-int?]])

(m/=> workspace-key [:=> [:cat ports/WorkspaceId ports/EntryKey] CompositeKey])
(tc/ann-op workspace-key)
(m/=> workspace-of [:=> [:cat :any] [:maybe :any]])
(tc/ann-op workspace-of)
(m/=> key-of [:=> [:cat :any] [:maybe :any]])
(tc/ann-op key-of)
(m/=> normalize [:=> [:cat [:maybe EntryInput]] ports/WorkingEntry])
(tc/ann-op normalize)
(m/=> valid? [:=> [:cat [:maybe EntryInput]] :boolean])
(tc/ann-op valid?)
(m/=> cas-ok? [:=> [:cat [:maybe CurrentEntry] ports/ExpectedRevision] :boolean])
(tc/ann-op cas-ok?)
(m/=> next-revision [:=> [:cat [:maybe CurrentEntry]] pos-int?])
(tc/ann-op next-revision)
(m/=> stamp [:=> [:cat [:maybe EntryInput] [:maybe CurrentEntry]] ports/WorkingEntry])
(tc/ann-op stamp)
(m/=> supersedes? [:=> [:cat [:maybe [:map [:supersedes {:optional true} [:maybe :string]]]] [:maybe [:map [:id :string]]]] :boolean])
(tc/ann-op supersedes?)
(m/=> changed-since? [:=> [:cat [:maybe [:map [:revision nat-int?]]] [:maybe nat-int?]] :boolean])
(tc/ann-op changed-since?)
(m/=> entry-size [:=> [:cat [:maybe :map]] nat-int?])
(tc/ann-op entry-size)
(m/=> delta-of [:function
                [:=> [:cat ports/WorkspaceId [:maybe [:sequential :map]] [:maybe ports/Cursor]] ports/Delta]
                [:=> [:cat ports/WorkspaceId [:maybe [:sequential :map]] [:maybe ports/Cursor] [:maybe ports/DeltaOpts]] ports/Delta]])
(tc/ann-op delta-of)
(m/=> terminal? [:=> [:cat [:maybe [:map [:status {:optional true} [:maybe ports/Status]]]]] :boolean])
(tc/ann-op terminal?)
