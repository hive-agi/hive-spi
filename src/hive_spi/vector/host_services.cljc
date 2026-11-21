(ns hive-spi.vector.host-services
  "What a vector-store BACKEND asks of its host, as data.

   A backend addon (hive-chroma, hive-milvus, ...) stores memory entries, but
   the policy around a write is the host's: which embedding provider a type
   routes to, which space it emits, the process-wide embedding gate, config
   lookup, federated ingest search, and the host's own named-collection port.
   Reaching those by resolving host symbols makes the addon depend on the host
   by name; this namespace inverts that. The HOST publishes a map of
   capability keyword -> fn at boot, and the backend calls through it.

   Same shape as `hive-spi.editor.services`: the contract is the key set
   (`catalog`), registration validates it, and adding a capability is
   additive. A backend reads a capability through `soft`, which resolves at
   CALL time, so a host that registers after the backend loaded is still
   seen, and an absent capability degrades to the fallback the call site
   declares instead of a NullPointerException.

   The pure embedding-space value layer is not here: it needs no host and
   lives in `hive-spi.embeddings.space`."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(def catalog
  "Every capability a backend may ask for, keyword -> what it means. A host
   registers the subset it provides; unknown keys are accepted (additive), but
   only these are part of the contract."
  {;; active (global fallback) embedding provider
   :embedding/require!               "() throws ex-info {:type :no-embedding-provider} when no provider is configured"
   :embedding/configured?            "() true iff a global provider is configured"
   :embedding/provider               "() the global provider, or nil"
   :embedding/set-provider!          "(provider) / (provider spec) install the global provider"
   :embedding/reset-provider!        "() clear the global provider"
   :embedding/dimension              "(provider) the dimension of PROVIDER's vectors"
   :embedding/embed-text             "(provider text) one vector, through the host's embedding gate"
   :embedding/embed-batch            "(provider texts) one vector per text, through the host's embedding gate"
   :embedding/provider-for           "(collection) the provider routed for COLLECTION"
   :embedding/embed-text-for         "(collection text) embed with COLLECTION's provider"
   :embedding/embed-batch-for        "(collection texts) embed a batch with COLLECTION's provider"
   :embedding/dimension-for          "(collection) the dimension of COLLECTION's provider"
   ;; per-type routing
   :routing/no-embed-type?           "(type) true when entries of TYPE are stored without a vector"
   :routing/resolve-for-type         "(type) {:provider .. :collection ..} routed for TYPE, or nil"
   :routing/resolve-for-type+size    "(type text) the route for TYPE sized for TEXT, or nil"
   :routing/validate-content-size!   "(text resolved) throws when TEXT exceeds the route's input budget"
   :routing/space-for-resolved       "(resolved opts) the EmbeddingSpace the route emits, or nil"
   :routing/default-space            "(opts) the EmbeddingSpace of the global provider, or nil"
   :routing/sibling-failover         "(resolved) an alternative route when RESOLVED's provider fails, or nil"
   :routing/type->collection-names   "(type) the collection names entries of TYPE may live in"
   :routing/all-collection-names     "() every routed collection name"
   ;; query shaping and config
   :query/shape                      "(template text) TEXT shaped by the query TEMPLATE"
   :config/get-in                    "(path) / (path default) a host config value"
   ;; federated ingest search
   :ingest/resolve-search            "() the ingest search fn, or nil"
   :ingest/normalize-results         "(results) ingest results in memory-entry shape"
   :ingest/merge-and-rerank          "(default-results ingest-results limit) one ranked list"
   ;; the one process-wide embedding gate
   :gate/with-permit                 "(gate lane f) call F holding a permit of GATE in LANE"
   :gate/process-gate                "() the process-wide embedding gate, or nil"
   :gate/lane                        "() the caller's current embedding lane, or nil"
   ;; the host's named-collection port
   :vector/port                      "() the IVectorCollectionStore protocol the host consumes"
   :vector/store-set?                "() true iff the host has a vector-collection store installed"
   :vector/set-store!                "(store) install STORE as the host's vector-collection store"})

(defonce ^:private services (atom {}))

(defn register-services!
  "Publish CAPABILITIES, a map of keyword -> fn. Merges into what is already
   registered, so a host may register in stages. Returns the resulting map.

   Throws when CAPABILITIES is not a map of keywords to fns: a malformed
   registration must fail at the registrar, not at the first caller."
  [capabilities]
  (when-not (map? capabilities)
    (throw (ex-info "Host services must be a map of keyword -> fn"
                    {:error :host-services/invalid :got (type capabilities)})))
  (doseq [[k f] capabilities]
    (when-not (and (keyword? k) (ifn? f))
      (throw (ex-info "Host service must be keyword -> fn"
                      {:error :host-services/invalid :capability k :got (type f)}))))
  (swap! services merge capabilities))

(defn unregister-services!
  "Drop the capabilities named by KS, or every capability. Returns nil."
  ([] (reset! services {}) nil)
  ([ks] (swap! services #(apply dissoc % ks)) nil))

(defn registered
  "A read-only {capability -> fn} snapshot."
  []
  @services)

(defn capabilities
  "The registered capability keys, as a set."
  []
  (set (keys @services)))

(defn capability
  "The fn registered for CAP, or nil."
  [cap]
  (get @services cap))

(defn available?
  "True iff CAP is registered."
  [cap]
  (some? (capability cap)))

(defn invoke
  "Call CAP with ARGS. Throws ex-info :host-services/unavailable when the host
   registered no such capability."
  [cap & args]
  (if-let [f (capability cap)]
    (apply f args)
    (throw (ex-info (str "Host service unavailable: " cap)
                    {:error :host-services/unavailable
                     :capability cap
                     :available (vec (sort (capabilities)))}))))

(defn soft
  "A variadic fn standing in for CAP: each call invokes whatever is registered
   for CAP at that moment, or (apply absent-fn args) when nothing is. Without
   ABSENT-FN an absent capability throws like `invoke`."
  ([cap] (soft cap nil))
  ([cap absent-fn]
   (fn [& args]
     (if-let [f (capability cap)]
       (apply f args)
       (if absent-fn
         (apply absent-fn args)
         (apply invoke cap args))))))

;; =============================================================================
;; Contracts
;; =============================================================================

(def Capabilities
  "A capability map: keyword -> fn. Generates maps of constant fns."
  [:map-of :keyword [:fn {:gen/schema :int :gen/fmap constantly} ifn?]])

(m/=> register-services! [:=> [:cat Capabilities] Capabilities])
(m/=> unregister-services! [:function
                            [:=> [:cat] :nil]
                            [:=> [:cat [:sequential :keyword]] :nil]])
(m/=> registered [:=> [:cat] Capabilities])
(m/=> capabilities [:=> [:cat] [:set :keyword]])
(m/=> capability [:=> [:cat :keyword] [:maybe ifn?]])
(m/=> available? [:=> [:cat :keyword] :boolean])
;; invoke returns whatever the registered capability returns.
(m/=> invoke [:=> [:cat :keyword [:* :any]] :any])
(m/=> soft [:function
            [:=> [:cat :keyword] ifn?]
            [:=> [:cat :keyword [:maybe ifn?]] ifn?]])
