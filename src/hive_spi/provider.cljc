(ns hive-spi.provider
  "Providers as data. The DIP seam, held as a VALUE rather than a mutable slot.

   A provider is two things: an IMPLEMENTATION of some port protocol, and a
   PROFILE — plain data describing its measured behaviour. Consumers read the
   profile and never branch on which provider it is, so adding one is an entry
   in a registry and no edit anywhere above.

   Complements `hive-spi.slot`: a slot is a mutable holder for the one
   implementation a process has installed; a registry here is an immutable map
   threaded through a call as an argument. Use a slot when the injection point
   is the process; use a registry when two of them must coexist — a request and
   its test, a tenant and another tenant.

   Three rules the API enforces rather than documents:

     1. A profile is validated at REGISTRATION, so a malformed provider fails
        at boot where an operator is watching, not at the first caller.
     2. Capability is read off the profile, never inferred from the id.
     3. A SUBJECT selects its provider (`for-subject`). A caller naming a
        provider is stating a claim to be checked against the subject, never
        the authority that resolves it."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT

;; =============================================================================
;; the profile
;; =============================================================================

(def Profile
  "The keys every provider profile carries. Deliberately open: a domain adds
   its own measured behaviour — thresholds, currencies, timeouts — and
   `profile-schema` closes over both."
  [:map
   [:provider/id :keyword]
   [:provider/capabilities {:optional true} [:set :keyword]]])

(defn profile-schema
  "`Profile` extended with a domain's own keys.

   `extra` is a vector of malli map entries, e.g.
   `[[:provider/currency :keyword] [:provider/min-confirmations :int]]`."
  [extra]
  (into Profile extra))

(defn entry
  "A registry entry: an implementation and the profile that describes it."
  [profile implementation]
  {:profile profile :impl implementation})

;; =============================================================================
;; the registry
;; =============================================================================

(defn- validate!
  [{:keys [profile impl]} {:keys [schema satisfies-port? label]}]
  (when (and schema (not (m/validate schema profile)))
    (throw (ex-info "provider profile does not satisfy its schema"
                    {:hive-spi/error :invalid-profile
                     :provider (:provider/id profile)
                     :label label
                     :explain (m/explain schema profile)})))
  (when (and satisfies-port? (not (satisfies-port? impl)))
    (throw (ex-info "provider does not implement its port"
                    {:hive-spi/error :port-not-implemented
                     :provider (:provider/id profile)
                     :label label})))
  (when-not (keyword? (:provider/id profile))
    (throw (ex-info "provider profile has no :provider/id"
                    {:hive-spi/error :invalid-profile :label label})))
  true)

(defn registry
  "A registry VALUE from `entries`, each `{:profile .. :impl ..}`.

   `opts` may carry:
     :schema           malli schema every profile must satisfy (default `Profile`)
     :satisfies-port?  predicate every implementation must pass, typically
                       `#(satisfies? IThing %)`
     :label            a name for this registry, reported in failures

   Both checks run HERE, so an unusable provider is a boot failure rather than
   a runtime surprise at the first caller."
  ([entries] (registry entries {}))
  ([entries opts]
   (let [opts (merge {:schema Profile} opts)]
     (reduce (fn [acc {:keys [profile] :as e}]
               (validate! e opts)
               (assoc acc (:provider/id profile) e))
             {}
             entries))))

(defn ids
  "Every registered provider id."
  [reg]
  (set (keys reg)))

(defn profile
  "The profile registered for `id`, or nil."
  [reg id]
  (get-in reg [id :profile]))

(defn implementation
  "The implementation registered for `id`, or nil."
  [reg id]
  (get-in reg [id :impl]))

(defn registered?
  [reg id]
  (contains? reg id))

(defn add
  "`reg` with `e` registered. Returns a new registry — registration is a value
   transformation, not a side effect."
  ([reg e] (add reg e {}))
  ([reg e opts]
   (validate! e (merge {:schema Profile} opts))
   (assoc reg (get-in e [:profile :provider/id]) e)))

(defn without
  "`reg` without `id`."
  [reg id]
  (dissoc reg id))

;; =============================================================================
;; reading behaviour off the profile
;; =============================================================================

(defn behaviour
  "The value `id`'s profile declares for `k`.

   The whole point of the profile: a component asks what a provider DOES
   instead of asking which provider it is."
  ([reg id k] (behaviour reg id k nil))
  ([reg id k default]
   (get (profile reg id) k default)))

(defn capabilities
  "The capability set `id` declares."
  [reg id]
  (or (:provider/capabilities (profile reg id)) #{}))

(defn capable?
  "True when `id` declares `capability`.

   An absent provider is not capable — the caller need not check twice."
  [reg id capability]
  (contains? (capabilities reg id) capability))

(defn with-capability
  "Every registered id that declares `capability`."
  [reg capability]
  (into #{} (filter #(capable? reg % capability)) (keys reg)))

(defn profiles-by
  "Registered profiles grouped by the value each declares for `k`."
  [reg k]
  (group-by #(get % k) (map :profile (vals reg))))

;; =============================================================================
;; selection — the subject chooses
;; =============================================================================

(defn subject-id
  "The provider id `subject` names, read with `subject-key`.

   `subject-key` is anything invocable on the subject: a keyword, a path fn,
   `(comp :provider/id :assignment)`."
  [subject subject-key]
  (subject-key subject))

(defn for-subject
  "The registry entry `subject` selects, or nil.

   This is the rule, expressed as the only convenient way to resolve a
   provider: the SUBJECT carries the id, so a caller cannot substitute one.
   A notice claiming to be from a provider is checked against the subject's
   own; it never selects its own authenticator."
  [reg subject subject-key]
  (get reg (subject-id subject subject-key)))

(defn via
  "Apply `f` to the implementation `subject` selects. Nil when unregistered.

   Keeps the nil-check out of every call site, which is where the discipline
   erodes first."
  [reg subject subject-key f]
  (when-let [found (for-subject reg subject subject-key)]
    (f (:impl found))))

(defn via-capable
  "`via`, but only when the selected provider declares `capability`.

   A provider whose profile does not admit an operation is never asked to
   perform it — the profile is the permission, not a comment."
  [reg subject subject-key capability f]
  (when-let [found (for-subject reg subject subject-key)]
    (when (capable? reg (get-in found [:profile :provider/id]) capability)
      (f (:impl found)))))

;; =============================================================================
;; conformance — for tests
;; =============================================================================

(defn conformance
  "Every registered entry checked against `opts`, as
   `{id {:profile-valid? bool :implements-port? bool}}`.

   The Liskov check a test makes once: every implementation is substitutable
   for the port, and every profile means what the schema says. A registry that
   was built with the same opts cannot fail this — which is the point of
   asserting it in a test that would notice if the checks were ever loosened."
  [reg {:keys [schema satisfies-port?] :or {schema Profile}}]
  (into {}
        (map (fn [[id {:keys [profile impl]}]]
               [id {:profile-valid? (m/validate schema profile)
                    :implements-port? (if satisfies-port?
                                        (boolean (satisfies-port? impl))
                                        true)}]))
        reg))

(defn conforming?
  "True when every registered provider passes `conformance`."
  [reg opts]
  (every? (fn [[_ v]] (and (:profile-valid? v) (:implements-port? v)))
          (conformance reg opts)))

(m/=> profile-schema [:=> [:cat [:sequential :any]] :any])
(m/=> ids [:=> [:cat [:maybe :map]] [:set :keyword]])
(m/=> capable? [:=> [:cat [:maybe :map] :keyword :keyword] :boolean])
(m/=> with-capability [:=> [:cat [:maybe :map] :keyword] [:set :keyword]])
(m/=> conforming? [:=> [:cat [:maybe :map] :map] :boolean])
