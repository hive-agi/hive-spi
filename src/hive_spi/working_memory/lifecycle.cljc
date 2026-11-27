(ns hive-spi.working-memory.lifecycle
  "The working-memory entry lifecycle, as DATA.

   Four statuses, two live and two terminal:

     :draft      \u2014 filed, not yet being worked
     :working    \u2014 claimed and in flight
     :settled    \u2014 done, and the thing to consolidate into long-term memory
     :abandoned  \u2014 dropped; consolidates to a single negative note

   The allowed transitions are a closed table (`transitions`), not a predicate
   scattered across callers, so 'which moves are legal' has one answer a test
   can read and an operator can print. A terminal status has NO outgoing
   transition: a :settled or :abandoned entry is immutable except by being
   SUPERSEDED, which is a new entry naming the old one, not a mutation of it
   (REQ 3 / REQ 6).

   `apply-transition` is the pure write: it returns the entry with the new
   status and a refreshed :updated, or throws a contract refusal when the move
   is not in the table. It touches no store and stamps no revision \u2014 the store
   owns the revision, this namespace owns the status.

   Releasing a claim whose owner died is NOT a move back to :draft. A dead
   owner releases by abandoning its entry (a terminal status) and filing a
   new claim, which names the old one with :supersedes; the table stays closed
   because a store never has to un-settle an entry in place."
  (:require [hive-spi.memory.ids :as ids]
            [hive-spi.working-memory.ports :as ports]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; =============================================================================
;; The table
;; =============================================================================

(def terminal-statuses
  "The statuses an entry does not leave: a terminal entry is immutable except
   by supersession."
  #{:settled :abandoned})

(def live-statuses
  "The statuses an entry may still move from."
  #{:draft :working})

(def transitions
  "The closed transition table: {from #{to ...}}. A move absent here is
   refused. :draft and :working may be settled or abandoned; a terminal status
   has no outgoing edge."
  {:draft   #{:working :settled :abandoned}
   :working #{:settled :abandoned}
   :settled #{}
   :abandoned #{}})

(def transition-list
  "The table as a sorted vector of {:from :to}, for introspection and for a
   test to enumerate every legal move."
  (vec (for [from (sort (keys transitions))
             to (sort (get transitions from))]
         {:from from :to to})))

(defn terminal?
  "True when STATUS is terminal (:settled or :abandoned). Pure."
  [status]
  (contains? terminal-statuses status))

(defn live?
  "True when STATUS may still move (:draft or :working). Pure."
  [status]
  (contains? live-statuses status))

(defn allowed?
  "True when the move FROM -> TO is in the table. Pure."
  [from to]
  (contains? (get transitions from #{}) to))

(defn allowed-transitions
  "The statuses ENTRY may move to next, sorted; empty for a terminal entry.
   Pure."
  [entry]
  (vec (sort (get transitions (:status entry) #{}))))

(defn- refusal [message data]
  (throw (ports/refusal :wm/invalid-transition message data)))

(defn apply-transition
  "ENTRY with its :status moved to TO and :updated refreshed, or a refusal
   ex-info {:error :wm/invalid-transition} when the move is not in the table.

   The move is judged against the ENTRY's current :status, so a caller cannot
   skip a state: :draft -> :settled is legal, :settled -> :working is not.
   A no-op move (FROM = TO) is refused: a transition is a change, and a store
   asked to write the same status again would mint a revision for nothing.
   Pure apart from the timestamp source it reads through `ports`."
  [entry to]
  (let [from (:status entry)]
    (when-not (contains? transitions from)
      (refusal (str "unknown status " (pr-str from)) {:status from}))
    (when (= from to)
      (refusal (str "entry is already " (pr-str to)) {:status from :to to}))
    (when-not (allowed? from to)
      (refusal (str "cannot move " (pr-str from) " -> " (pr-str to)
                    "; allowed: " (pr-str (allowed-transitions entry)))
               {:status from :to to :allowed (allowed-transitions entry)}))
    (assoc entry
           :status to
           :updated (ids/iso-timestamp))))

(defn settle
  "ENTRY moved to :settled. A thin, readable alias for the common move."
  [entry]
  (apply-transition entry :settled))

(defn abandon
  "ENTRY moved to :abandoned. A thin, readable alias for the common move."
  [entry]
  (apply-transition entry :abandoned))

(defn claim
  "ENTRY moved to :working: an agent has taken the claim. A thin, readable
   alias for the common move."
  [entry]
  (apply-transition entry :working))

;; =============================================================================
;; Contracts
;; =============================================================================

(def StatusSet
  "A set of statuses."
  [:set ports/Status])

(def Transition
  "One row of the table."
  [:map {:closed true}
   [:from ports/Status]
   [:to ports/Status]])

(m/=> terminal? [:=> [:cat :any] :boolean])
(m/=> live? [:=> [:cat :any] :boolean])
(m/=> allowed? [:=> [:cat :any :any] :boolean])
(m/=> allowed-transitions [:=> [:cat [:maybe :map]] [:vector ports/Status]])
(m/=> apply-transition [:=> [:cat [:maybe :map] ports/Status] [:map]])
(m/=> settle [:=> [:cat [:maybe :map]] [:map]])
(m/=> abandon [:=> [:cat [:maybe :map]] [:map]])
(m/=> claim [:=> [:cat [:maybe :map]] [:map]])
