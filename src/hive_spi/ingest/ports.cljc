(ns hive-spi.ingest.ports
  "Ingestion SPI: the ports a document pipeline consumes and a corpus provider
   implements. Protocols only, no dependencies.

   ISource is required; ISourceHealth and IParserRule are optional, so ask
   `satisfies?` before dispatching. Providers install through
   hive-spi.ingest.registry.

   Rationale: hive memory 20260906013030-2d53c103.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defprotocol ISource
  "One corpus the pipeline can pull documents from."

  (source-id [this]
    "Stable identifier for this source type.

     Convention: lowercase, hyphen-separated. Examples: \"notion\", \"jira\",
     \"logseq\", \"confluence\", \"rfc\", \"web-crawl\".")

  (fetch-documents [this opts]
    "Fetch documents from the external source.

     Returns Result<seq<Document>>, where Document is the value object built
     by hive-spi.ingest.model/make-document. Constructing one through that
     smart constructor rather than by hand is what keeps a provider honest
     about the contract.

     Common opts, and a source may accept its own beyond these:
       :project-id  scope filter for multi-project sources
       :since       java.time.Instant, for incremental sync
       :limit       maximum documents to fetch
       :filters     source-specific query map"))

(defprotocol ISourceHealth
  "Optional: whether this source's upstream is reachable.

   Optional by ISP. A corpus that is a local directory has no connection to
   report and should not be forced to invent one."

  (source-health [this]
    "Return {:status :ok|:degraded|:down :details {...}}."))

(defprotocol IParserRule
  "One claim on the shape of a fetched response.

   An ordered chain of these selects a parse profile. Adding a response shape
   means appending a rule, never editing the chain that folds them: the OCP
   seam of the ingestion side.

   A profile is DATA, not behaviour dressed up as data:
     {:profile/id :profile/format :profile/content-kind :profile/parser}"

  (rule-id [this]
    "Stable keyword identifying this rule.")

  (rule-applies? [this ctx]
    "True when this rule claims the response.
     ctx is {:url :content-type :body}.")

  (rule-profile [this ctx]
    "The parse profile for a response this rule has claimed."))
