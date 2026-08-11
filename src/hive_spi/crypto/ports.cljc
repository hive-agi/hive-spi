(ns hive-spi.crypto.ports
  "The detached-signature port.

   Declared with no dependencies so a signing implementation never has to
   depend on a crypto library to learn what to implement.

   Encoding contract: key material and signatures cross this boundary as
   base64 TEXT — a private key as PKCS#8, a public key as X.509 — while the
   payload crosses as raw bytes. Both encodings wrap the same 32-byte Ed25519
   material, so two implementations are interchangeable over the same key
   files and produce byte-identical signatures.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded — re-evaluating this namespace will not orphan existing
   implementations."
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -isigner-defined? (atom false))

(when (compare-and-set! -isigner-defined? false true)
  (defprotocol ISigner
    "Detached signatures over bytes."

    (sign-detached [this private-key-b64 payload]
      "Base64 detached signature over PAYLOAD bytes, under the base64 PKCS#8
       private key PRIVATE-KEY-B64.")

    (verify-detached [this public-key-b64 payload signature-b64]
      "True when SIGNATURE-B64 is a valid signature over PAYLOAD bytes for the
       base64 X.509 public key PUBLIC-KEY-B64. Malformed input yields false,
       never a throw.")

    (signer-algorithm [this]
      "Keyword naming the signature algorithm, e.g. :ed25519.")))

(defonce ^:private signer-slot
  (slot/single-slot {:validate #(satisfies? ISigner %)}))

(defn set-signer!
  "Install SIGNER as the active signer. Returns SIGNER."
  [signer]
  (slot/install! signer-slot signer))

(defn get-signer
  "The active signer, or nil when none is installed."
  []
  (slot/current signer-slot))

(defn clear-signer!
  "Remove the installed signer, so consumers fall back to their own default.
   Returns nil."
  []
  (slot/clear! signer-slot))

(defn signer-set?
  "True iff a signer is installed."
  []
  (slot/present? signer-slot))
