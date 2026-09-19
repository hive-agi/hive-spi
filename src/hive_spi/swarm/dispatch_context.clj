(ns hive-spi.swarm.dispatch-context
  "IDispatchContext: what a coordinator hands an agent as its task context.

   A dispatch carries either plain text or references that are expanded on
   the receiving side. Both are values of one protocol, so the dispatch path
   never branches on which it got:

     TextContext  the prompt, verbatim
     RefContext   the prompt plus context-store refs and KG node ids, and the
                  function that turns those into text

   Host-free by construction. A RefContext does not know HOW references are
   reconstructed: the function is injected as data at creation time, and a
   RefContext built without one resolves to its prompt and its refs alone. A
   host that owns a reconstruction (or a richer context kind) wraps these
   constructors; nothing here resolves a host namespace.

   Contract, same as every hive-spi port: resolve-context never throws. A
   reconstruction that throws is reported inside the resolved prompt, so the
   agent still receives its task.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded. Re-evaluating this namespace will not orphan existing
   implementations. Consumers must NOT re-defprotocol this name.")

;; SPDX-License-Identifier: MIT

(defonce ^:private -idispatchcontext-defined? (atom false))

(when (compare-and-set! -idispatchcontext-defined? false true)
  (defprotocol IDispatchContext
    "Task context for an agent dispatch."
    (resolve-context [this]
      "A consumable map with at minimum {:prompt string}. Never throws.")
    (context-type [this]
      "The keyword identifying this kind of context, e.g. :text, :ref.")))

(defrecord TextContext [prompt]
  IDispatchContext
  (resolve-context [_] {:prompt prompt})
  (context-type [_] :text))

(defn- reconstruct
  "The text RECONSTRUCT-FN makes of the references, a failure notice when it
   throws, nil when there is no function or it answers nil."
  [reconstruct-fn ctx-refs kg-node-ids scope]
  (when reconstruct-fn
    (try
      (reconstruct-fn ctx-refs kg-node-ids scope)
      (catch Throwable e
        (str "Context reconstruction failed: " (.getMessage e))))))

;; prompt         the base task prompt, always present as the fallback
;; ctx-refs       category -> context-store id, e.g. {:axioms "ctx-123"}
;; kg-node-ids    KG node ids to expand
;; scope          project scope the references resolve in
;; reconstruct-fn (fn [ctx-refs kg-node-ids scope] -> string | nil), or nil
(defrecord RefContext [prompt ctx-refs kg-node-ids scope reconstruct-fn]
  IDispatchContext
  (resolve-context [_]
    (let [reconstructed (reconstruct reconstruct-fn ctx-refs kg-node-ids scope)]
      (cond-> {:prompt prompt}
        (seq ctx-refs)    (assoc :refs (vec (vals ctx-refs)))
        (seq kg-node-ids) (assoc :kg-nodes kg-node-ids)
        reconstructed     (assoc :reconstructed reconstructed
                                 :prompt (str reconstructed "\n\n---\n\n" prompt)))))
  (context-type [_] :ref))

(defn ->text-context
  "A TextContext wrapping PROMPT."
  [prompt]
  (->TextContext prompt))

(defn ->ref-context
  "A RefContext for pass-by-reference dispatch. :reconstruct-fn is
   (fn [ctx-refs kg-node-ids scope] -> string | nil); without one the
   context resolves to PROMPT and its references, unexpanded."
  [prompt {:keys [ctx-refs kg-node-ids scope reconstruct-fn]}]
  (->RefContext prompt
                (or ctx-refs {})
                (vec (or kg-node-ids []))
                scope
                reconstruct-fn))

(defn ensure-context
  "PROMPT-OR-CONTEXT as an IDispatchContext: itself when it already is one,
   else a TextContext of its string form."
  [prompt-or-context]
  (if (satisfies? IDispatchContext prompt-or-context)
    prompt-or-context
    (->TextContext (str prompt-or-context))))
