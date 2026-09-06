(ns hive-spi.ingest.model
  "The value objects hive-spi.ingest.ports PROMISES, and nothing else.

   A provider cannot satisfy `fetch-documents -> Result<seq<Document>>` without
   being able to CONSTRUCT a Document, so the Document value object and the
   format vocabulary it carries are part of the contract, not of the pipeline.

   What is deliberately ABSENT is the rest of the pipeline's model: chunks,
   ingestion results, knowledge entries, synthesis results, collection and
   pipeline config. A provider never builds any of those. They stay with the
   pipeline that owns them, because a contract that carries its implementor's
   internals is not a contract.

   Smart constructors following Wlaschin: illegal states unrepresentable,
   invariants enforced at creation, failures returned as Result rather than
   thrown."
  (:require [clojure.string :as str]
            [hive-dsl.adt :as adt]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(adt/defadt DocumentFormat
  "The file formats a document can arrive in.

   Closed: a pipeline must be able to dispatch extraction on this exhaustively,
   and a format nothing can extract is not a format. Providers name one of
   these on every Document they emit."
  :format/pdf
  :format/markdown
  :format/docx
  :format/text
  :format/epub
  :format/html
  :format/rtf
  :format/odt
  :format/odp
  :format/csv
  :format/xml
  :format/org
  :format/rst
  :format/asciidoc)

(defn make-document
  "Smart constructor for the Document value object. Returns Result<Document>.

   The one value object a provider must be able to build. `format` is a
   DocumentFormat variant; `metadata` is the provider's own map and is the
   sanctioned place for anything this contract does not model."
  [{:keys [id source format content metadata]
    :or {metadata {}}}]
  (cond
    (str/blank? id)
    (r/err :ingest/invalid-document {:field :id :reason "blank document id"})

    (str/blank? source)
    (r/err :ingest/invalid-document {:field :source :reason "blank source path"})

    (nil? content)
    (r/err :ingest/invalid-document {:field :content :reason "nil content"})

    :else
    (r/ok {:document/id       id
           :document/source   source
           :document/format   format
           :document/content  content
           :document/metadata metadata})))
