(ns hive-spi.editor.elisp
  "Elisp SOURCE construction: Clojure data in, elisp source text out.

   Every fn here is a string builder — no editor connection, no evaluation,
   no state, no lifecycle. Evaluation is a capability (see
   hive-spi.editor.services); syntax is not.

   Usage:
     (require '[hive-spi.editor.elisp :as el])
     (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-status)
     ;; => \"(progn (require 'hive-mcp-magit nil t) ...)\""
  (:require [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Quoting
;; =============================================================================

(defn elisp-quote
  "Quote a Clojure value for elisp.
   Strings get double-quoted, symbols get quoted, numbers pass through,
   booleans become elisp's `t` / `nil`."
  [v]
  (cond
    (nil? v) "nil"
    (true? v) "t"
    (false? v) "nil"
    (string? v) (pr-str v)
    (number? v) (str v)
    (keyword? v) (str ":" (name v))
    (symbol? v) (str "'" v)
    (vector? v) (str "'(" (str/join " " (map elisp-quote v)) ")")
    :else (pr-str v)))

(defn format-args
  "Format arguments for an elisp function call, leading space included.
   Empty args produce the empty string so `(fn)` never becomes `(fn )`."
  [args]
  (if (seq args)
    (str " " (str/join " " (map elisp-quote args)))
    ""))

;; =============================================================================
;; Call construction
;; =============================================================================

(defn require-and-call-json
  "Generate elisp: require feature, call function, JSON-encode result.

   Examples:
     (require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-status)
     (require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-log 10)"
  [feature fn-sym & args]
  (format "(progn
  (require '%s nil t)
  (if (fboundp '%s)
      (json-encode (%s%s))
    (json-encode (list :error \"%s not loaded\"))))"
          feature fn-sym fn-sym (format-args args) feature))

(defn require-and-call-plist-json
  "Generate elisp: require feature, call function with a single plist arg,
   JSON-encode result.

   PARAMS-MAP is a Clojure map converted to an elisp plist; nil values are
   omitted."
  [feature fn-sym params-map]
  (let [plist-str (->> params-map
                       (remove (comp nil? val))
                       (mapcat (fn [[k v]] [(str ":" (name k)) (elisp-quote v)]))
                       (str/join " "))]
    (format "(progn
  (require '%s nil t)
  (if (fboundp '%s)
      (json-encode (%s (list %s)))
    (json-encode (list :error \"%s not loaded\"))))"
            feature fn-sym fn-sym plist-str feature)))

(defn require-and-call-text
  "Generate elisp: require feature, call function, return as text."
  [feature fn-sym & args]
  (format "(progn
  (require '%s nil t)
  (if (fboundp '%s)
      (%s%s)
    \"Error: %s not loaded\"))"
          feature fn-sym fn-sym (format-args args) feature))

(defn require-and-call
  "Generate elisp: require feature, call function, error if not available."
  [feature fn-sym & args]
  (format "(progn
  (require '%s nil t)
  (if (fboundp '%s)
      (%s%s)
    (error \"%s not loaded\")))"
          feature fn-sym fn-sym (format-args args) feature))

(defn fboundp-call-json
  "Generate elisp: check if function exists, call it, JSON-encode.
   Use when the feature is already required elsewhere."
  [fn-sym & args]
  (format "(if (fboundp '%s)
    (json-encode (%s%s))
  (json-encode (list :error \"%s not available\")))"
          fn-sym fn-sym (format-args args) fn-sym))

;; =============================================================================
;; clojure-elisp integration (optional)
;; =============================================================================

(def ^:private clel-available?
  "Whether clojure-elisp is on the classpath. Soft: this namespace declares no
   dependency on it, so absence falls back to pr-str."
  (delay
    (try
      (require 'clojure-elisp.core)
      true
      (catch Exception _ false))))

(defn emit
  "Compile a Clojure form to elisp source via clojure-elisp when present,
   falling back to pr-str."
  [form]
  (if @clel-available?
    ((resolve 'clojure-elisp.core/emit) form)
    (pr-str form)))

(defn emit-forms
  "Compile multiple forms to elisp source, joined by blank lines."
  [forms]
  (str/join "\n\n" (map emit forms)))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn wrap-progn
  "Wrap elisp strings in a progn form."
  [& elisp-strs]
  (str "(progn\n  " (str/join "\n  " elisp-strs) ")"))

(defn format-elisp
  "Template-format elisp source.

   Example:
     (format-elisp \"(goto-line %d)\" 42)"
  [template & args]
  (apply format template args))
