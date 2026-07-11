(ns hive-spi.diag.ports-test
  "Contract tests for the six hive-spi.diag.ports protocols — each protocol's
   method surface (exact method-name set, so an accidental add/rename is caught)
   plus a satisfies?-probe on a single stub record that implements all six.
   Behavioural composition and the concrete adapters are exercised by downstream
   consumers."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.diag.ports :as p]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn- protocol-method-names [pvar]
  (->> @pvar :sigs vals (map :name) set))

;; One stub implementing ALL six ports — the minimal thing that must satisfy the
;; contract. Fallible methods return the raw Result shape ({:ok …}) directly; the
;; scaffold only asserts the surface, not the behaviour.
(defrecord StubDiag []
  p/IHeapProbe
  (heap-snapshot   [_]     {:diag/used-bytes 0 :diag/committed-bytes 0 :diag/max-bytes 0})
  (class-histogram [_ _n]  {:diag/entries []})
  (request-gc!     [_]     {:diag/used-before-bytes 0 :diag/used-after-bytes 0 :diag/reclaimed-bytes 0})
  (dump-heap!      [_ _ _] {:ok {:diag/path "/x" :diag/bytes 0}})

  p/IRetainedSizer
  (sizer-available? [_]   false)
  (retained-size    [_ _] {:error :diag/sizer-unavailable})

  p/IAllocationSampler
  (sample-allocation [_ _ms] {:error :diag/sampler-unavailable})

  p/IProfiler
  (profiler-active? [_]    false)
  (start-profiling! [_ _]  {:error :diag/profiler-unavailable})
  (stop-profiling!  [_]    {:error :diag/profiler-not-running})

  p/ICacheProbe
  (cache-id        [_] :stub/cache)
  (cache-occupancy [_] {:ok {:diag/cache-id :stub/cache :diag/stores []}})
  (evict-cache!    [_] {:ok {:diag/used-before-bytes 0 :diag/used-after-bytes 0 :diag/reclaimed-bytes 0}})

  p/IMemoryClinic
  (diagnose       [_]   {:ok {}})
  (relieve!       [_]   {:ok {}})
  (hunt-retainers [_ _] {:ok {:diag/roots []}}))

(deftest protocol-surfaces
  (testing "each port exposes exactly its contracted method set (ISP: six small ports)"
    (is (= '#{heap-snapshot class-histogram request-gc! dump-heap!}
           (protocol-method-names #'p/IHeapProbe)))
    (is (= '#{sizer-available? retained-size}
           (protocol-method-names #'p/IRetainedSizer)))
    (is (= '#{sample-allocation}
           (protocol-method-names #'p/IAllocationSampler)))
    (is (= '#{profiler-active? start-profiling! stop-profiling!}
           (protocol-method-names #'p/IProfiler)))
    (is (= '#{cache-id cache-occupancy evict-cache!}
           (protocol-method-names #'p/ICacheProbe)))
    (is (= '#{diagnose relieve! hunt-retainers}
           (protocol-method-names #'p/IMemoryClinic)))))

(deftest stub-satisfies-every-port
  (let [s (->StubDiag)]
    (testing "a single record can satisfy all six ports"
      (is (satisfies? p/IHeapProbe s))
      (is (satisfies? p/IRetainedSizer s))
      (is (satisfies? p/IAllocationSampler s))
      (is (satisfies? p/IProfiler s))
      (is (satisfies? p/ICacheProbe s))
      (is (satisfies? p/IMemoryClinic s)))
    (testing "cache-id is a keyword identity (pure, never throws)"
      (is (keyword? (p/cache-id s))))
    (testing "capability predicates return booleans"
      (is (boolean? (p/sizer-available? s)))
      (is (boolean? (p/profiler-active? s))))))
