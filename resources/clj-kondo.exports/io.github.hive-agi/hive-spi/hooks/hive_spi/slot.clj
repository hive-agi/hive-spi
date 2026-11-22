(ns hooks.hive-spi.slot
  "clj-kondo hooks for hive-spi.slot/defslot and defregistry.

   Both macros define one public fn per accessor the spec map names. kondo
   cannot see those defs, so every caller of a port accessor would read as an
   unresolved var. The hook rewrites the macro call into the equivalent
   (do (defn name [args] slot) ...) so the names resolve and the arities are
   checked."
  (:require [clj-kondo.hooks-api :as api]))

;; SPDX-License-Identifier: MIT

(def ^:private role-arglists
  "role -> the arglists the macro emits for it (underscored: the hook only
   needs the arity, not a body that reads the args)."
  {:set              [['_impl]]
   :get              [[]]
   :clear            [[]]
   :present?         [[]]
   :register         [['_key '_impl]]
   :unregister       [['_key]]
   :snapshot         [[]]
   :lookup           [['_key]]
   :require          [[] ['_key]]
   :set-default      [['_impl]]
   :default-present? [[]]
   :reset            [[]]})

(defn- spec-pairs
  [spec-node]
  (partition 2 (:children spec-node)))

(defn- accessor-role?
  [k-node]
  (contains? role-arglists (api/sexpr k-node)))

(defn- arity-node
  "(argv slot-node) for one emitted arity, so the slot reads as used."
  [argv slot-node]
  (api/list-node [(api/vector-node (mapv api/token-node argv)) slot-node]))

(defn- accessor-node
  [slot-node [k-node name-node]]
  (api/list-node
   (into [(api/token-node 'defn) name-node]
         (map #(arity-node % slot-node))
         (role-arglists (api/sexpr k-node)))))

(defn defslot
  [{:keys [node]}]
  (let [[_ slot-node spec-node] (:children node)
        pairs    (spec-pairs spec-node)
        ;; the non-accessor values (:noun, :doc, :set-default-via ...) are
        ;; still analysed, so a symbol one names reads as used
        others   (api/vector-node (mapv second (remove (comp accessor-role? first) pairs)))]
    {:node (api/list-node
            (into [(api/token-node 'do) others]
                  (map #(accessor-node slot-node %))
                  (filter (comp accessor-role? first) pairs)))}))
