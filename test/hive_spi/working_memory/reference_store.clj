(ns hive-spi.working-memory.reference-store
  "A test-only in-memory IWorkingMemoryStore, the reference implementation
   hive-spi.working-memory.conformance is written against.

   It lives under test/ on purpose: the contract is ports, schema and
   lifecycle, and a real store belongs to an addon (a JetStream KV bucket, a
   datalevin table). This one exists only so the suite has something faithful
   to run against. It lives under test/ and does NOT ship in the jar, so a
   host that wants a store for its own tests copies this file or writes its
   own.

   It is atom-backed, single-process and CAS-correct: put-cas! judges the
   expected revision, enforces terminal immutability and the transition
   table, keeps the key's identity, and fires watchers (a throwing watcher is
   dropped, never surfaced after the commit); delta-since re-delivers a key
   whose revision advanced and honours a size budget."
  (:require [hive-spi.working-memory.entry :as entry]
            [hive-spi.working-memory.lifecycle :as lifecycle]
            [hive-spi.working-memory.ports :as ports]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defrecord ReferenceStore [state watchers]
  ports/IWorkingMemoryStore
  (get-entry [_ workspace-id key]
    (get-in @state [(entry/workspace-key workspace-id key)]))
  (put-cas! [_ e expected-revision]
    (let [normalized (entry/normalize e)]
      (when-not (try (m/validate ports/WorkingEntry normalized)
                     (catch Exception _ false))
        (throw (ports/refusal :wm/invalid-entry "entry does not conform"
                              {:explanation (try (m/explain ports/WorkingEntry normalized)
                                                 (catch Exception _ nil))})))
      (let [ck (entry/workspace-key (:workspace-id normalized) (:key normalized))
            ;; the whole read-judge-write happens in one swap! so two writers
            ;; with the same expected revision cannot both win
            outcome (atom nil)]
        (swap! state
               (fn [m]
                 (let [current (get m ck)
                       supersedes? (entry/supersedes? normalized current)
                       new-status (:status normalized)
                       cur-status (:status current)]
                   (cond
                     ;; a terminal entry is immutable except by supersession
                     (and (entry/terminal? current) (not supersedes?))
                     (do (reset! outcome {:ok? false :error :wm/terminal :current current})
                         m)

                     ;; a status change must be a move the table allows
                     (and (some? current)
                          (not= new-status cur-status)
                          (not (lifecycle/allowed? cur-status new-status)))
                     (do (reset! outcome {:ok? false :error :wm/invalid-transition :current current})
                         m)

                     (entry/cas-ok? current expected-revision)
                     (let [stored (entry/stamp normalized current)]
                       (reset! outcome {:ok? true :entry stored})
                       (assoc m ck stored))

                     :else
                     (do (reset! outcome {:ok? false :error :wm/revision-conflict
                                          :current current})
                         m)))))
        (let [o @outcome]
          (when (:ok? o)
            ;; a throwing watcher must not surface from put-cas! after the
            ;; commit: the write already landed, so a bad watcher is dropped
            (doseq [f (get @watchers ck)]
              (try (f (:entry o)) (catch Exception _ nil))))
          o))))
  (watch! [_ workspace-id key f]
    (let [ck (entry/workspace-key workspace-id key)]
      (swap! watchers update ck (fnil conj []) f)
      (fn cancel [] (swap! watchers update ck (fn [fs] (vec (remove #(identical? % f) fs)))) nil)))
  (delta-since [_ workspace-id cursor opts]
    (let [entries (for [[[w _] e] @state :when (= w workspace-id)] e)]
      (entry/delta-of workspace-id entries cursor opts))))

(defn make-store
  "A fresh, empty ReferenceStore."
  []
  (->ReferenceStore (atom {}) (atom {})))

(defn store?
  "True when X is a ReferenceStore."
  [x]
  (instance? ReferenceStore x))
