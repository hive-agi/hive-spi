(ns ^:typed.clojure hive-spi.typed-demo
  "check: clojure -M:typed -e \"(require 'typed.clojure)(typed.clojure/check-ns-clj 'hive-spi.typed-demo)\""
  (:require [typed.clojure :as t]
            [hive-spi.schema.registry]
            [hive-spi.schema.typed-ann :as ta]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(ta/defalias-schema HiveResult :hive/result)
(ta/defalias-schema ErrorCategory :hive/error-category)

(ta/ann-op ok-handler [:map [:qn {:optional true} :string]
                            [:function {:optional true} :string]])
(defn ok-handler [m] {:ok m})

(t/ann make-err [ErrorCategory :-> HiveResult])
(defn make-err [category] {:error category})
