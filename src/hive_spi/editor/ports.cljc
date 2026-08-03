(ns hive-spi.editor.ports
  "Editor/vessel SPI — the verb-level tool contract as a pure port.

   A host exposes editor operations to tool callers (evaluate in the editor,
   inspect and drive buffers, query its help system, manage its daemons); an
   implementation (e.g. the hive-emacs IAddon, backed by an Emacs daemon)
   extends these protocols and installs itself through
   hive-spi.editor.registry. Living in this SPI leaf lets the implementation
   compile WITHOUT depending on any host — the editor DIP seam.

   EDITOR, not Emacs: Emacs is one adapter. A vessel that speaks a different
   editor protocol implements the same contract and the host cannot tell.

   Four protocols rather than one, by ISP: an adapter with no daemon model, no
   help system, or no buffer concept implements only what it can honour, and
   consumers ask `satisfies?` before reaching for the optional surface.
   IEditorPort is the substrate every adapter must provide.

   Verb-level contract: each method models one `emacs`-family verb. PARAMS is
   the MCP params map (string keys); the return is the MCP response map
   {:content [...] :isError bool}. Implementations MUST return an isError
   response rather than throw for expected failures (validation, editor down,
   missing buffer).

   Reload-safety: each defprotocol is wrapped in a defonce-guarded
   compare-and-set! so re-evaluating this namespace does not mint a fresh host
   interface class per reload.")

;; SPDX-License-Identifier: MIT

(defonce ^:private -ieditorport-defined? (atom false))

(when (compare-and-set! -ieditorport-defined? false true)
  (defprotocol IEditorPort
    "Editor substrate port — evaluation, messaging and liveness. The minimum
     an adapter must implement to be registered."

    (editor-eval [this params]
      "Evaluate code in the editor's own language. params: code (required),
       timeout_ms.")

    (editor-notify [this params]
      "Show a message to the user. params: message (required), level
       (info|warn|error).")

    (editor-status [this params]
      "Liveness and current focus: whether the editor is reachable, and what
       it currently has open.")

    (editor-capabilities [this params]
      "What this adapter's editor side actually exposes — the bridge/API
       surface that is loaded and callable right now.")))

(defonce ^:private -ieditorbufferport-defined? (atom false))

(when (compare-and-set! -ieditorbufferport-defined? false true)
  (defprotocol IEditorBufferPort
    "Buffer and file surface — optional. An adapter whose editor has no buffer
     model omits this protocol entirely."

    (list-buffers [this params]
      "Every open buffer with its backing file, if any.")

    (current-buffer [this params]
      "The focused buffer: name, file, modified flag, major mode.")

    (buffer-info [this params]
      "Detailed info for :buffer_name.")

    (special-buffers [this params]
      "The editor's non-file buffers (*-buffers): tooling output, REPLs, logs.")

    (switch-buffer [this params]
      "Focus the buffer named :buffer.")

    (find-file [this params]
      "Open :file, creating the buffer if needed.")

    (save-buffers [this params]
      "Save the current buffer, or every modified buffer when :all is true.")

    (goto-line [this params]
      "Move point to :line (1-indexed) in the focused buffer.")

    (insert-text [this params]
      "Insert :text at point in the focused buffer.")

    (recent-files [this params]
      "Recently visited files, most recent first.")

    (project-root [this params]
      "Root directory of the focused buffer's project, or nil when none.")

    (editor-context [this params]
      "The aggregate context snapshot: focus, project, buffers, modes.")))

(defonce ^:private -ieditordocsport-defined? (atom false))

(when (compare-and-set! -ieditordocsport-defined? false true)
  (defprotocol IEditorDocsPort
    "The editor's own help/introspection system — optional."

    (describe-function [this params]
      "Documentation for the function named :function_name.")

    (describe-variable [this params]
      "Documentation for the variable named :variable_name.")

    (docs-apropos [this params]
      "Symbols matching :pattern, optionally filtered by :type.")

    (package-functions [this params]
      "Public functions of :package_or_prefix.")

    (package-commentary [this params]
      "Commentary/README section shipped with :package_name.")

    (find-keybindings [this params]
      "Key bindings matching :pattern.")

    (list-packages [this params]
      "Packages the editor currently has available.")))

(defonce ^:private -ieditordaemonport-defined? (atom false))

(when (compare-and-set! -ieditordaemonport-defined? false true)
  (defprotocol IEditorDaemonPort
    "Editor daemon/instance lifecycle — optional. Adapters that talk to a
     single always-on editor process omit this protocol.

     A 'daemon' is one addressable editor instance; several may coexist and
     the adapter decides which one a tool call lands on."

    (list-daemons [this params]
      "Known daemons with their socket/address and health.")

    (select-daemon [this params]
      "Choose the daemon subsequent calls route to. Returns the selection.")

    (daemon-health [this params]
      "Reachability and load for one daemon, or all when unspecified.")

    (spawn-daemon [this params]
      "Start a new editor daemon. Returns its identity once reachable.")

    (kill-daemon [this params]
      "Stop the named daemon. LOUD-FAILURE CONTRACT: a blank or unknown
       daemon name is an isError response — never a silent success.")))
