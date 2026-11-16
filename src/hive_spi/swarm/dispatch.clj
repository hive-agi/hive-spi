(ns hive-spi.swarm.dispatch
  "Open port for dispatching one task to a worker agent.

   A scheduler (the DAG waves, a plan runner) hands a task to a dispatcher and
   gets back who runs it; it never names how the agent is spawned. Closed
   implementations (a swarm with grants, budgets and park/resume) and the open
   local one register here, and the host picks between them.

   Contract:
     (dispatch-task! d task opts) -> Result
       ok  {:agent-id str :run-id any}
       err an error value naming why no agent was started
     (capabilities d) -> set of keywords, e.g. #{:park :budget :grant :recursive}

   Completions are not part of this port. They keep flowing on the existing
   wave channel, hive.v1.wave.<run-id>.completed.<task-id>.

   TASK is {:task-id :title :description :role :project-id ...}.
   OPTS is {:cwd :presets :run-id :max-budget-usd :parent :rules :spawned-by
   :prefer-lightweight? ...}; an implementation ignores keys it does not know.

   Registry: dispatchers register under an id with a priority (lower is
   preferred). `registered` answers them in preference order; the host decides
   which one serves a call (hive-mcp does it through its :task-dispatch
   transport chain). Leaf namespace: no host dependency."
  (:require [hive-dsl.result :as r]))

;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Port
;; =============================================================================

(defprotocol ITaskDispatcher
  (dispatch-task! [this task opts]
    "Start an agent for TASK. Answers (ok {:agent-id :run-id}) or an err.")
  (capabilities [this]
    "The set of capability keywords this dispatcher honours."))

(defprotocol IDispatcherHealth
  "Optional extension: a dispatcher that can say whether it can serve now."
  (health [this]
    "Answers (ok info) when ready to dispatch, an err otherwise. Cheap, no side effects."))

;; =============================================================================
;; Values
;; =============================================================================

(def DispatchTask
  "The task handed to dispatch-task!. Open map."
  [:map [:task-id :string]])

(def Dispatched
  "What a successful dispatch answers."
  [:map [:agent-id :string] [:run-id :any]])

(def known-capabilities
  "Capability keywords with a stated meaning. A dispatcher may add its own."
  #{:park :budget :grant :recursive})

(defn valid-task?
  "True when TASK is a map with a non-blank string :task-id."
  [task]
  (and (map? task)
       (string? (:task-id task))
       (not (zero? (count (.trim ^String (:task-id task)))))))

(defn valid-dispatched?
  "True when X is a Dispatched value."
  [x]
  (and (map? x)
       (string? (:agent-id x))
       (contains? x :run-id)))

(defn invalid-task
  "The error a dispatcher answers for a task that fails `valid-task?`.
   Addressing, so a transport chain does not fall back on it."
  [task]
  (r/err :dispatch/invalid-task
         {:error/kind :addressing
          :message    "task needs a non-blank string :task-id"
          :task       (when (map? task) (select-keys task [:task-id :title]))}))

;; =============================================================================
;; Registry
;; =============================================================================

(defonce ^:private -dispatchers (atom {}))

(defn- implements-port?
  [impl]
  (satisfies? ITaskDispatcher impl))

(defn register-dispatcher!
  "Install IMPL under ID with PRIORITY (lower is preferred). Re-registering an
   id replaces it. Throws when IMPL does not satisfy ITaskDispatcher: a wiring
   error fails at startup. Answers ID."
  [id impl priority]
  (when-not (keyword? id)
    (throw (ex-info "dispatcher id must be a keyword" {:id id})))
  (when-not (integer? priority)
    (throw (ex-info "dispatcher priority must be an integer" {:id id :priority priority})))
  (when-not (implements-port? impl)
    (throw (ex-info "impl does not satisfy ITaskDispatcher" {:id id :impl (type impl)})))
  (swap! -dispatchers assoc id {:impl impl :priority priority})
  id)

(defn unregister-dispatcher!
  "Drop the dispatcher under ID. Idempotent. Answers ID."
  [id]
  (swap! -dispatchers dissoc id)
  id)

(defn reset-dispatchers!
  "Drop every dispatcher. Test support."
  []
  (reset! -dispatchers {})
  nil)

(defn registered
  "Registered dispatchers as [{:id :impl :priority}], most preferred first;
   equal priorities order by id."
  []
  (->> @-dispatchers
       (map (fn [[id v]] (assoc v :id id)))
       (sort-by (juxt :priority :id))
       vec))

(defn dispatcher
  "The implementation registered under ID, or nil."
  [id]
  (get-in @-dispatchers [id :impl]))

(defn registered?
  "True when a dispatcher is registered under ID."
  [id]
  (contains? @-dispatchers id))

(defn dispatcher-health
  "Health of IMPL: its own answer when it implements IDispatcherHealth, else
   ok (a dispatcher with no probe is assumed ready; the call itself reports)."
  [impl]
  (if (satisfies? IDispatcherHealth impl)
    (try
      (r/ensure-result (health impl))
      (catch Exception e
        (r/err :dispatch/health-exception
               {:error/kind :unavailable :message (ex-message e)})))
    (r/ok {:probe? false})))
