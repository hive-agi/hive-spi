(ns hive-spi.projection.stub
  "Reference FloorProjections the conformance suite is proven against.

   `identity-projection` is what a host does with an empty slot, made into an
   implementation so the fallback is held to the same laws. `truncating`
   withholds everything after the first blank line behind a pointer, a crude
   but lawful projection that shows the suite is not vacuous about :C."
  (:require [clojure.string :as str]
            [hive-spi.projection.ports :as p]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(defrecord IdentityProjection []
  p/FloorProjection
  (project-floor [_ entry] entry))

(defn identity-projection
  "The projection that sends every floor entry whole."
  []
  (->IdentityProjection))

(defrecord TruncatingProjection []
  p/FloorProjection
  (project-floor [_ entry]
    (let [c (str (:C entry))
          i (str/index-of c "\n\n")]
      (if (or (:projected entry) (nil? i))
        entry
        (let [out (str (subs c 0 i) "\n[withheld; pull with: memory get " (:id entry) "]")]
          (if (< (count out) (count c))
            (assoc entry :C out :projected true)
            entry))))))

(defn truncating
  "A projection keeping only the opening run of each entry."
  []
  (->TruncatingProjection))

(defn harness
  "A fresh conformance harness over the identity projection."
  []
  {:projection (identity-projection)})

(defn truncating-harness
  "A fresh conformance harness over the truncating projection."
  []
  {:projection (truncating)})

(m/=> identity-projection [:=> [:cat] p/Projection])
(m/=> truncating [:=> [:cat] p/Projection])
(m/=> harness [:=> [:cat] [:map [:projection p/Projection]]])
(m/=> truncating-harness [:=> [:cat] [:map [:projection p/Projection]]])
