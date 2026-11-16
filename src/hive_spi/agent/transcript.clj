(ns hive-spi.agent.transcript
  "Transcript-query port: the READ side of an agent's transcript, host-neutral.

   This is the read half of hive-agent's ITranscriptStore. Only queries are
   published here; append, fork and close stay with the store's owner, because
   run-control and its consumers are read-only against the transcript.
   Resolution is PATH-BLIND: a query names an agent-id and a cursor, never a
   file, a directory or a database handle. That is what keeps the port
   host-neutral.

   EVIDENCE. A store may bound what it keeps (hive-agent truncates content at
   10k chars). An entry whose content was bounded must say so with
   :transcript/truncated? true, and `evidence?` is false for it. A consumer
   that cites a transcript as evidence filters through `evidence?`, so a
   bounded journal payload is never presented as the full record.

   Methods return hive-dsl.result Results.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded."
  (:require [hive-spi.agent.run-control :as rc]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Shapes
;; =============================================================================

(def Role
  "Who produced a transcript entry."
  [:enum :user :assistant :tool :system :unknown])

(def TranscriptEntry
  "One normalized transcript entry, as every store backend returns it."
  [:map
   [:transcript/agent-id rc/AgentId]
   [:transcript/turn [:int {:min 0}]]
   [:transcript/role {:optional true} Role]
   [:transcript/content {:optional true} :string]
   [:transcript/timestamp {:optional true} :int]
   [:transcript/truncated? {:optional true} :boolean]])

(def Entries
  "Entries ordered by :transcript/turn, ascending."
  [:vector TranscriptEntry])

;; =============================================================================
;; Port
;; =============================================================================

(defonce ^:private -itranscriptquery-defined? (atom false))

(when (compare-and-set! -itranscriptquery-defined? false true)
  (defprotocol ITranscriptQuery
    "Read-only, path-blind access to agent transcripts."

    (transcript-since [this transcript-ref]
      "The entries of (:agent-id TRANSCRIPT-REF) whose :transcript/turn is
       strictly greater than (:turn (:cursor TRANSCRIPT-REF)), ordered by turn
       ascending. Returns Result<Entries>. An agent with no entries answers
       (ok []), not an err.")

    (query-by-agent [this agent-id]
      "Every entry of AGENT-ID, ordered by turn ascending. Returns
       Result<Entries>.")))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn evidence?
  "True when ENTRY may be cited as evidence: it is not marked
   :transcript/truncated?."
  [entry]
  (not (true? (:transcript/truncated? entry))))

(defn last-cursor
  "The cursor after the last of ENTRIES, or FROM when ENTRIES is empty. The
   next transcript-since from it returns only newer entries."
  [from entries]
  (if-let [t (some->> (seq entries) (map :transcript/turn) (apply max))]
    {:turn (max t (:turn from))}
    from))

(def valid-entry? (m/validator TranscriptEntry))
(def valid-entries? (m/validator Entries))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> evidence? [:=> [:cat [:maybe [:map [:transcript/truncated? {:optional true} :any]]]]
                 :boolean])
(m/=> last-cursor [:=> [:cat rc/Cursor [:sequential TranscriptEntry]] rc/Cursor])
