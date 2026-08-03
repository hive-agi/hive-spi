(ns hive-spi.editor.elisp-test
  "These builders produce source that a live Emacs will EVALUATE, so a quoting
   slip is a code-injection-shaped bug, not a formatting nit. The tests pin
   quoting per type and the not-loaded fallback of every call form."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-spi.editor.elisp :as el]))

;; SPDX-License-Identifier: MIT

(deftest quoting-per-type
  (testing "nil and booleans use elisp's own vocabulary, not Clojure's"
    (is (= "nil" (el/elisp-quote nil)))
    (is (= "t" (el/elisp-quote true)))
    (is (= "nil" (el/elisp-quote false))))
  (testing "symbols are quoted so elisp does not evaluate them as variables"
    (is (= "'my-symbol" (el/elisp-quote 'my-symbol))))
  (testing "strings keep their quotes and escape embedded ones"
    (is (= "\"hello\"" (el/elisp-quote "hello")))
    (is (= "\"hello \\\"world\\\"\"" (el/elisp-quote "hello \"world\""))))
  (testing "numbers and keywords pass through in elisp form"
    (is (= "42" (el/elisp-quote 42)))
    (is (= ":kw" (el/elisp-quote :kw))))
  (testing "vectors become quoted elisp lists, recursively"
    (is (= "'(1 \"two\" 'three)" (el/elisp-quote [1 "two" 'three])))))

(deftest format-args-never-emits-a-stray-space
  (is (= "" (el/format-args nil)))
  (is (= "" (el/format-args [])))
  (is (= " 1 \"a\"" (el/format-args [1 "a"]))))

(deftest require-and-call-json-shape
  (let [out (el/require-and-call-json 'hive-mcp-magit 'hive-mcp-magit-api-log 10)]
    (is (str/includes? out "(require 'hive-mcp-magit nil t)"))
    (is (str/includes? out "(fboundp 'hive-mcp-magit-api-log)"))
    (is (str/includes? out "(json-encode (hive-mcp-magit-api-log 10))"))
    (is (str/includes? out "hive-mcp-magit not loaded")
        "a missing feature must degrade to a JSON error, not an elisp error")))

(deftest require-and-call-json-with-no-args
  (is (str/includes? (el/require-and-call-json 'feat 'some-fn)
                     "(json-encode (some-fn))")))

(deftest plist-json-omits-nils-and-quotes-values
  (let [out (el/require-and-call-plist-json
             'hive-mcp-cider 'spawn {:name "foo" :repl-type 'clj :port nil})]
    (is (str/includes? out ":name \"foo\""))
    (is (str/includes? out ":repl-type 'clj"))
    (is (not (str/includes? out ":port"))
        "a nil param must be absent, not passed as elisp nil")))

(deftest text-and-error-variants
  (let [text (el/require-and-call-text 'feat 'some-fn 'staged)
        strict (el/require-and-call 'feat 'some-fn)]
    (is (str/includes? text "(some-fn 'staged)"))
    (is (str/includes? text "\"Error: feat not loaded\""))
    (is (str/includes? strict "(error \"feat not loaded\")"))))

(deftest fboundp-call-json-skips-the-require
  (let [out (el/fboundp-call-json 'hive-mcp-api-status)]
    (is (not (str/includes? out "require")))
    (is (str/includes? out "(fboundp 'hive-mcp-api-status)"))
    (is (str/includes? out "hive-mcp-api-status not available"))))

(deftest wrap-progn-and-format-elisp
  (is (= "(progn\n  (a)\n  (b))" (el/wrap-progn "(a)" "(b)")))
  (is (= "(goto-line 42)" (el/format-elisp "(goto-line %d)" 42))))

(deftest emit-falls-back-without-clojure-elisp
  (is (string? (el/emit '(buffer-name))))
  (is (string? (el/emit-forms ['(a) '(b)]))))
