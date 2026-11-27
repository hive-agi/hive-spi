(ns hive-spi.working-memory.ports
  "The working-memory contract: a shared, fast-changing workspace a swarm
   subgroup coordinates in, and the seams that keep it out of any one host.

   One WORKSPACE per kanban card or epic, never per agent. A workspace holds
   entries keyed by an entry key, and the store answers each key with a
   per-key REVISION \u2014 which is the whole point: a revision is what makes
   compare-and-set possible, and a compare-and-set is how two agents writing
   the same key resolve without a lock. Entries are structured plain data and
   are NEVER embedded on write; embedding is consolidation's cost, not the
   writer's.

     IWorkingMemoryStore  get-entry, put-cas!, watch!, delta-since
     IConsolidation       consolidate! \u2014 fold ONE terminal entry into long-term memory
     IActivation          activate \u2014 the bundle a spawned ling starts from

   Nothing here owns a thread, a connection or a bucket. The port is data in,
   data out, so WHERE the entries live (a JetStream KV bucket, a datalevin
   table, an in-memory map in a test) is an addon's business and never this
   library's. hive-spi.working-memory.conformance is the executable form of
   this contract; hive-spi.working-memory.entry carries the pure entry
   helpers and hive-spi.working-memory.lifecycle the status transitions.

   Reload-safety: `defprotocol` is not idempotent, so each declaration is
   guarded \u2014 re-evaluating this namespace will not orphan existing
   implementations."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; =============================================================================
;; Vocabulary
;; =============================================================================

(def ErrorKeyword
  "The :error keywords the working-memory contract speaks. A refusal is an
   ex-info carrying one of these under :error, and it names keys, never
   values."
  [:enum
   :wm/invalid-entry
   :wm/invalid-transition
   :wm/terminal
   :wm/revision-conflict
   :wm/not-found
   :wm/store-unavailable
   :wm/no-consolidator
   :wm/no-activator])

(def Status
  "An entry's lifecycle status. `:draft` and `:working` are live; `:settled`
   and `:abandoned` are terminal, and a terminal entry is immutable except by
   being superseded. See hive-spi.working-memory.lifecycle for the allowed
   transitions, which are data."
  [:enum :draft :working :settled :abandoned])

(def WorkspaceId
  "The identity of a workspace: a kanban card id, an epic id, or a slug a
   subgroup agrees on. Non-blank; compared as given."
  [:string {:min 1 :max 128}])

(def EntryKey
  "An entry's key within its workspace: the name a claim is filed under, and
   the unit a revision and a compare-and-set are scoped to."
  [:string {:min 1 :max 128}])

(def EntryId
  "An entry id, minted by hive-spi.memory.ids/generate-id: the clock's
   14-digit stamp, a dash, eight lowercase hex digits."
  ;; :gen is portable: malli cannot generate a bare :re without test-chuck, so
  ;; a generated int is padded to the 14-digit stamp and a fixed hex tail.
  [:re {:gen/schema [:int {:min 0 :max 99999999999999}]
        :gen/fmap (fn [n]
                    (let [s (str n)]
                      (str (apply str (repeat (- 14 (count s)) "0")) s "-abcdef01")))}
   #"^\d{14}-[0-9a-f]{8}$"])

(def AgentId
  "The agent that owns an entry: a ling id, a coordinator id, or a role name."
  [:string {:min 1 :max 128}])

(def Owner
  "Who owns an entry. `:agent` is required \u2014 an entry nobody owns is one
   nobody can be asked about \u2014 and `:session` narrows it to one session of
   that agent when the host has one."
  [:map {:closed true}
   [:agent AgentId]
   [:session {:optional true} [:maybe :string]]])

(def Provenance
  "Where an entry came from. `:run-id` names the REPLAY ledger run that
   produced it (decision 20261127151801-71886065); `:actor` names the role
   that wrote it when that is not the owner."
  [:map {:closed true}
   [:run-id {:optional true} [:maybe :string]]
   [:actor {:optional true} [:maybe :string]]])

(def Timestamp
  "An ISO-8601 instant, as entries stamp :created and :updated."
  [:re {:gen/schema [:int {:min 0 :max 59}]
        :gen/fmap (fn [n] (str "2026-01-01T00:00:" (when (< n 10) "0") n "Z"))}
   #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*$"])

(def WorkingEntry
  "One entry of a working-memory workspace, as a store holds it.

   Shape:
     {:id           EntryId      (required) \u2014 minted once, stable across revisions
      :workspace-id WorkspaceId  (required) \u2014 the card or epic
      :key          EntryKey     (required) \u2014 the revision/CAS unit
      :claim        string       (required) \u2014 what this entry asserts
      :owner        Owner        (required) \u2014 who owns it
      :status       Status       (required) \u2014 :draft | :working | :settled | :abandoned
      :revision     nat-int?     (required) \u2014 STAMPED BY THE STORE, never the writer
      :sha          string?      (optional) \u2014 the commit this entry speaks about
      :branch       string?      (optional)
      :supersedes   EntryId?     (optional) \u2014 the entry this one replaces
      :provenance   Provenance?  (optional)
      :created      Timestamp    (required)
      :updated      Timestamp    (required)}

   A caller hands a put-cas! an entry whose :revision it read; the store
   answers the entry it actually stored, whose :revision is authoritative."
  [:map {:closed true}
   [:id EntryId]
   [:workspace-id WorkspaceId]
   [:key EntryKey]
   [:claim [:string {:min 1}]]
   [:owner Owner]
   [:status Status]
   [:revision nat-int?]
   [:sha {:optional true} [:maybe :string]]
   [:branch {:optional true} [:maybe :string]]
   [:supersedes {:optional true} [:maybe EntryId]]
   [:provenance {:optional true} [:maybe Provenance]]
   [:created Timestamp]
   [:updated Timestamp]])

(def Cursor
  "What a reader has already seen: {key -> the revision it saw for that key}.
   A key absent from the cursor has never been read, so delta-since answers
   it. This is a per-key cursor on purpose \u2014 a timestamp or an append-only
   id would MISS a mutation of an existing key, which is exactly the change
   working memory carries."
  [:map-of EntryKey nat-int?])

(def Delta
  "What delta-since answers: the entries of one workspace a reader has not
   seen at the revision it holds, plus the cursor it should carry forward.
   `:entries` are full WorkingEntries, in no guaranteed order."
  [:map {:closed true}
   [:workspace-id WorkspaceId]
   [:entries [:vector WorkingEntry]]
   [:cursor Cursor]])

(def PutResult
  "What put-cas! answers.

   On success: {:ok? true :entry <the stored WorkingEntry>} \u2014 :entry carries
   the revision the store stamped, which is the caller's next expected
   revision. On refusal: {:ok? false :error <ErrorKeyword> :current <the entry
   that won, or nil>}, where :current is what the caller must now reconcile
   against. A successful write answers :entry and no :error; a refused one
   answers :error and, when the key exists, :current."
  [:map
   [:ok? :boolean]
   [:entry {:optional true} [:maybe WorkingEntry]]
   [:error {:optional true} [:maybe ErrorKeyword]]
   [:current {:optional true} [:maybe WorkingEntry]]])

(def ExpectedRevision
  "What a caller believes the key's current revision is: a revision it read,
   or nil for 'the key must not exist yet' (create). There is deliberately no
   'overwrite regardless' value \u2014 a blind write is the thing CAS removes."
  [:maybe nat-int?])

(def WatchHandle
  "The value watch! answers: a zero-arg cancel fn. Calling it stops the watch;
   calling it twice is safe."
  [:fn {:error/message "must be a zero-arg cancel fn"} ifn?])

(def ConsolidationReceipt
  "What consolidate! answers.

   On success: {:ok? true :long-term-id <id> :edges [{:relation kw :to id}]} \u2014
   the long-term entry the fold landed on and the KG edges it minted
   (`:derived-from`, `:supersedes`, or an abandonment note). On refusal:
   {:ok? false :error <ErrorKeyword>}."
  [:map
   [:ok? :boolean]
   [:long-term-id {:optional true} [:maybe :string]]
   [:edges {:optional true} [:maybe [:vector :map]]]
   [:error {:optional true} [:maybe ErrorKeyword]]])

(def Activation
  "What activate answers: the bundle a ling starts from when it is spawned on
   a workspace ref (REQ 7). `:entries` are the brief and the settled claims,
   `:refs` are the KG neighbours to read one or two hops out, and `:brief` is
   the rendered summary when the host renders one."
  [:map
   [:workspace-id WorkspaceId]
   [:entries [:vector WorkingEntry]]
   [:refs {:optional true} [:maybe [:vector :map]]]
   [:brief {:optional true} [:maybe :string]]])

;; =============================================================================
;; Protocols
;; =============================================================================

(defonce ^:private -iworkingmemorystore-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -iworkingmemorystore-defined? false true))
  (defprotocol IWorkingMemoryStore
    "Where a workspace's entries live. Every method is total on a well-formed
     argument: an absent key is nil, an empty delta is empty, never a throw.
     A refusal is an ex-info carrying an ErrorKeyword under :error."

    (get-entry [this workspace-id key]
      "The current WorkingEntry at [WORKSPACE-ID KEY], or nil when the key is
       absent. A read, with no side effect on the revision.")

    (put-cas! [this entry expected-revision]
      "Store ENTRY at [(:workspace-id entry) (:key entry)] when the key's
       current revision is EXPECTED-REVISION.

       EXPECTED-REVISION is the revision the caller read, or nil meaning the
       key must not exist yet (create). On success the store stamps a fresh
       :revision (one past the expected one, or 1 on create), sets :updated,
       stores the entry and answers {:ok? true :entry <stored>}. On a mismatch
       it writes nothing and answers {:ok? false :error :wm/revision-conflict
       :current <the entry that won, or nil>}. Throws ex-info
       {:error :wm/invalid-entry} when ENTRY does not conform \u2014 a store that
       persisted a malformed entry would poison every later reader.")

    (watch! [this workspace-id key f]
      "Call F with the new WorkingEntry whenever the entry at
       [WORKSPACE-ID KEY] is written, until the returned cancel fn is called.
       F is called with the stored entry, after the write is durable. Answers
       a zero-arg cancel fn (a WatchHandle); calling it twice is safe. A watch
       on an absent key is armed and fires on the first write.")

    (delta-since [this workspace-id cursor]
      "Every entry of WORKSPACE-ID a reader holding CURSOR has not seen: a key
       whose current revision is greater than the cursor's, or absent from the
       cursor. CURSOR nil means 'nothing seen', so every entry answers. Answers
       a Delta whose :cursor the reader carries into its next call. A key that
       was EDITED re-delivers \u2014 that is the mutation an append-only cursor
       would drop.")))

(defonce ^:private -iconsolidation-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -iconsolidation-defined? false true))
  (defprotocol IConsolidation
    "Folding a terminal entry out of the fast, cheap workspace and into
     long-term memory. Event-driven, never a timer: the host calls this when
     an entry reaches a terminal status."

    (consolidate! [this entry]
      "Fold ONE terminal ENTRY into long-term memory, minting the KG edges
       that tie the long-term fact back to its workspace and its predecessors.

       Only a terminal entry is consolidated: a :settled entry becomes a
       long-term fact carrying :derived-from and :supersedes edges, an
       :abandoned entry becomes a single negative note. A non-terminal entry
       is refused with {:ok? false :error :wm/terminal} rather than folded.
       This port does NOT embed: the host owns the embedding cost, so a
       consolidate! that would re-embed is the host's decision, not the
       contract's. Answers a ConsolidationReceipt.")))

(defonce ^:private -iactivation-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -iactivation-defined? false true))
  (defprotocol IActivation
    "Turning a workspace ref into the bundle a spawned ling starts from."

    (activate [this workspace-id opts]
      "The Activation for WORKSPACE-ID: the brief and the settled claims, then
       the KG neighbours one or two hops out, then a small axiom core. OPTS
       carries the host's knobs (a hop budget, a claim cap, an axiom core) and
       is host-defined; an empty map asks for the host's defaults. Answers an
       Activation, empty of entries when the workspace is unknown \u2014 an
       unknown workspace is an empty one, not an error.")))

;; =============================================================================
;; Contracts
;; =============================================================================

(def WorkingMemoryStore
  "Any IWorkingMemoryStore implementation."
  [:fn {:error/message "must satisfy IWorkingMemoryStore"}
   #(satisfies? IWorkingMemoryStore %)])

(def Consolidator
  "Any IConsolidation implementation."
  [:fn {:error/message "must satisfy IConsolidation"}
   #(satisfies? IConsolidation %)])

(def Activator
  "Any IActivation implementation."
  [:fn {:error/message "must satisfy IActivation"}
   #(satisfies? IActivation %)])

(defn refusal
  "The ex-info the contract throws when it refuses: message
   \"working-memory: MESSAGE\", data {:error ERROR} merged with DATA. Names
   keys, never values. Pure."
  [error message data]
  (ex-info (str "working-memory: " message) (merge {:error error} data)))

(defn valid-entry?
  "True when E is a conformant WorkingEntry. Pure; never throws."
  [e]
  (try (m/validate WorkingEntry e) (catch #?(:clj Exception :cljs :default :cljr Exception :default :default) _ false)))

(m/=> refusal [:=> [:cat ErrorKeyword :string [:maybe :map]] [:fn {:error/message "must be an ex-info"} #(some? (ex-data %))]])
(m/=> valid-entry? [:=> [:cat :any] :boolean])
