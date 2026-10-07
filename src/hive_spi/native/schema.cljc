(ns hive-spi.native.schema
  "Portable malli shapes for hive-cabi/v1 envelopes, catalogs and library declarations."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(def ErrorType
  "Host-side native failure categories."
  [:enum :native/unavailable :native/timeout :native/malformed :native/failed])

(def Envelope
  "Decoded hive-cabi/v1 result; host errors may add :error/type."
  [:or
   [:map [:ok [:= true]] [:value :any]]
   [:map [:ok [:= false]] [:error :string] [:error/type {:optional true} ErrorType]]])

(def OpEntry
  "One operation advertised by the native `ops` catalog."
  [:map [:name :string] [:doc :string] [:pure :boolean]])

(def OpCatalog
  "An ordered catalog of operations, returned as the value of `ops`."
  [:vector OpEntry])

(def Transport
  "Supported native host transport identifiers."
  [:enum :jvm/ffm :node/ffi :node/wasm :cljrs/dlopen :cljw/wasi :subprocess])

(def ^:private artifact
  [:map [:shared {:optional true} :string]
   [:js {:optional true} :string]
   [:wasi {:optional true} :string]])

(def LibrarySpec
  "Logical library and its packaged artifacts. Paths are absolute or classpath resources;
   shared artifacts conventionally live at native/<os>-<arch>/lib<name>.so,
   .dylib or <name>.dll; wasm artifacts live at native/wasm/."
  [:map
   [:native/library :string]
   [:native/abi [:= :hive-cabi/v1]]
   [:native/artifacts
    [:map
     [:linux-x86_64 {:optional true} artifact]
     [:linux-aarch64 {:optional true} artifact]
     [:darwin-aarch64 {:optional true} artifact]
     [:darwin-x86_64 {:optional true} artifact]
     [:windows-x86_64 {:optional true} artifact]
     [:wasm {:optional true} artifact]]]
   [:native/timeout-ms {:optional true} [:int {:min 1}]]
   [:native/transports {:optional true} [:vector Transport]]])

(defn valid?
  "True when a value conforms to a native contract schema."
  [schema value]
  (m/validate schema value))

(defn ->library-spec
  "Validate and return a library declaration or throw with malli explanation."
  [spec]
  (if (valid? LibrarySpec spec)
    spec
    (throw (ex-info "Invalid native library spec"
                    {:error :native/invalid-library-spec
                     :explanation (m/explain LibrarySpec spec)}))))

(defn ->envelope
  "Validate and return a decoded native envelope or throw with malli explanation."
  [envelope]
  (if (valid? Envelope envelope)
    envelope
    (throw (ex-info "Invalid native envelope"
                    {:error :native/malformed
                     :explanation (m/explain Envelope envelope)}))))
