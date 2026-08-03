(ns hive-spi.editor.services
  "Editor SERVICE capabilities as data — the function-level companion to
   hive-spi.editor.ports.

   The ports carry the TOOL surface: MCP params in, MCP response out. Host
   code that is not a tool needs something else — `(eval-elisp \"(+ 1 2)\")`
   returning {:success bool :result any} — and wrapping every such call in an
   MCP envelope would be a lie about what is happening.

   Rather than mint a protocol method per function (25+ and growing, each one
   a breaking change to add), an implementation publishes a MAP of capability
   keyword -> fn. This is provider-behaviour-as-data: the contract is the key
   set, the registry validates it, and adding a capability is additive.

   A host resolves capabilities through `invoke` (or `capability` when it
   wants to check first). An unregistered capability is an explicit
   :editor/capability-unavailable result — never a NullPointerException, and
   never a silent nil that a caller mistakes for a legitimate empty answer.")

;; SPDX-License-Identifier: MIT

(defonce ^:private services (atom {}))

(defn register-services!
  "Publish CAPABILITIES, a map of keyword -> fn, under KEY (or :default).
   Merges into whatever that key already holds, so an implementation may
   register in stages. Returns the resulting capability map.

   Throws when CAPABILITIES is not a map of keywords to fns: a malformed
   registration must fail at the registrar, not at the first caller."
  ([capabilities] (register-services! :default capabilities))
  ([key capabilities]
   (when-not (map? capabilities)
     (throw (ex-info "Editor capabilities must be a map of keyword -> fn"
                     {:key key :got (type capabilities)})))
   (doseq [[k f] capabilities]
     (when-not (and (keyword? k) (ifn? f))
       (throw (ex-info "Editor capability must be keyword -> fn"
                       {:key key :capability k :got (type f)}))))
   (get (swap! services update key merge capabilities) key)))

(defn unregister-services!
  "Drop every capability registered under KEY (or :default). Returns nil."
  ([] (unregister-services! :default))
  ([key] (swap! services dissoc key) nil))

(defn registered
  "A read-only {key -> {capability -> fn}} snapshot."
  []
  @services)

(defn capabilities
  "The capability keys available under KEY (or :default), as a set."
  ([] (capabilities :default))
  ([key] (set (keys (get @services key)))))

(defn capability
  "The fn registered for CAP under KEY (or :default), or nil when absent."
  ([cap] (capability :default cap))
  ([key cap] (get-in @services [key cap])))

(defn available?
  "True iff CAP is registered under KEY (or :default)."
  ([cap] (available? :default cap))
  ([key cap] (some? (capability key cap))))

(defn unavailable
  "The explicit result returned when CAP has no implementation. Shaped like
   the failure maps the editor surface already returns, so a caller that only
   checks :success takes the failure branch instead of reading nil as data."
  [key cap]
  {:success false
   :success? false
   :error :editor/capability-unavailable
   :capability cap
   :registry-key key
   :available (vec (sort (capabilities key)))})

(defn invoke
  "Call CAP under KEY (or :default) with ARGS. Returns `unavailable` when no
   implementation is registered — the one place a missing editor becomes a
   value instead of an exception."
  [key cap & args]
  (if-let [f (capability key cap)]
    (apply f args)
    (unavailable key cap)))

(defn invoke-default
  "invoke against the :default key."
  [cap & args]
  (apply invoke :default cap args))

(defn reset-services!
  "Remove every registered capability under every key. Returns nil."
  []
  (reset! services {})
  nil)
