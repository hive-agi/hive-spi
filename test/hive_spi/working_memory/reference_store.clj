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

(defn- supersedes-resolves?
  "True when ID names an entry of WORKSPACE-ID already in state M that is
   TERMINAL and is not the entry at THIS-CK. A supersession replaces a settled
   or abandoned claim; naming an id nobody minted, a live entry (two live
   entries claiming to replace each other), or the key's own entry is not one.
   Pure."
  [m workspace-id id this-ck]
  (boolean
   (some (fn [[[w _ :as ck] e]]
           (and (= w workspace-id)
                (not= ck this-ck)
                (= (:id e) id)
                (entry/terminal? e)))
         m)))

(defrecord ReferenceStore [state watchers locks]
  ports/IWorkingMemoryStore
  (get-entry [_ workspace-id key]
    (get-in @state [(entry/workspace-key workspace-id key)]))
  (put-cas! [_ e expected-revision]
    (let [normalized (entry/normalize e)
          ck (entry/workspace-key (:workspace-id normalized) (:key normalized))
          ;; One lock per composite key: the whole read-judge-write happens
          ;; under it, so two writers with the same expected revision cannot
          ;; both win, and the watcher calls below stay in write order.
          lock (or (get @locks ck)
                   (get (swap! locks assoc ck (Object.)) ck))]
      (locking lock
        (let [outcome (atom nil)]
          (swap! state
                 (fn [m]
                   (let [current (get m ck)
                         new-status (:status normalized)
                         cur-status (:status current)]
                     (cond
                       ;; A terminal entry is immutable, FULL STOP. The gate reads
                       ;; CURRENT's status, never `supersedes?`: at this key
                       ;; CURRENT is this key's own entry, so a write naming its
                       ;; own id is a self-supersession — a mutation in place —
                       ;; and a legitimate supersession lands at ANOTHER key,
                       ;; where CURRENT is nil or live, so it never reaches here.
                       (entry/terminal? current)
                       (do (reset! outcome {:ok? false :error :wm/terminal :current current})
                           m)

                       ;; A create may not name a terminal status: there is no
                       ;; entry state for a key born :settled or :abandoned.
                       (and (nil? current) (entry/terminal? normalized))
                       (do (reset! outcome {:ok? false :error :wm/invalid-transition
                                            :current current})
                           m)

                       ;; a status change must be a move the table allows
                       (and (some? current)
                            (not= new-status cur-status)
                            (not (lifecycle/allowed? cur-status new-status)))
                       (do (reset! outcome {:ok? false :error :wm/invalid-transition
                                            :current current})
                           m)

                       ;; :supersedes, when present, must name a TERMINAL entry
                       ;; that exists in this workspace.
                       (and (some? (:supersedes normalized))
                            (not (supersedes-resolves? m (:workspace-id normalized)
                                                       (:supersedes normalized) ck)))
                       (do (reset! outcome {:ok? false :error :wm/not-found :current current})
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
              ;; A throwing watcher must not surface from put-cas! after the
              ;; commit: the write already landed, so a bad watcher is dropped.
              ;; The calls happen inside the key's lock, so a watcher observes
              ;; writes in the order the store accepted them.
              (doseq [f (get @watchers ck)]
                (try (f (:entry o)) (catch Exception _ nil))))
            o)))))
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
  (->ReferenceStore (atom {}) (atom {}) (atom {})))

(defn store?
  "True when X is a ReferenceStore."
  [x]
  (instance? ReferenceStore x))
