(ns hive-spi.ingest.model-test
  "make-document is the one way a provider builds a Document; it errs on a
   blank id or source and nil content, and never throws for those."
  (:require [hive-schemas.test :as hst]
            [hive-spi.ingest.model :as model]
            [hive-test.golden :refer [deftest-golden]]
            [clojure.string]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private Fields
  "Fields that reach every branch: blank and present id/source, nil and
   present content."
  [:map
   [:id [:enum "" " " "doc-1" "doc-2"]]
   [:source [:enum "" "/a.md" "https://x/y"]]
   [:content [:maybe [:enum "body" ""]]]
   [:format [:enum :format/markdown :format/pdf]]])

(hst/deftrifecta-from-schema make-document-errs-on-each-missing-field
  hive-spi.ingest.model/make-document
  {:in  [:cat Fields]
   :out model/DocumentResult
   :rel (fn [[{:keys [id source content format]}] out]
          (cond
            (clojure.string/blank? id)     (= :id (:field out))
            (clojure.string/blank? source) (= :source (:field out))
            (nil? content)                 (= :content (:field out))
            :else (= {:ok {:document/id id :document/source source
                           :document/format format :document/content content
                           :document/metadata {}}}
                     out)))})

(deftest-golden every-branch
  "test/golden/hive_spi/ingest/model.edn"
  (mapv model/make-document
        [{:id "d" :source "/s" :content "c" :format :format/org :metadata {:k 1}}
         {:id " " :source "/s" :content "c"}
         {:id "d" :source "" :content "c"}
         {:id "d" :source "/s"}]))
