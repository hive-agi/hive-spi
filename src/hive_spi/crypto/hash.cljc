(ns hive-spi.crypto.hash
  "The content-digest port.

   Declared with no host dependency, so a namespace that hashes content stays
   loadable on every runtime and the digest primitive arrives through an
   installed IHasher.

   Encoding contract: TEXT crosses this boundary as a string and is digested as
   its UTF-8 bytes; the result is the lowercase hex rendering of those digest
   bytes, two characters per byte. Two implementations of the same algorithm
   are interchangeable and return identical strings.

   Empty-policy: with no hasher installed the port resolves the host default
   (`hive-spi.crypto.hash-jvm`) when that namespace is loadable, and yields nil
   where it is not.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations."
  (:require [hive-spi.slot :as slot]
            [malli.core :as m]
            #?@(:clj [[hive-spi.log.ports :as log]
                      [hive-spi.resolve :as resolve]])))

;; SPDX-License-Identifier: MIT

(defonce ^:private -ihasher-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -ihasher-defined? false true))
  (defprotocol IHasher
    "Cryptographic digests over text."

    (digest-hex [this algorithm text]
      "Lowercase hex digest of TEXT's UTF-8 bytes under ALGORITHM, a keyword
       such as :sha256. Throws :hash/unsupported-algorithm for an algorithm
       this hasher does not provide.")

    (hasher-algorithms [this]
      "The set of algorithm keywords this hasher provides.")))

(defonce ^:private host-default
  (delay
    ;; `requiring-resolve` is a JVM-family primitive: ClojureScript loads its
    ;; namespaces at compile time and has no runtime require, so there is no
    ;; host default to resolve there. An absent hasher yields nil quietly; a
    ;; broken one is reported through the log port.
    #?(:clj (let [{:keys [value failures]} (resolve/first-available
                                            ['hive-spi.crypto.hash-jvm/default-hasher])]
              (doseq [f failures]
                (log/warn "hive-spi.crypto.hash:" (resolve/describe-failure f)))
              value)
       :cljs nil)))

(defonce ^:private hasher-slot
  (slot/single-slot {:validate #(satisfies? IHasher %)
                     :on-empty #(deref host-default)}))

(slot/defslot hasher-slot
  {:noun "hasher" :fallback :host-default
   :set set-hasher! :get get-hasher :clear clear-hasher! :present? hasher-set?
   :doc {:get "The active hasher: the installed one, else the host default, else nil."}})

(defn sha256
  "Lowercase hex SHA-256 of TEXT's UTF-8 bytes, through the active hasher.
   Throws :hash/no-hasher when no hasher is installed and no host default is
   available."
  [text]
  (if-let [hasher (get-hasher)]
    (digest-hex hasher :sha256 text)
    (throw (ex-info "No IHasher installed"
                    {:error :hash/no-hasher :algorithm :sha256}))))

;; =============================================================================
;; Contracts
;; =============================================================================

(def Hasher
  "An IHasher implementation. Generates a hasher answering one generated
   lowercase-hex digest for every input."
  [:fn {:error/message "must satisfy IHasher"
        :gen/schema    [:re #"^[0-9a-f]{64}$"]
        :gen/fmap      (fn [digest]
                         (reify IHasher
                           (digest-hex [_ _ _] digest)
                           (hasher-algorithms [_] #{:sha256})))}
   #(satisfies? IHasher %)])

(m/=> set-hasher! [:=> [:cat Hasher] Hasher])
(m/=> get-hasher [:=> [:cat] [:maybe Hasher]])
(m/=> clear-hasher! [:=> [:cat] :nil])
(m/=> hasher-set? [:=> [:cat] :boolean])
;; The output is whatever the active hasher renders: the port DOCUMENTS
;; lowercase hex, but it passes an installed hasher's answer through
;; unchecked, so the contract states the string the port actually returns.
(m/=> sha256 [:=> [:cat :string] :string])
