(ns hive-spi.editor.registry
  "The injection point for IEditorPort implementations.

   An implementation installs itself under a key; consumers read the active
   port. The key :default names the active port `get-port` returns with no
   argument.

   Only IEditorPort — the substrate — is required to register. The buffer,
   docs and daemon surfaces are optional (ISP): ask `supports?` before
   dispatching a verb from one of them, so a partial adapter degrades into a
   smaller tool surface instead of an AbstractMethodError."
  (:require [hive-spi.editor.ports :as ports]
            [hive-spi.slot :as slot]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defonce ^:private port-slot
  (slot/multi-slot {:validate #(satisfies? ports/IEditorPort %)}))

(slot/defregistry port-slot
  {:noun "editor port" :key-name :port-key
   :hint "Call set-port! or register-port! :default first (the hive.emacs addon does this at init)."
   :register register-port! :unregister unregister-port! :snapshot registered-ports
   :require get-port :set-default set-port! :default-present? port-set?
   :doc {:register "Install IMPL under KEY. Returns IMPL. Throws when IMPL does not satisfy\n   IEditorPort."}})

(defn supports?
  "True iff PORT implements the optional SURFACE, one of :buffer, :docs or
   :daemon. Unknown surfaces are false — a caller naming a surface this SPI
   does not define gets no dispatch, not an exception."
  [port surface]
  (boolean
   (case surface
     :buffer (satisfies? ports/IEditorBufferPort port)
     :docs   (satisfies? ports/IEditorDocsPort port)
     :daemon (satisfies? ports/IEditorDaemonPort port)
     false)))

(defn surfaces
  "The optional surfaces PORT implements, as a set of keywords. The substrate
   (IEditorPort) is implied by registration and never listed."
  [port]
  (into #{} (filter #(supports? port %)) [:buffer :docs :daemon]))

(slot/defregistry port-slot
  {:noun "editor port" :reset reset-registry!})

;; =============================================================================
;; Contracts
;; =============================================================================

(def Surface
  "An optional editor surface `supports?` knows."
  [:enum :buffer :docs :daemon])

(def EditorPort
  "An IEditorPort implementation. Generates a substrate-only port, or one
   that also implements the docs surface, so `surfaces` sees both shapes."
  [:fn {:error/message "must satisfy IEditorPort"
        :gen/schema    :boolean
        :gen/fmap      (fn [docs?]
                         (let [resp {:content [] :isError false}]
                           (if docs?
                             (reify
                               ports/IEditorPort
                               (editor-eval [_ _] resp) (editor-notify [_ _] resp)
                               (editor-status [_ _] resp) (editor-capabilities [_ _] resp)
                               ports/IEditorDocsPort
                               (describe-function [_ _] resp) (describe-variable [_ _] resp)
                               (docs-apropos [_ _] resp) (package-functions [_ _] resp)
                               (package-commentary [_ _] resp) (find-keybindings [_ _] resp)
                               (list-packages [_ _] resp))
                             (reify ports/IEditorPort
                               (editor-eval [_ _] resp) (editor-notify [_ _] resp)
                               (editor-status [_ _] resp) (editor-capabilities [_ _] resp)))))}
   #(satisfies? ports/IEditorPort %)])

(m/=> register-port! [:=> [:cat :any EditorPort] EditorPort])
(m/=> unregister-port! [:=> [:cat :any] :nil])
(m/=> registered-ports [:=> [:cat] [:map-of :any EditorPort]])
(m/=> get-port [:function [:=> [:cat] EditorPort] [:=> [:cat :any] EditorPort]])
(m/=> set-port! [:=> [:cat EditorPort] EditorPort])
(m/=> port-set? [:=> [:cat] :boolean])
;; Total over any value and any surface name: unknown surfaces are false.
(m/=> supports? [:=> [:cat :any :any] :boolean])
(m/=> surfaces [:=> [:cat :any] [:set Surface]])
(m/=> reset-registry! [:=> [:cat] :nil])
