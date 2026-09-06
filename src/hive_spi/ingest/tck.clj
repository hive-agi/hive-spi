(ns hive-spi.ingest.tck
  "The source conformance kit: what an ingestion provider must DO, executed.

   `hive-spi.ingest.ports` says what a provider IS. That is a claim about
   shape only: an ISource returning an empty vector from every call satisfies
   the protocol and ingests nothing. This namespace discharges the other half,
   by running a provider against laws its answers must satisfy.

   WHY THIS IS PUBLIC, and why that is the whole point.

   The pipeline that consumes providers is closed. A community author cannot
   read it, cannot resolve it, and must never need to. So the ability to prove
   a provider correct has to live on the PUBLIC side of the seam, next to the
   ports, or the seam only works for people who already have the product. A
   conformance kit shipped inside the proprietary artifact is a conformance
   kit only employees can run.

   Plain functions over plain data, on purpose. `conform` returns a report and
   asserts NOTHING, so a provider drives it from clojure.test, from a REPL, or
   from a CI script, and needs no test framework on its classpath to do so.
   The only dependencies are clojure.string and hive-dsl, the same ones the
   ports and registry already carry.

   Two rungs, weakest first, and every result names the rung it came from:

     :rung/descriptor  static. Identity and its stability. Nothing is fetched.
     :rung/behaviour   one fetch against fixture opts. Result discipline,
                       document validity, uniqueness, and limit honouring.

   A law whose protocol is not implemented, or whose fixture was not supplied,
   is SKIPPED and itemised in `:skips` with the reason. It NEVER counts as a
   pass. A provider that runs three descriptor laws and reports `:ok true` has
   been measured at the descriptor rung and nowhere else, and the report says
   so.

   Laws are an OPEN set: `register-law!` adds one, so a corpus contributes the
   laws only it can state without editing this namespace."
  (:require [clojure.string :as str]
            [hive-spi.ingest.model :as model]
            [hive-spi.ingest.ports :as ports]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def rungs
  "Evidence rungs, weakest first. A report is only as strong as the weakest
   rung it actually ran."
  [:rung/descriptor :rung/behaviour])

;; =============================================================================
;; What a law is
;; =============================================================================
;;
;; :law/id       qualified keyword, stable
;; :law/rung     which rung the evidence sits at
;; :law/summary  what a reader learns from a pass
;; :law/needs    optional set of protocols the law calls; a provider that does
;;               not satisfy one is SKIPPED, not failed
;; :law/inputs   optional set of fixture keys the law reads
;; :law/check    (fn [ctx] -> true | false | {:detail string}). Throwing is a
;;               FAILURE with the throwable's message, never an escape.

(defn- ok-result?
  "hive-dsl Result discriminated without requiring hive-dsl.result here: an ok
   carries :ok, an err carries :error. Kept structural so the kit does not
   pin a Result implementation a provider might shade."
  [x]
  (and (map? x) (contains? x :ok)))

(defn- err-result? [x]
  (and (map? x) (contains? x :error)))

(defn- result? [x] (or (ok-result? x) (err-result? x)))

(defn- payload [x] (:ok x))

(def ^:private document-format-variants
  (:variants model/DocumentFormat))

;; =============================================================================
;; The built-in laws
;; =============================================================================

(def default-laws
  "The laws every ingestion provider must satisfy.

   Deliberately few and deliberately about the CONTRACT, not about any corpus.
   A law that only one corpus can state belongs in that corpus, added through
   `register-law!`."
  [{:law/id      :source/id-is-non-blank
    :law/rung    :rung/descriptor
    :law/summary "source-id returns a non-blank string, which is the registry key."
    :law/check   (fn [{:keys [source]}]
                   (let [id (ports/source-id source)]
                     (if (and (string? id) (not (str/blank? id)))
                       true
                       {:detail (str "source-id returned " (pr-str id))})))}

   {:law/id      :source/id-is-stable
    :law/rung    :rung/descriptor
    :law/summary "source-id is the same on every call, so a registration cannot drift."
    :law/check   (fn [{:keys [source]}]
                   (let [a (ports/source-id source) b (ports/source-id source)]
                     (if (= a b) true {:detail (str (pr-str a) " then " (pr-str b))})))}

   {:law/id      :source/id-follows-convention
    :law/rung    :rung/descriptor
    :law/summary "source-id is lowercase and hyphen-separated, per the port's contract."
    :law/check   (fn [{:keys [source]}]
                   (let [id (str (ports/source-id source))]
                     (if (re-matches #"[a-z0-9]+(-[a-z0-9]+)*" id)
                       true
                       {:detail (str "not lowercase-hyphenated: " (pr-str id))})))}

   {:law/id      :source/fetch-returns-a-result
    :law/rung    :rung/behaviour
    :law/inputs  #{:opts}
    :law/summary "fetch-documents returns a Result rather than raw data or a throw."
    :law/check   (fn [{:keys [source opts]}]
                   (let [out (ports/fetch-documents source opts)]
                     (if (result? out)
                       true
                       {:detail (str "returned " (pr-str (type out)))})))}

   {:law/id      :source/ok-payload-is-a-collection
    :law/rung    :rung/behaviour
    :law/inputs  #{:opts}
    :law/summary "An ok fetch carries a collection of documents, empty allowed."
    :law/check   (fn [{:keys [source opts]}]
                   (let [out (ports/fetch-documents source opts)]
                     (cond
                       (err-result? out) {:skip "fetch returned err; nothing to inspect"}
                       (coll? (payload out)) true
                       :else {:detail (str "ok payload was " (pr-str (type (payload out))))})))}

   {:law/id      :source/documents-are-well-formed
    :law/rung    :rung/behaviour
    :law/inputs  #{:opts}
    :law/summary "Every emitted document carries a non-blank id and source, and non-nil content."
    :law/check   (fn [{:keys [source opts]}]
                   (let [out (ports/fetch-documents source opts)]
                     (if (err-result? out)
                       {:skip "fetch returned err; nothing to inspect"}
                       (let [bad (->> (payload out)
                                      (remove (fn [d]
                                                (and (map? d)
                                                     (not (str/blank? (str (:document/id d))))
                                                     (not (str/blank? (str (:document/source d))))
                                                     (some? (:document/content d)))))
                                      (take 3) vec)]
                         (if (empty? bad)
                           true
                           {:detail (str (count bad) " malformed, first: "
                                         (pr-str (first bad)))})))))}

   {:law/id      :source/document-ids-are-unique
    :law/rung    :rung/behaviour
    :law/inputs  #{:opts}
    :law/summary "One fetch never emits the same document id twice, or the store dedupes silently."
    :law/check   (fn [{:keys [source opts]}]
                   (let [out (ports/fetch-documents source opts)]
                     (if (err-result? out)
                       {:skip "fetch returned err; nothing to inspect"}
                       (let [ids (mapv :document/id (payload out))
                             dupes (->> ids frequencies (filter #(> (val %) 1)) (mapv key))]
                         (if (empty? dupes)
                           true
                           {:detail (str "duplicate ids: " (pr-str (vec (take 3 dupes))))})))))}

   {:law/id      :source/format-is-a-declared-variant
    :law/rung    :rung/behaviour
    :law/inputs  #{:opts}
    :law/summary "A document's :document/format, when present, is a DocumentFormat variant."
    :law/check   (fn [{:keys [source opts]}]
                   (let [out (ports/fetch-documents source opts)]
                     (if (err-result? out)
                       {:skip "fetch returned err; nothing to inspect"}
                       (let [fmts (->> (payload out)
                                       (keep :document/format)
                                       (map #(if (map? %) (:adt/variant %) %))
                                       (remove document-format-variants)
                                       distinct (take 3) vec)]
                         (if (empty? fmts)
                           true
                           {:detail (str "undeclared formats: " (pr-str fmts))})))))}

   {:law/id      :source/limit-is-honoured
    :law/rung    :rung/behaviour
    :law/inputs  #{:limit-opts}
    :law/summary "A :limit in opts caps the number of documents returned."
    :law/check   (fn [{:keys [source limit-opts]}]
                   (let [lim (:limit limit-opts)
                         out (ports/fetch-documents source limit-opts)]
                     (cond
                       (not (pos-int? lim)) {:skip ":limit-opts carries no positive :limit"}
                       (err-result? out) {:skip "fetch returned err; nothing to inspect"}
                       (<= (count (payload out)) lim) true
                       :else {:detail (str "asked for " lim ", got " (count (payload out)))})))}

   {:law/id      :source/health-reports-a-known-status
    :law/rung    :rung/descriptor
    :law/needs   #{::health}
    :law/summary "When ISourceHealth is implemented, it answers with a known status keyword."
    :law/check   (fn [{:keys [source]}]
                   (let [h (ports/source-health source)]
                     (if (and (map? h) (#{:ok :degraded :down} (:status h)))
                       true
                       {:detail (str "source-health returned " (pr-str h))})))}])

;; =============================================================================
;; The open set
;; =============================================================================

(defonce ^:private extra-laws (atom {}))

(defn register-law!
  "Add a law to the kit. A corpus contributes what only it can state."
  [law]
  (swap! extra-laws assoc (:law/id law) law)
  (:law/id law))

(defn unregister-law! [law-id]
  (swap! extra-laws dissoc law-id)
  law-id)

(defn laws
  "Every law currently in the kit, built-ins first."
  []
  (into (vec default-laws) (vals @extra-laws)))

;; =============================================================================
;; Running them
;; =============================================================================

(defn- satisfied?
  "Whether SOURCE provides what LAW needs. ::health is the only optional
   protocol today; an unknown need is treated as unmet rather than assumed."
  [source need]
  (case need
    ::health (satisfies? ports/ISourceHealth source)
    false))

(defn- run-law
  [law {:keys [source] :as ctx}]
  (let [missing-need (first (remove #(satisfied? source %) (:law/needs law)))
        missing-input (first (remove #(contains? ctx %) (:law/inputs law)))]
    (cond
      missing-need
      {:law/id (:law/id law) :law/rung (:law/rung law)
       :status :skip :reason (str "provider does not implement " missing-need)}

      missing-input
      {:law/id (:law/id law) :law/rung (:law/rung law)
       :status :skip :reason (str "fixture did not supply " missing-input)}

      :else
      (let [out (try ((:law/check law) ctx)
                     (catch Throwable t {:throw (or (ex-message t) (str t))}))]
        (cond
          (true? out)
          {:law/id (:law/id law) :law/rung (:law/rung law) :status :pass}

          (:skip out)
          {:law/id (:law/id law) :law/rung (:law/rung law)
           :status :skip :reason (:skip out)}

          (:throw out)
          {:law/id (:law/id law) :law/rung (:law/rung law)
           :status :fail :reason (str "law threw: " (:throw out))}

          :else
          {:law/id (:law/id law) :law/rung (:law/rung law)
           :status :fail
           :reason (or (:detail out) "check returned false")})))))

(defn conform
  "Run the kit against SOURCE and return a REPORT. Asserts nothing.

   ctx supplies the fixtures the behaviour rung needs:
     :opts        opts for the baseline fetch
     :limit-opts  opts carrying a positive :limit

   The report:
     :ok        true when nothing FAILED. Skips do not make it false, and do
                not make it meaningful either, which is what :rungs-measured
                is for.
     :results   every law, with its rung and status
     :passes / :failures / :skips   itemised
     :rungs-measured  the rungs that actually ran at least one law. A report
                      with :ok true and only #{:rung/descriptor} here has said
                      nothing whatever about fetching."
  ([source] (conform source {}))
  ([source ctx]
   (let [ctx (assoc ctx :source source)
         results (mapv #(run-law % ctx) (laws))
         by (group-by :status results)
         measured (into #{} (comp (remove #(= :skip (:status %))) (map :law/rung)) results)]
     {:ok (empty? (:fail by))
      :results results
      :passes (mapv :law/id (:pass by))
      :failures (mapv #(select-keys % [:law/id :law/rung :reason]) (:fail by))
      :skips (mapv #(select-keys % [:law/id :law/rung :reason]) (:skip by))
      :rungs-measured measured})))

(defn explain
  "A human-readable rendering of `conform`, for a REPL or a CI log."
  ([source] (explain source {}))
  ([source ctx]
   (let [{:keys [ok passes failures skips rungs-measured]} (conform source ctx)]
     (str/join
      "\n"
      (concat
       [(str (if ok "CONFORMS" "FAILS") "  source=" (pr-str (ports/source-id source)))
        (str "  measured at: " (if (seq rungs-measured) (pr-str rungs-measured) "NOTHING"))
        (str "  " (count passes) " passed, " (count failures) " failed, "
             (count skips) " skipped")]
       (when (seq failures)
         (cons "  failures:"
               (map #(str "    " (:law/id %) " [" (:law/rung %) "] " (:reason %)) failures)))
       (when (seq skips)
         (cons "  skips:"
               (map #(str "    " (:law/id %) " " (:reason %)) skips))))))))
