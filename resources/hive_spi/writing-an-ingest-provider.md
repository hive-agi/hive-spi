# Writing an ingestion provider

You can teach a hive ingestion pipeline about a new corpus without access to
that pipeline. You need this library and nothing else.

That is deliberate. The pipeline is closed; the seam is not.

## What you implement

One protocol, from `hive-spi.ingest.ports`:

```clojure
(require '[hive-spi.ingest.ports :as ports]
         '[hive-spi.ingest.model :as model]
         '[hive-dsl.result :as r])

(defrecord MyCorpus [config]
  ports/ISource
  (source-id [_] "my-corpus")          ; lowercase, hyphen-separated
  (fetch-documents [_ {:keys [limit since project-id filters]}]
    (r/ok (my-fetch config limit))))
```

`fetch-documents` returns `Result<seq<Document>>`. Build each Document through
the smart constructor rather than by hand, so an invariant you did not know
about still holds:

```clojure
(model/make-document
  {:id      "rfc-2616"
   :source  "https://example.org/rfc/2616"
   :format  :format/text                ; a DocumentFormat variant
   :content "..."
   :metadata {:anything :you-like}})    ; your map; the contract does not read it
```

`:metadata` is the sanctioned place for everything this contract does not
model. Put your corpus's own facts there rather than inventing top-level keys.

### Optional surfaces

`ISourceHealth` is optional by ISP. Implement it only if your corpus has an
upstream that can be down. A local directory should not invent a health signal.

`IParserRule` is a separate seam, for when a corpus serves several response
shapes and you want to claim one before a generic parser sees it.

## Prove it before you ship it

`hive-spi.ingest.tck` runs your provider against the laws its answers must
satisfy. It asserts nothing and returns a report, so you can drive it from a
REPL, from `clojure.test`, or from a CI script, with no test framework on your
classpath.

```clojure
(require '[hive-spi.ingest.tck :as tck])

(println (tck/explain (->MyCorpus cfg)
                      {:opts {} :limit-opts {:limit 2}}))
```

```
CONFORMS  source="my-corpus"
  measured at: #{:rung/descriptor :rung/behaviour}
  9 passed, 0 failed, 0 skipped
```

**Read `measured at`, not just the verdict.** `:ok true` with only
`#{:rung/descriptor}` means the kit checked your identity and never fetched
anything. A law whose fixture you did not supply is SKIPPED and itemised; it
never counts as a pass. That is the difference between a green report and
evidence.

Supply both fixtures:

| fixture | what it is |
|---|---|
| `:opts` | opts for a baseline fetch |
| `:limit-opts` | opts carrying a positive `:limit` |

### Adding a law only your corpus can state

The law set is open. Contribute rather than fork:

```clojure
(tck/register-law!
  {:law/id      :my-corpus/ids-are-rfc-numbers
   :law/rung    :rung/behaviour
   :law/inputs  #{:opts}
   :law/summary "Every document id is rfc-<digits>."
   :law/check   (fn [{:keys [source opts]}]
                  (every? #(re-matches #"rfc-\d+" (:document/id %))
                          (:ok (ports/fetch-documents source opts))))})
```

A check returns `true`, `false`, or `{:detail "why"}`. Throwing is a failure
with the message attached, never an escape.

## Register it

From your addon's `initialize!`, and retract on `shutdown!`:

```clojure
(require '[hive-spi.ingest.registry :as registry])

(registry/register-source! owner "my-corpus"
  {:factory     (fn [opts] (->MyCorpus opts))
   :description "What this corpus is, one line."
   :params      {:since "ISO instant for incremental sync"}})

;; on shutdown
(registry/retract-all! owner)
```

`owner` is your addon id. Registration is owner-scoped: nobody else can
replace or remove your entries, and `retract-all!` takes exactly yours. A
second owner claiming your `source-id` is refused with
`:source-registry/owner-conflict` rather than silently winning, because in a
shared JVM the loser of a silent race is a corpus that stops answering for
reasons nobody can see.

## What you will never need

The pipeline. Chunking, embedding, storage, knowledge-graph synthesis and the
ingest entry point are all on the other side of this seam. If you find
yourself wanting one of them, that is a gap in this contract worth reporting,
not a reason to reach for the closed artifact.

Your `deps.edn` should read:

```clojure
{:deps {io.github.hive-agi/hive-spi {:mvn/version "RELEASE"}
        io.github.hive-agi/hive-addon {:mvn/version "RELEASE"}}}
```

Both are MIT and on Clojars. No token, no private registry.
