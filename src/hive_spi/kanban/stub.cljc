(ns hive-spi.kanban.stub
  "A recording, atom-backed kanban provider for tests.

   Ships in src so every consumer's tests drive the same double, and the
   double is held to the same `hive-spi.kanban/conformance` cases the real
   provider passes. Every call is appended to `:calls` as [method argument]."
  (:require [hive-spi.kanban :as k]
            [hive-spi.kanban.registry :as r]))

;; SPDX-License-Identifier: MIT

(defn- ->status [s] (if (keyword? s) (name s) s))

(defrecord RecordingKanban [board calls]
  k/IKanbanRead
  (list-tasks [_ query]
    (swap! calls conj [:list-tasks query])
    (let [status (->status (:status query))]
      (->> (vals @board)
           (filter #(or (nil? status) (= status (:status %))))
           (sort-by :id)
           vec)))
  (get-task [_ id]
    (swap! calls conj [:get-task id])
    (get @board id))
  k/IKanbanWrite
  (transition! [_ {:keys [task-id new-status] :as req}]
    (swap! calls conj [:transition! req])
    (if-let [task (get @board task-id)]
      (let [moved (assoc task :status new-status)]
        (swap! board assoc task-id moved)
        {:ok moved})
      {:err {:error :kanban/not-found :message (str "no task " task-id)}}))
  (create-task! [_ {:keys [title] :as req}]
    (swap! calls conj [:create-task! req])
    (if (and (string? title) (seq title))
      (let [id (str "stub-" (inc (count @board)))]
        (swap! board assoc id {:id id :title title
                               :status (or (:status req) "todo")
                               :priority (or (:priority req) "medium")})
        {:ok {:id id}})
      {:err {:error :kanban/validation :message "title required"}})))

(defn recording-kanban
  "A RecordingKanban seeded with TASKS, a seq of Task maps."
  ([] (recording-kanban []))
  ([tasks]
   (->RecordingKanban (atom (into {} (map (juxt :id identity)) tasks)) (atom []))))

(defn board
  "The stub's current {id -> Task} snapshot."
  [stub]
  @(:board stub))

(defn calls
  "The stub's recorded calls, oldest first."
  [stub]
  @(:calls stub))

(defn install!
  "Register STUB under both kanban ports. Returns STUB."
  [stub]
  (r/register! :IKanbanRead stub)
  (r/register! :IKanbanWrite stub)
  stub)

(defn uninstall!
  "Revert both kanban ports to their Noop defaults. Returns nil."
  []
  (r/unregister! :IKanbanRead)
  (r/unregister! :IKanbanWrite)
  nil)
