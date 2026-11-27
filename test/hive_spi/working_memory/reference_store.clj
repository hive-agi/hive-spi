(ns hive-spi.working-memory.reference-store
  "A test-only in-memory IWorkingMemoryStore, the reference implementation
   hive-spi.working-memory.conformance is written against.

   It lives under test/ on purpose: the contract is ports, schema and
   lifecycle, and a real store belongs to an addon (a JetStream KV bucket, a
   datalevin table). This one exists only so the suite has something faithful
   to run against, and so a host's own test can stand up a store with no
   backend.

   It is atom-backed, single-process and CAS-correct: put-cas! judges the
   expected revision, stamps the next one and fires watchers; delta-since
   re-delivers a key whose revision advanced."
  (:require [hive-spi.working-memory.entry :as entry]
            [hive-spi.working-memory.ports :as ports]))

;; SPDX-License-Identifier: MIT

(defrecord ReferenceStore [state watchers]
  ports/IWorkingMemoryStore
  (get-entry [_ workspace-id key]
    (get-in @state [(entry/workspace-key workspace-id key)]))
  (put-cas! [_ e expected-revision]
    (let [normalized (entry/normalize e)]
      (when-not (try (malli.core/validate ports/WorkingEntry normalized)
                     (catch Exception _ false))
        (throw (ports/refusal :wm/invalid-entry "entry does not conform"
                              {:explanation (try (malli.core/explain ports/WorkingEntry normalized)
                                                 (catch Exception _ nil))})))
      (let [ck (entry/workspace-key (:workspace-id normalized) (:key normalized))]
        ;; the whole read-judge-write happens in one swap! so two writers with
        ;; the same expected revision cannot both win
        (let [outcome (atom nil)]
          (swap! state
                 (fn [m]
                   (let [current (get m ck)]
                     (if (entry/cas-ok? current expected-revision)
                       (let [stored (entry/stamp normalized current)]
                         (reset! outcome {:ok? true :entry stored})
                         (assoc m ck stored))
                       (do (reset! outcome {:ok? false :error :wm/revision-conflict
                                            :current current})
                           m)))))
          (let [o @outcome]
            (when (:ok? o)
              (doseq [f (get @watchers ck)] (f (:entry o))))
            o)))))
  (watch! [_ workspace-id key f]
    (let [ck (entry/workspace-key workspace-id key)]
      (swap! watchers update ck (fnil conj []) f)
      (fn cancel [] (swap! watchers update ck (fn [fs] (vec (remove #(identical? % f) fs)))) nil)))
  (delta-since [_ workspace-id cursor]
    (let [entries (for [[[w _] e] @state :when (= w workspace-id)] e)]
      (entry/delta-of workspace-id entries cursor))))

(defn make-store
  "A fresh, empty ReferenceStore."
  []
  (->ReferenceStore (atom {}) (atom {})))

(defn store?
  "True when X is a ReferenceStore."
  [x]
  (instance? ReferenceStore x))
