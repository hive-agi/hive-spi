(ns hive-spi.kg.factory
  "Late-bound construction of an IKGStore for a backend keyword.

   A consumer names a backend (:datahike, :datalevin, ...) and receives a value
   satisfying `hive-spi.kg.protocol/IKGStore`. The concrete artifact is resolved
   from the classpath at call time, so no consumer declares a storage engine in
   its `:require` or its deps.edn.

   Extension point: (defmethod backend->store :my-backend [_ opts] <IKGStore>)."
  (:require [hive-dsl.result :as r]))

(defn resolve-sym
  "Resolve `sym`, loading its namespace on demand.
   Returns the var, or nil when the artifact is absent from the classpath."
  [sym]
  (r/rescue nil (requiring-resolve sym)))

(defmulti backend->store
  "Construct a store for `backend` from the `opts` map.
   Returns an IKGStore, or nil when the backend cannot be constructed."
  (fn [backend _opts] backend))

(defmethod backend->store :default [_ _] nil)

(defn- from-artifact
  "Call `create-store-sym` with `opts` when its artifact is on the classpath."
  [create-store-sym opts]
  (when-let [create-fn (resolve-sym create-store-sym)]
    (r/rescue nil (create-fn opts))))

(defmethod backend->store :datahike [_ opts]
  (from-artifact 'hive-datahike.kg.store/create-store opts))

(defmethod backend->store :datalevin [_ opts]
  (from-artifact 'hive-datalevin.kg.store/create-store opts))

(defn supported-backends
  "The set of backend keywords with a registered `backend->store` method.
   Registration alone does not imply the artifact is on the classpath."
  []
  (disj (set (keys (methods backend->store))) :default))

(defn make-store
  "Construct a store for `backend`, defaulting `opts` to {}.

   Returns Result: ok <IKGStore>, or err :kg.factory/backend-unavailable with
   {:backend :supported} when no method is registered or the artifact is absent."
  ([backend] (make-store backend {}))
  ([backend opts]
   (if-let [store (backend->store backend opts)]
     (r/ok store)
     (r/err :kg.factory/backend-unavailable
            {:backend   backend
             :supported (supported-backends)}))))

(defprotocol IStoreFactory
  "Construction seam for an IKGStore. Injectable so a consumer can be handed a
   stub or in-memory store without going through classpath resolution."
  (create [this backend opts]
    "Return Result: ok IKGStore, or err describing why construction failed."))

(defrecord LateBoundFactory []
  IStoreFactory
  (create [_ backend opts] (make-store backend opts)))

(defn late-bound-factory
  "The production factory: resolves backends through `backend->store`."
  []
  (->LateBoundFactory))
