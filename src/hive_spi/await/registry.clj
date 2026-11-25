(ns hive-spi.await.registry
  "The injection point for await probe sources and secret schemes.

   An addon registers its IProbeSource at initialize! and unregisters it at
   shutdown!; a host's poll engine reads the source for a subscription type
   on EVERY tick, so a later registration (an addon mounted after boot, a
   hot reload) is what the next tick answers from, and an unregistered
   source resolves to nil, which the host reports loudly.

   Ports and the protocol each must satisfy (port->protocol), checked on
   register as in the kanban and memory registries:
     :source  IProbeSource   keyed by (:type (descriptor impl))
     :scheme  ISecretScheme  keyed by (scheme impl)
   A wiring error (wrong protocol, a malformed descriptor, an unknown port)
   throws at register: it must fail at startup, unlike a missing source,
   which a host degrades on."
  (:require [hive-spi.await.ports :as ports]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def port->protocol
  "Port key -> the protocol an implementation must satisfy."
  {:source ports/IProbeSource
   :scheme ports/ISecretScheme})

(defonce ^:private -registry (atom {:source {} :scheme {}}))

(defn- wiring-error [message data]
  (ex-info (str "await registry: " message)
           (merge {:error :await/invalid-provider} data)))

(defn key-of
  "The registry key IMPL is installed under for PORT: a source's descriptor
   :type, a scheme's name. Throws when IMPL does not satisfy the port's
   protocol, when the port is unknown, or when the key is malformed."
  [port impl]
  (let [proto (or (get port->protocol port)
                  (throw (ex-info (str "await registry: unknown port " (pr-str port))
                                  {:error :await/unknown-port :port port
                                   :known (vec (keys port->protocol))})))]
    (when-not (satisfies? proto impl)
      (throw (wiring-error "impl does not satisfy the port protocol"
                           {:port port :impl (str (type impl))})))
    (case port
      :source (let [d (ports/descriptor impl)]
                (when-not (ports/valid-descriptor? d)
                  (throw (wiring-error "malformed source descriptor"
                                       {:port port :impl (str (type impl))
                                        :explain (str (m/explain ports/SourceDescriptor d))})))
                (:type d))
      :scheme (let [s (ports/scheme impl)]
                (when-not (m/validate ports/SchemeName s)
                  (throw (wiring-error "malformed scheme name" {:port port :scheme (pr-str s)})))
                s))))

(defn register!
  "Install IMPL for PORT under its own key (key-of). Replaces whatever was
   under that key. Returns IMPL. Throws on a wiring error."
  [port impl]
  (let [k (key-of port impl)]
    (swap! -registry assoc-in [port k] impl)
    impl))

(defn unregister!
  "Drop what PORT holds under KEY. No-op when absent. Returns nil.

   The 3-arity drops it only when the value registered under KEY is
   `identical?` to INSTANCE: a caller that installed an implementation and
   was later displaced under the same key by another cannot unregister the
   replacement by accident. Returns nil either way."
  ([port key] (unregister! port key ::any))
  ([port key instance]
   (when-not (contains? port->protocol port)
     (throw (ex-info (str "await registry: unknown port " (pr-str port))
                     {:error :await/unknown-port :port port :known (vec (keys port->protocol))})))
   (swap! -registry update port
          (fn [m]
            (if (or (identical? instance ::any)
                    (identical? (get m key) instance))
              (dissoc m key)
              m)))
   nil))

(defn lookup
  "What PORT holds under KEY, or nil. Never throws on an absent key."
  [port key]
  (get-in @-registry [port key]))

(defn snapshot
  "A read-only {port {key impl}} snapshot."
  []
  @-registry)

(defn reset-registry!
  "Drop every source and scheme. Test support. Returns nil."
  []
  (reset! -registry {:source {} :scheme {}})
  nil)

;; =============================================================================
;; Sources
;; =============================================================================

(defn register-source!
  "Install SOURCE under its descriptor :type. Returns SOURCE."
  [source]
  (register! :source source))

(defn unregister-source!
  "Drop the source for TYPE. Returns nil.

   The 2-arity drops it only when the source registered for TYPE is
   `identical?` to SOURCE, so a caller displaced by a later registration
   under the same type cannot unregister the replacement."
  ([type] (unregister! :source type))
  ([type source] (unregister! :source type source)))

(defn source
  "The source registered for TYPE, or nil."
  [type]
  (lookup :source type))

(defn source-types
  "The registered source types, sorted."
  []
  (vec (sort (keys (:source @-registry)))))

(defn descriptors
  "{type -> descriptor} of every registered source."
  []
  (into {} (map (fn [[k s]] [k (ports/descriptor s)])) (:source @-registry)))

;; =============================================================================
;; Schemes
;; =============================================================================

(defn register-scheme!
  "Install SCHEME under its name. Returns SCHEME."
  [scheme]
  (register! :scheme scheme))

(defn unregister-scheme!
  "Drop the scheme NAME. Returns nil.

   The 2-arity drops it only when the scheme registered as NAME is
   `identical?` to SCHEME, so a caller displaced by a later registration
   under the same name cannot unregister the replacement."
  ([name] (unregister! :scheme name))
  ([name scheme] (unregister! :scheme name scheme)))

(defn scheme
  "The scheme registered as NAME, or nil."
  [name]
  (lookup :scheme name))

(defn scheme-names
  "The registered scheme names, sorted."
  []
  (vec (sort (keys (:scheme @-registry)))))

;; =============================================================================
;; Contracts
;; =============================================================================

(def Port
  "An await registry port key."
  (into [:enum] (keys port->protocol)))

(def Provider
  "An implementation of either await port."
  [:fn {:error/message "must satisfy IProbeSource or ISecretScheme"}
   #(or (satisfies? ports/IProbeSource %) (satisfies? ports/ISecretScheme %))])

(def Snapshot
  "What snapshot answers."
  [:map
   [:source [:map-of :keyword ports/ProbeSource]]
   [:scheme [:map-of :string ports/SecretScheme]]])

(m/=> key-of [:=> [:cat :any :any] [:or :keyword :string]])
(tc/ann-op key-of)
(m/=> register! [:=> [:cat Port Provider] Provider])
(tc/ann-op register!)
(m/=> unregister! [:function
                   [:=> [:cat :any :any] :nil]
                   [:=> [:cat :any :any :any] :nil]])
(tc/ann-op unregister!)
(m/=> lookup [:=> [:cat :any :any] [:maybe Provider]])
(tc/ann-op lookup)
(m/=> snapshot [:=> [:cat] Snapshot])
(tc/ann-op snapshot)
(m/=> reset-registry! [:=> [:cat] :nil])
(tc/ann-op reset-registry!)
(m/=> register-source! [:=> [:cat ports/ProbeSource] ports/ProbeSource])
(tc/ann-op register-source!)
(m/=> unregister-source! [:function
                          [:=> [:cat :any] :nil]
                          [:=> [:cat :any ports/ProbeSource] :nil]])
(tc/ann-op unregister-source!)
(m/=> source [:=> [:cat :any] [:maybe ports/ProbeSource]])
(tc/ann-op source)
(m/=> source-types [:=> [:cat] [:vector :keyword]])
(tc/ann-op source-types)
(m/=> descriptors [:=> [:cat] [:map-of :keyword ports/SourceDescriptor]])
(tc/ann-op descriptors)
(m/=> register-scheme! [:=> [:cat ports/SecretScheme] ports/SecretScheme])
(tc/ann-op register-scheme!)
(m/=> unregister-scheme! [:function
                          [:=> [:cat :any] :nil]
                          [:=> [:cat :any ports/SecretScheme] :nil]])
(tc/ann-op unregister-scheme!)
(m/=> scheme [:=> [:cat :any] [:maybe ports/SecretScheme]])
(tc/ann-op scheme)
(m/=> scheme-names [:=> [:cat] [:vector :string]])
(tc/ann-op scheme-names)
