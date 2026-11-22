(ns hive-spi.cider.registry
  "The injection point for ICiderPort implementations.

   An implementation installs itself under a key; consumers read the active
   port. The key :default names the active port `get-port` returns with no
   argument."
  (:require [hive-spi.cider.ports :as ports]
            [hive-spi.slot :as slot]
            [malli.core :as m]))

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

;; =============================================================================
;; Contracts
;; =============================================================================

(def CiderPort
  "An ICiderPort implementation. Generates a port that answers every verb
   with one generated text response."
  [:fn {:error/message "must satisfy ICiderPort"
        :gen/schema    [:string {:max 8}]
        :gen/fmap      (fn [text]
                         (let [resp {:content [{:type "text" :text text}] :isError false}]
                           (reify ports/ICiderPort
                             (cider-eval [_ _] resp) (cider-doc [_ _] resp)
                             (cider-info [_ _] resp) (cider-complete [_ _] resp)
                             (cider-apropos [_ _] resp) (cider-status [_ _] resp)
                             (spawn-session [_ _] resp) (connect-session [_ _] resp)
                             (list-sessions [_ _] resp) (kill-session [_ _] resp)
                             (kill-all-sessions [_ _] resp) (ensure-connected [_ _] text))))}
   #(satisfies? ports/ICiderPort %)])

(m/=> register-port! [:=> [:cat :any CiderPort] CiderPort])
(m/=> unregister-port! [:=> [:cat :any] :nil])
(m/=> registered-ports [:=> [:cat] [:map-of :any CiderPort]])
(m/=> get-port [:function [:=> [:cat] CiderPort] [:=> [:cat :any] CiderPort]])
(m/=> set-port! [:=> [:cat CiderPort] CiderPort])
(m/=> port-set? [:=> [:cat] :boolean])
(m/=> reset-registry! [:=> [:cat] :nil])
