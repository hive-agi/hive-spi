(ns hive-spi.cider.ports
  "CIDER/REPL SPI — the verb-level tool contract as a pure port.

   A host exposes CIDER operations to tool callers (eval, introspection,
   session lifecycle); an implementation (e.g. the hive-emacs IAddon, backed
   by an Emacs daemon) extends this protocol and installs itself through
   hive-spi.cider.registry. Living in this SPI leaf lets the implementation
   compile WITHOUT depending on any host — the cider DIP seam.

   Verb-level contract: each method models one `code :cider` verb. PARAMS is
   the MCP params map (string keys); the return is the MCP response map
   {:content [...] :isError bool}. Implementations MUST return an isError
   response rather than throw for expected failures (validation, missing
   session, backend down).

   Reload-safety: the defprotocol is wrapped in a defonce-guarded
   compare-and-set! so re-evaluating this namespace does not mint a fresh
   host interface class per reload.")

;; SPDX-License-Identifier: MIT

(defonce ^:private -iciderport-defined? (atom false))

(when (compare-and-set! -iciderport-defined? false true)
  (defprotocol ICiderPort
    "CIDER/REPL tool-surface port — one method per `code :cider` verb."

    (cider-eval [this params]
      "Evaluate Clojure code. params: code (required), mode (silent|explicit),
       timeout (s), session_name (route to named session), directory /
       project_dir (route to that project's session, auto-spawning).")

    (cider-doc [this params]
      "Docstring for :symbol, optionally inside :session_name's REPL.")

    (cider-info [this params]
      "Full semantic info for :symbol, optionally inside :session_name's REPL.")

    (cider-complete [this params]
      "Completions for :prefix, optionally inside :session_name's REPL.")

    (cider-apropos [this params]
      "Symbols matching :pattern; :search_docs true also searches docstrings.")

    (cider-status [this params]
      "Connection status of the current/default CIDER connection.")

    (spawn-session [this params]
      "Spawn a named nREPL session. params: name (required, non-blank),
       project_dir, port, repl_type, agent_id, extra_args, aliases,
       extra_deps, middleware.")

    (connect-session [this params]
      "Connect to an existing nREPL server as a named session.
       params: name and port (both required), host, repl_type, agent_id,
       project_dir.")

    (list-sessions [this params]
      "List all registered sessions with status and ports.")

    (kill-session [this params]
      "Kill the session named :session_name. LOUD-FAILURE CONTRACT: a blank
       or unknown session_name is an isError response — never a silent
       success, never \"Session 'null' killed\".")

    (kill-all-sessions [this params]
      "Kill every registered session.")

    (ensure-connected [this project-dir]
      "Auto-connect: the name of a connected session for PROJECT-DIR,
       spawning one when none is connected. Nil PROJECT-DIR uses the
       implementation's default project. Returns the session name on
       success; signals/returns an error on failure.")))
