(ns hive-spi.diag.ports
  "SPI ports for runtime memory/heap DIAGNOSTICS — a live, toggleable clinic for
   answering \"what is consuming the heap, and can we get it back?\".

   Pure protocol stubs — NO implementations and NO :require live here (SPI is a
   pure-contract leaf). Each method docstring states its CONTRACT: argument
   shapes, the value-object it returns (see hive-spi.diag.schema), and its
   failure semantics.

   SIX small ports rather than one god-interface (ISP): a probe reads the heap,
   a sizer measures one object, a sampler watches allocation, a profiler renders
   flamegraphs, a cache-probe inspects one suspected retainer, and the clinic
   composes the five into railway workflows by INJECTION. Each concretion is an
   adapter over exactly one capability/library and is independently testable
   against its own contract, so a missing capability (e.g. the clj-memory-meter
   agent, or async-profiler on a locked-down box) degrades to an `err` Result on
   that ONE port instead of breaking the whole clinic.

   Fallible methods return a hive-dsl.result Result — `{:ok v}` / `{:error k …}`
   — never throw for environmental failure (capability absent, profiler already
   running, cache backend not loaded). They throw ex-info only on a programming
   error (a non-conformant argument)."
  #?(:cljs (:require-macros)))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; === IHeapProbe ===
;;; Reads live heap/RSS state from the JVM itself (JMX MemoryMXBean, the HotSpot
;;; DiagnosticCommand, /proc). Cheap, always-available, no external library.

(defprotocol IHeapProbe
  "Live JVM heap + process-RSS inspection. Implementations wrap only the JDK's
   own management surface — no third-party dependency."

  (heap-snapshot
    [this]
    "Capture current memory occupancy.
     Returns: a conformant HeapSnapshot (hive-spi.diag.schema/HeapSnapshot) —
              used/committed/max heap bytes, non-heap, per-pool breakdown, and
              process RSS bytes when readable (nil :diag/rss-bytes otherwise).
     Pure read; never throws.")

  (class-histogram
    [this n]
    "Live class histogram, the top `n` classes by retained bytes.
     Arguments:
       n — positive int; how many rows to keep (ranked by bytes desc).
     Returns: a conformant ClassHistogram — [{:diag/rank :diag/class
              :diag/instances :diag/bytes} …] plus totals. NOTE: capturing a
              histogram forces a full GC (counts reflect LIVE objects only).
     Pure read; never throws.")

  (request-gc!
    [this]
    "Request a garbage collection and report the used-heap delta. With
     -XX:+ExplicitGCInvokesConcurrent this is a concurrent (non-STW) cycle.
     Returns: a conformant Reclamation {:diag/used-before-bytes
              :diag/used-after-bytes :diag/reclaimed-bytes}. A small reclaim on
              a full heap is the signal that the residency is LIVE, not garbage.
     Side-effecting; never throws.")

  (dump-heap!
    [this path live-only?]
    "Write an hprof heap dump via the HotSpotDiagnostic MXBean, for offline
     dominator analysis.
     Arguments:
       path       — absolute filesystem path; MUST NOT already exist.
       live-only? — when true, dump only reachable objects (a full GC first).
     Returns: Result — ok {:diag/path <string> :diag/bytes <long>} on success,
              err :diag/dump-failed {:reason …} on IO/mbean failure."))

;;; === IRetainedSizer ===
;;; Deep retained-size of ONE object graph. Adapter over clj-memory-meter (JOL).
;;; A capability, not a guarantee: JOL self-attach needs
;;; -Djdk.attach.allowAttachSelf=true, so `sizer-available?` gates use.

(defprotocol IRetainedSizer
  "Measure the deep retained size of a single live object. The workhorse for
   confirming a suspected retainer once the histogram/hunt has narrowed it."

  (sizer-available?
    [this]
    "Return true iff deep sizing is usable in this JVM (the measurement agent
     attached). Pure; never throws. Call before `retained-size` to branch.")

  (retained-size
    [this obj]
    "Deep retained size of `obj`'s reachable graph.
     Arguments:
       obj — any live object (an atom's value, a cache, a record field …).
     Returns: Result — ok {:diag/bytes <long> :diag/human <string>} , or
              err :diag/sizer-unavailable {…} when the capability is absent.
     HAZARD: walking a multi-GB graph allocates an identity set proportional to
     object count; under -XX:+ExitOnOutOfMemoryError a reckless call can kill
     the VM. Adapters SHOULD refuse (err :diag/sizer-refused) an object whose
     class-histogram-implied size exceeds a configured guard. Never throws for
     capability/guard failures."))

;;; === IAllocationSampler ===
;;; Allocation-rate over a window. Adapter over jvm-alloc-rate-meter. Answers
;;; "is the heap CHURNING (re-allocated hot) or STATIC (retained)?" — the first
;;; fork in any RAM investigation.

(defprotocol IAllocationSampler
  "Sample the JVM's allocation rate over a time window."

  (sample-allocation
    [this duration-ms]
    "Measure allocation rate for `duration-ms` milliseconds (blocking).
     Returns: Result — ok AllocationProfile {:diag/duration-ms
              :diag/samples-bps [long…] :diag/mean-bps :diag/peak-bps}, or
              err :diag/sampler-unavailable {…}. A near-zero mean means the
              residency is retained (static), so an allocation profiler will not
              find it — reach for the sizer/hunt instead."))

;;; === IProfiler ===
;;; Sampling profiler → flamegraph artifacts. Adapter over clj-async-profiler.
;;; Stateful: at most one session at a time (start!/stop!). This is the
;;; \"toggle\" surface — on! begins sampling, off! renders the flamegraph.

(defprotocol IProfiler
  "Toggleable sampling profiler that renders flamegraph artifacts (CPU, wall,
   or allocation call-stacks)."

  (profiler-active?
    [this]
    "True iff a profiling session is currently running. Pure; never throws.")

  (start-profiling!
    [this opts]
    "Begin a profiling session.
     Arguments:
       opts — {:diag/event <#{:cpu :alloc :wall :lock}> (default :cpu) …} passed
              through to the backend.
     Returns: Result — ok {:diag/event <kw>} , or
              err :diag/profiler-already-running {…} / :diag/profiler-unavailable.")

  (stop-profiling!
    [this]
    "Stop the active session and render its flamegraph.
     Returns: Result — ok FlamegraphArtifact {:diag/event :diag/path
              :diag/duration-ms}, or err :diag/profiler-not-running {…}."))

;;; === ICacheProbe ===
;;; Inspect + evict ONE cache that is a suspected retainer. One adapter per
;;; backend (datalevin query-result cache, datahike LRU, an app memoize atom…),
;;; so the clinic holds a COLLECTION of these and can attribute reclaimed bytes
;;; to a named cache. This is how a retainer hiding in a library-private
;;; `defonce` (invisible to a var scan) is made addressable.

(defprotocol ICacheProbe
  "Inspect and evict one named cache suspected of pinning heap."

  (cache-id
    [this]
    "Stable keyword identity of this cache (e.g. :datalevin/query-result).
     Pure; never throws.")

  (cache-occupancy
    [this]
    "Current occupancy WITHOUT realizing values (counts/capacity only — reading
     entries must never serialize the cached graphs).
     Returns: Result — ok CacheOccupancy {:diag/cache-id :diag/stores
              [{:diag/store :diag/entries :diag/capacity} …]}, or
              err :diag/cache-unavailable {…} when the backend is not loaded.")

  (evict-cache!
    [this]
    "Evict all entries and report reclaimed heap (GC included). MUST be safe:
     only pure performance caches are eligible — eviction may cost recomputation
     but never correctness.
     Returns: Result — ok Reclamation, or err :diag/cache-unavailable {…}."))

;;; === IMemoryClinic ===
;;; Facade. Composes the five capability ports BY INJECTION into railway
;;; workflows. Depends only on the contracts above — no capability is hard-wired.

(defprotocol IMemoryClinic
  "Composed diagnostic facade: high-level workflows over the injected ports."

  (diagnose
    [this]
    "One-shot situational report: heap snapshot + top-N class histogram +
     short allocation sample (static-vs-churning verdict) + occupancy of every
     registered cache probe.
     Returns: Result — ok DiagnosisReport, threading each port's Result and
              short-circuiting on the first hard failure.")

  (relieve!
    [this]
    "Best-effort RSS relief: evict every registered cache probe, then GC, and
     report the aggregate Reclamation. Safe (pure caches only).
     Returns: Result — ok Reclamation (aggregate before/after/reclaimed).")

  (hunt-retainers
    [this candidates]
    "Rank suspected retainer roots by deep retained size.
     Arguments:
       candidates — [{:diag/label <string> :diag/object <obj>} …] — named live
                    roots to measure (typically produced by the caller from
                    cache probes, named app atoms, or record fields).
     Returns: Result — ok RetainerReport {:diag/roots [{:diag/label :diag/bytes}
              …] sorted desc}. Requires an available IRetainedSizer; err
              :diag/sizer-unavailable when absent. Skips (does not abort on) any
              individual candidate the sizer refuses/guards, recording it with
              :diag/bytes nil and a :diag/skipped reason."))
