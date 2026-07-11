(ns hive-spi.notify
  "INotify — pluggable notification backend SPI.

   Concrete backends (desktop / sound / cli / widget) live in downstream
   implementations and register into a fanout via `notify-fanout!`. A
   workflow event consumer projects :workflow/* events onto the notification
   shape and calls `notify!`.

   Pure protocol contract — no implementations live here."

  ;; Intentionally NO :require — SPI is a pure-contract leaf.
  )

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;;; ===========================================================================
;;; INotify
;;; ---------------------------------------------------------------------------
;;; Notification shape (the `notification` map passed to `notify!`):
;;;   {:event-type keyword — typically a :workflow/* variant kw, but any
;;;                          namespaced kw acceptable (the consumer projects).
;;;    :summary    string  — short one-line title (e.g. window-manager title).
;;;    :body       string  — longer human/LLM-readable body; may be markdown.
;;;    :urgency    #{:low :normal :critical} — freedesktop-style urgency.
;;;    :level      #{:info :warn :error :success} — semantic level (drives
;;;                                                 colour / icon choice).}
;;;
;;; Backends MUST NOT throw from `notify!` — degrade gracefully (the cli
;;; backend in particular is "never-throw"). Failure to deliver is reported
;;; via the return value, not exceptions.
;;; ===========================================================================

(defprotocol INotify
  "Pluggable notification backend.

   Implementations register into a fanout registry via a fanout helper.
   A workflow event consumer projects WorkflowEvent maps onto the
   notification shape documented above and calls `notify!`."

  (notify-id
    [this]
    "Return the keyword identifying this backend.
     Recognised: #{:desktop :sound :cli :widget}. Must be stable and used as
     the dispatch key in the notification registry.")

  (backend-available?
    [this]
    "Predicate: is this backend currently usable on this host?
     Examples: `:desktop` checks `notify-send` on PATH; `:sound` checks an
     audio device; `:widget` checks an Emacs frame is attached.
     Pure side-effect-free probe; never throws.")

  (accepts?
    [this event-type]
    "Predicate: does this backend opt-in to deliver notifications for the
     given `event-type` keyword (e.g. :workflow/failed)?
     Used by `notify-fanout!` to skip backends that filter the event out.
     Pure; never throws.")

  (notify!
    [this notification]
    "Deliver `notification` (shape documented in the ns docstring) through
     this backend.
     Returns: {:delivered? boolean :backend keyword :detail any-map}.
     MUST NOT throw — failures are reported via :delivered? false plus
     :detail diagnostic data. Backends must be idempotent across rapid
     repeated calls (callers may coalesce upstream, but backends must not
     blow up on duplicates)."))
