(ns hive-spi.diag.schema
  "Value objects for the memory-diagnostics domain — plain EDN maps validated by
   malli, with fail-loud smart constructors. Pure leaf (requires only malli).

   Every public shape the ports (hive-spi.diag.ports) return is defined here as
   a named schema with namespaced :diag/* keys, an open map so a richer adapter
   may add keys without a schema change, and a smart constructor `->name` that
   fills defaults and FAILS LOUD — `ex-info {:error :diag/invalid :kind <kw>
   :explanation <malli explain>}` — on a non-conformant value.

   Bytes are the canonical unit everywhere (:diag/*-bytes). Human-friendly
   rendering (MiB/GiB) is the caller's concern; `human-bytes` is provided."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; ---------------------------------------------------------------------------
;; Leaf sub-schemas
;; ---------------------------------------------------------------------------

(def NonNegLong
  "A byte count: an integer >= 0 (or nil where a reading may be unavailable)."
  (m/schema [:maybe [:int {:min 0}]]))

(def MemoryPool
  "One JMX memory pool's occupancy (e.g. G1 Eden/Old, Metaspace)."
  (m/schema
   [:map {:closed false}
    [:diag/name  :string]
    [:diag/used-bytes      NonNegLong]
    [:diag/committed-bytes NonNegLong]]))

;; ---------------------------------------------------------------------------
;; HeapSnapshot
;; ---------------------------------------------------------------------------

(def HeapSnapshot
  "A point-in-time heap + RSS reading (IHeapProbe/heap-snapshot).
     :diag/used-bytes       heap used
     :diag/committed-bytes  heap committed (drives RSS)
     :diag/max-bytes        -Xmx ceiling
     :diag/non-heap-bytes   metaspace + code cache + …
     :diag/rss-bytes        process RSS from /proc, or nil if unreadable
     :diag/pools            per-pool breakdown (optional)
     :diag/at-ms            capture wall-clock (epoch millis)"
  (m/schema
   [:map {:closed false}
    [:diag/used-bytes      NonNegLong]
    [:diag/committed-bytes NonNegLong]
    [:diag/max-bytes       NonNegLong]
    [:diag/non-heap-bytes  {:optional true} NonNegLong]
    [:diag/rss-bytes       {:optional true} NonNegLong]
    [:diag/pools           {:optional true} [:vector MemoryPool]]
    [:diag/at-ms           {:optional true} NonNegLong]]))

;; ---------------------------------------------------------------------------
;; ClassHistogram
;; ---------------------------------------------------------------------------

(def HistogramEntry
  "One class's live footprint."
  (m/schema
   [:map {:closed false}
    [:diag/rank      [:int {:min 1}]]
    [:diag/class     :string]
    [:diag/instances NonNegLong]
    [:diag/bytes     NonNegLong]]))

(def ClassHistogram
  "The top-N classes by retained bytes (IHeapProbe/class-histogram)."
  (m/schema
   [:map {:closed false}
    [:diag/entries      [:vector HistogramEntry]]
    [:diag/total-bytes  {:optional true} NonNegLong]
    [:diag/at-ms        {:optional true} NonNegLong]]))

;; ---------------------------------------------------------------------------
;; AllocationProfile
;; ---------------------------------------------------------------------------

(def AllocationProfile
  "Allocation-rate window (IAllocationSampler/sample-allocation). bps = bytes/sec."
  (m/schema
   [:map {:closed false}
    [:diag/duration-ms  [:int {:min 0}]]
    [:diag/samples-bps  [:vector NonNegLong]]
    [:diag/mean-bps     NonNegLong]
    [:diag/peak-bps     NonNegLong]]))

;; ---------------------------------------------------------------------------
;; FlamegraphArtifact
;; ---------------------------------------------------------------------------

(def FlamegraphArtifact
  "A rendered profiler flamegraph (IProfiler/stop-profiling!)."
  (m/schema
   [:map {:closed false}
    [:diag/event       [:enum :cpu :alloc :wall :lock]]
    [:diag/path        :string]
    [:diag/duration-ms {:optional true} [:int {:min 0}]]]))

;; ---------------------------------------------------------------------------
;; CacheOccupancy
;; ---------------------------------------------------------------------------

(def CacheStore
  "One backing store within a cache (a cache may shard per store/db)."
  (m/schema
   [:map {:closed false}
    [:diag/store    :string]
    [:diag/entries  NonNegLong]
    [:diag/capacity {:optional true} NonNegLong]]))

(def CacheOccupancy
  "Occupancy of one named cache (ICacheProbe/cache-occupancy)."
  (m/schema
   [:map {:closed false}
    [:diag/cache-id :keyword]
    [:diag/stores   [:vector CacheStore]]]))

;; ---------------------------------------------------------------------------
;; Reclamation
;; ---------------------------------------------------------------------------

(def Reclamation
  "Before/after used-heap around a GC or eviction (bytes). :diag/reclaimed-bytes
   may be negative if the heap grew concurrently — that is information, not error."
  (m/schema
   [:map {:closed false}
    [:diag/used-before-bytes NonNegLong]
    [:diag/used-after-bytes  NonNegLong]
    [:diag/reclaimed-bytes   :int]
    [:diag/detail            {:optional true} [:vector CacheOccupancy]]]))

;; ---------------------------------------------------------------------------
;; RetainerReport
;; ---------------------------------------------------------------------------

(def RetainerRoot
  "One measured retainer candidate. :diag/bytes nil + :diag/skipped when the
   sizer refused/guarded it."
  (m/schema
   [:map {:closed false}
    [:diag/label   :string]
    [:diag/bytes   NonNegLong]
    [:diag/skipped {:optional true} [:maybe :string]]]))

(def RetainerReport
  "Ranked retainer candidates (IMemoryClinic/hunt-retainers)."
  (m/schema
   [:map {:closed false}
    [:diag/roots [:vector RetainerRoot]]]))

;; ---------------------------------------------------------------------------
;; DiagnosisReport
;; ---------------------------------------------------------------------------

(def DiagnosisReport
  "Composed one-shot report (IMemoryClinic/diagnose)."
  (m/schema
   [:map {:closed false}
    [:diag/snapshot   HeapSnapshot]
    [:diag/histogram  ClassHistogram]
    [:diag/allocation {:optional true} AllocationProfile]
    [:diag/caches     [:vector CacheOccupancy]]
    [:diag/verdict    {:optional true} [:enum :static-residency :churning :mixed :unknown]]]))

;; ---------------------------------------------------------------------------
;; Fail-loud gates + smart constructors
;; ---------------------------------------------------------------------------

(defn valid?
  "True iff `x` conforms to `schema`. Pure; never throws."
  [schema x]
  (m/validate schema x))

(defn explain
  "malli explanation for `x` against `schema`, or nil if it conforms."
  [schema x]
  (m/explain schema x))

(defn- build
  "Validate `m` against `schema`; return it, or FAIL LOUD."
  [schema kind m]
  (if (m/validate schema m)
    m
    (throw (ex-info (str "Not a valid " (name kind))
                    {:error :diag/invalid :kind kind :explanation (m/explain schema m)}))))

(defn ->heap-snapshot   [m] (build HeapSnapshot      :diag/heap-snapshot   m))
(defn ->class-histogram [m] (build ClassHistogram    :diag/class-histogram m))
(defn ->allocation      [m] (build AllocationProfile :diag/allocation      m))
(defn ->flamegraph      [m] (build FlamegraphArtifact :diag/flamegraph     m))
(defn ->cache-occupancy [m] (build CacheOccupancy    :diag/cache-occupancy m))
(defn ->reclamation     [m] (build Reclamation       :diag/reclamation     m))
(defn ->retainer-report [m] (build RetainerReport    :diag/retainer-report m))
(defn ->diagnosis       [m] (build DiagnosisReport   :diag/diagnosis       m))

;; ---------------------------------------------------------------------------
;; Rendering helper (bytes -> human string). Pure.
;; ---------------------------------------------------------------------------

(def ^:private units ["B" "KiB" "MiB" "GiB" "TiB"])

(defn human-bytes
  "Render a byte count as a human string, e.g. 11901239296 -> \"11.1 GiB\".
   nil -> \"n/a\"."
  [n]
  (if (nil? n)
    "n/a"
    (loop [v (double n) us units]
      (if (or (< v 1024.0) (nil? (next us)))
        (str (-> (* v 100) Math/round (/ 100.0)) " " (first us))
        (recur (/ v 1024.0) (next us))))))
