(ns hive-spi.ingest.model
  "The value objects hive-spi.ingest.ports promises: DocumentFormat and
   Document. Smart constructors return Result rather than throwing.

   Chunks, ingestion results, synthesis and pipeline config are deliberately
   absent; a provider never builds those.

   Rationale: hive memory 20260906013030-2d53c103."
  (:require [clojure.string :as str]
            [hive-dsl.adt :as adt]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(adt/defadt DocumentFormat
  "The file formats a document can arrive in. Closed."
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
  "Smart constructor for Document. Returns Result<Document>.

   `format` is a DocumentFormat variant. `metadata` is the provider's own map,
   for anything this contract does not model. Errs on blank id or source, or
   nil content."
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
