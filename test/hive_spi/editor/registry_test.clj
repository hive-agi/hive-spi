(ns hive-spi.editor.registry-test
  "The editor registry is the runtime seam a host uses to inject the
   IEditorPort implementation. Two failures matter: accepting an impl that is
   not a port at all, and mis-reporting the OPTIONAL surfaces — a host that
   believes a substrate-only adapter can drive buffers dispatches straight
   into an AbstractMethodError."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-spi.editor.ports :as ports]
            [hive-spi.editor.registry :as reg]))

;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Doubles
;; =============================================================================

(defn- ok [calls tag params]
  (swap! calls conj [tag params])
  {:content [{:type "text" :text "ok"}] :isError false})

(defrecord SubstrateOnlyPort [calls]
  ports/IEditorPort
  (editor-eval [_ params] (ok calls :eval params))
  (editor-notify [_ params] (ok calls :notify params))
  (editor-status [_ params] (ok calls :status params))
  (editor-capabilities [_ params] (ok calls :capabilities params)))

(defrecord FullEditorPort [calls]
  ports/IEditorPort
  (editor-eval [_ params] (ok calls :eval params))
  (editor-notify [_ params] (ok calls :notify params))
  (editor-status [_ params] (ok calls :status params))
  (editor-capabilities [_ params] (ok calls :capabilities params))

  ports/IEditorBufferPort
  (list-buffers [_ params] (ok calls :list-buffers params))
  (current-buffer [_ params] (ok calls :current-buffer params))
  (buffer-info [_ params] (ok calls :buffer-info params))
  (special-buffers [_ params] (ok calls :special-buffers params))
  (switch-buffer [_ params] (ok calls :switch-buffer params))
  (find-file [_ params] (ok calls :find-file params))
  (save-buffers [_ params] (ok calls :save-buffers params))
  (goto-line [_ params] (ok calls :goto-line params))
  (insert-text [_ params] (ok calls :insert-text params))
  (recent-files [_ params] (ok calls :recent-files params))
  (project-root [_ params] (ok calls :project-root params))
  (editor-context [_ params] (ok calls :editor-context params))

  ports/IEditorDocsPort
  (describe-function [_ params] (ok calls :describe-function params))
  (describe-variable [_ params] (ok calls :describe-variable params))
  (docs-apropos [_ params] (ok calls :docs-apropos params))
  (package-functions [_ params] (ok calls :package-functions params))
  (package-commentary [_ params] (ok calls :package-commentary params))
  (find-keybindings [_ params] (ok calls :find-keybindings params))
  (list-packages [_ params] (ok calls :list-packages params))

  ports/IEditorDaemonPort
  (list-daemons [_ params] (ok calls :list-daemons params))
  (select-daemon [_ params] (ok calls :select-daemon params))
  (daemon-health [_ params] (ok calls :daemon-health params))
  (spawn-daemon [_ params] (ok calls :spawn-daemon params))
  (kill-daemon [_ params] (ok calls :kill-daemon params)))

(defn- substrate-port [] (->SubstrateOnlyPort (atom [])))
(defn- full-port [] (->FullEditorPort (atom [])))

(use-fixtures :each (fn [f] (reg/reset-registry!) (f) (reg/reset-registry!)))

;; =============================================================================
;; Registry behavior
;; =============================================================================

(deftest register-and-get-default
  (let [p (full-port)]
    (reg/set-port! p)
    (is (reg/port-set?))
    (is (identical? p (reg/get-port)))
    (is (identical? p (reg/get-port :default)))))

(deftest get-port-throws-when-absent
  (is (not (reg/port-set?)))
  (try
    (reg/get-port)
    (is false "should have thrown")
    (catch clojure.lang.ExceptionInfo e
      (is (re-find #"No default editor port" (ex-message e))))))

(deftest get-port-unknown-key-throws-with-available
  (reg/register-port! :em (full-port))
  (try
    (reg/get-port :nope)
    (is false "should have thrown")
    (catch clojure.lang.ExceptionInfo e
      (is (= :nope (:port-key (ex-data e))))
      (is (= [:em] (:available (ex-data e)))))))

(deftest register-validates-substrate-protocol
  (try
    (reg/register-port! :bad {:not "a port"})
    (is false "should have thrown")
    (catch AssertionError _
      (is true))))

(deftest unregister-removes
  (let [p (full-port)]
    (reg/set-port! p)
    (reg/unregister-port! :default)
    (is (not (reg/port-set?)))
    (is (empty? (reg/registered-ports)))))

;; =============================================================================
;; Optional surfaces (ISP)
;; =============================================================================

(deftest substrate-only-adapter-registers-and-reports-no-surfaces
  (let [p (substrate-port)]
    (reg/set-port! p)
    (is (reg/port-set?) "substrate alone is enough to register")
    (is (= #{} (reg/surfaces p)))
    (is (not (reg/supports? p :buffer)))
    (is (not (reg/supports? p :docs)))
    (is (not (reg/supports? p :daemon)))))

(deftest full-adapter-reports-every-surface
  (let [p (full-port)]
    (is (= #{:buffer :docs :daemon} (reg/surfaces p)))
    (is (reg/supports? p :buffer))
    (is (reg/supports? p :docs))
    (is (reg/supports? p :daemon))))

(deftest unknown-surface-is-false-not-a-throw
  (is (false? (reg/supports? (full-port) :telepathy))))

;; =============================================================================
;; Dispatch
;; =============================================================================

(deftest methods-dispatch-through-registry
  (let [p (full-port)]
    (reg/set-port! p)
    (ports/editor-eval (reg/get-port) {"code" "(message \"hi\")"})
    (ports/switch-buffer (reg/get-port) {"buffer" "*scratch*"})
    (ports/kill-daemon (reg/get-port) {"name" "dev"})
    (is (= [[:eval {"code" "(message \"hi\")"}]
            [:switch-buffer {"buffer" "*scratch*"}]
            [:kill-daemon {"name" "dev"}]]
           @(:calls p)))))
