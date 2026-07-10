(ns hive-spi.schema.registry
  "Central malli registry for hive core-op schemas.

   Holds named schemas under an atom-backed mutable registry composed over
   malli's defaults. Named refs (e.g. :hive/result) resolve through this
   registry — use the wrappers here (`schema`/`validate`/`explain`) or pass
   `{:registry registry}` explicitly; the malli default registry does NOT
   know :hive/* keys.

   `register!` is the open extension point: addons contribute their own
   op/field schemas by key without editing this namespace.

   Seeded core schemas:
     :hive/error-category        qualified keyword (Result error tag)
     :hive/known-error-category  error-category in the dsl taxonomy registry
     :hive/ok                    {:ok value}
     :hive/err                   {:error category ...}
     :hive/result                Result sum: ok | err

   Canonical ownership (MALLI-P4 spec<->malli convergence, one truth per concept):
     - malli (here)                 owns Result DATA-SHAPE + coercion (der/compile-op).
     - hive-dsl.result.spec         owns fn/macro BEHAVIORAL contracts (fdef/instrument!).
     - hive-dsl.result.taxonomy     owns the error-CATEGORY set (single source; both
                                    this registry and the spec derive `known-error?`
                                    from it — never hardcode categories).
     - hive-test.generators.result  owns GENERATION (malli.generator over these schemas
                                    + taxonomy-sourced categories).
   An executable agreement property test (hive-spi.schema.result-agreement-test)
   pins the exact relationship so the validation systems cannot drift.
   :hive/ok is biconditional with r/ok? (pure :ok-key membership). :hive/err is
   STRICTER than r/err?: it also requires the :error value to be a qualified keyword
   (the taxonomy convention), so r/err? holds whenever :hive/err does but not the
   reverse. :hive/known-error-category is the strict category refinement; its
   :gen/elements snapshots the taxonomy at load (base categories) so the schema is
   self-generatable, while realistic dynamic generation lives in hive-test."
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [hive-dsl.result.taxonomy :as tax]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; ---------------------------------------------------------------------------
;; Mutable extension registry (OCP seam)
;; ---------------------------------------------------------------------------

(defonce ^:private schemas* (atom {}))

(defn register!
  "Register/replace named schema `k` (qualified keyword) with malli form
   `schema`. Idempotent per key. Returns `k`."
  [k schema]
  (swap! schemas* assoc k schema)
  k)

(defn register-all!
  "Register a bundle of named schemas: `m` is {schema-key schema-form}.
   Idempotent per key. Returns the vec of keys. Batch form of `register!` —
   the declarative contribution API addons install their op-schemas through."
  [m]
  (swap! schemas* merge m)
  (vec (keys m)))

(defn deregister-all!
  "Remove named schemas by key `ks` (lifecycle teardown for addon
   contributions). Returns the vec of removed keys."
  [ks]
  (swap! schemas* (fn [s] (apply dissoc s ks)))
  (vec ks))

(defn registered
  "Current {schema-key schema-form} map."
  []
  @schemas*)

(def registry
  "Composite malli registry: malli defaults + the hive mutable registry."
  (mr/composite-registry
   (m/default-schemas)
   (mr/mutable-registry schemas*)))

;; ---------------------------------------------------------------------------
;; Registry-aware wrappers
;; ---------------------------------------------------------------------------

(defn schema
  "Compile `?schema` (a registry key or malli form) against the hive registry."
  [?schema]
  (m/schema ?schema {:registry registry}))

(defn validate
  "True iff `x` conforms to `?schema` under the hive registry. Pure."
  [?schema x]
  (m/validate ?schema x {:registry registry}))

(defn explain
  "malli explanation for `x` against `?schema`, or nil if it conforms."
  [?schema x]
  (m/explain ?schema x {:registry registry}))

;; ---------------------------------------------------------------------------
;; Seeded core-op schemas — Result / error taxonomy bridge (hive-dsl)
;; ---------------------------------------------------------------------------

(register! :hive/error-category :qualified-keyword)

;; :gen/elements makes the strict refinement generatable (a bare :fn predicate is
;; not — mg/generate would throw such-that-failure). Snapshots the registered set at
;; load; validation via `known-error?` stays fully dynamic.
(register! :hive/known-error-category
           [:and {:gen/elements (vec (tax/registered-categories))}
            :hive/error-category
            [:fn {:error/message "unknown error category"} tax/known-error?]])

(register! :hive/ok
           [:map {:closed false} [:ok :any]])

(register! :hive/err
           [:map {:closed false} [:error :hive/error-category]])

;; Exclusive sum (XOR): a Result is EXACTLY one of ok | err. Both-keys
;; ({:ok _ :error _}) dispatches to ::invalid so :hive/result ⟺ spec ::result
;; (making illegal states unrepresentable). :hive/ok mirrors r/ok? (key membership);
;; :hive/err is STRICTER than r/err? (also requires a qualified-keyword :error value).
;; The exclusivity lives in THIS dispatch, not in the :hive/ok / :hive/err members.
(register! :hive/result
           [:multi {:dispatch (fn [x]
                                (cond
                                  (not (map? x))                                ::invalid
                                  (and (contains? x :ok) (contains? x :error))  ::invalid
                                  (contains? x :ok)                             :ok
                                  (contains? x :error)                          :err
                                  :else                                         ::invalid))
                    :error/message "not a Result: exactly one of {:ok ..} | {:error ..}"}
            [:ok  :hive/ok]
            [:err :hive/err]])