(ns hive-spi.cider.registry
  "The injection point for ICiderPort implementations.

   An implementation installs itself under a key; consumers read the active
   port. The key :default names the active port `get-port` returns with no
   argument."
  (:require [hive-spi.cider.ports :as ports]
            [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private port-slot
  (slot/multi-slot {:validate #(satisfies? ports/ICiderPort %)}))

(defn register-port!
  "Install PORT under KEY. Returns PORT. Throws when PORT does not satisfy
   ICiderPort."
  [key port]
  (slot/reg-put! port-slot key port))

(defn unregister-port!
  "Remove the port under KEY. No-op when absent. Returns nil."
  [key]
  (slot/reg-remove! port-slot key))

(defn registered-ports
  "A read-only {key -> port} snapshot."
  []
  (slot/reg-snapshot port-slot))

(defn get-port
  "The port under KEY, or the :default port when called with no argument.

   Throws ex-info naming the available keys when the port is absent — a
   caller reaching for a port it never registered has a wiring bug."
  ([]
   (or (slot/reg-get port-slot :default)
       (throw (ex-info "No default cider port registered."
                       {:registry-keys (vec (keys (registered-ports)))
                        :hint "Call set-port! or register-port! :default first (the hive.emacs addon does this at init)."}))))
  ([key]
   (or (slot/reg-get port-slot key)
       (throw (ex-info (str "Unknown cider port key: " key)
                       {:port-key key
                        :available (vec (keys (registered-ports)))})))))

(defn set-port!
  "Install PORT as the :default port. Returns PORT."
  [port]
  (register-port! :default port))

(defn port-set?
  "True iff a :default port is installed. Never throws."
  []
  (some? (slot/reg-get port-slot :default)))

(defn reset-registry!
  "Remove every registered port. Returns nil."
  []
  (slot/reg-clear! port-slot))
