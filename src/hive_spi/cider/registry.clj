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

(slot/defregistry port-slot
  {:noun "cider port" :key-name :port-key
   :hint "Call set-port! or register-port! :default first (the hive.emacs addon does this at init)."
   :register register-port! :unregister unregister-port! :snapshot registered-ports
   :require get-port :set-default set-port! :default-present? port-set?
   :reset reset-registry!
   :doc {:register "Install IMPL under KEY. Returns IMPL. Throws when IMPL does not satisfy\n   ICiderPort."}})

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
