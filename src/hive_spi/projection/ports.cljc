(ns hive-spi.projection.ports
  "The floor-lane projection port: how an entry that may NOT be summarised is
   displayed on the catchup drain.

   A host's memory drain sends entries in two lanes. A pool entry may become a
   pointer the caller pulls on demand; that is host policy and stays in the
   host. A FLOOR entry (an axiom, a pinned entry) is inviolable: it is never a
   pointer, and its binding text is delivered byte-identical. What a floor
   projection may do is withhold the parts of the entry that do not bind
   (rationale, enforcement source) and leave a pointer line saying how to pull
   them. Which parts those are is a judgement, and the judgement is an
   implementation, so it lives behind this port and not in the host.

   The host reads `get-projection` on every drain and treats an empty slot as
   IDENTITY: the floor entry is sent whole. That degrades toward sending more,
   never toward withholding something binding.

   The laws an implementation must keep are checked by
   `hive-spi.projection.conformance`:

     - the entry stays addressable: :id and :T are unchanged;
     - a floor entry is never turned into a pointer (no :ref is introduced);
     - the projection never grows :C;
     - the projection is idempotent, because the drain writes its projected
       buffer back and projects it again on every later drain.

   Reload-safety: `defprotocol` is not idempotent, so the declaration is
   guarded; re-evaluating this namespace will not orphan existing
   implementations."
  (:require [hive-spi.slot :as slot]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT

(defonce ^:private -floorprojection-defined? (atom false))

(when #?(:cljr true :default (compare-and-set! -floorprojection-defined? false true))
  (defprotocol FloorProjection
    "Displays a floor-lane entry without losing a binding word."

    (project-floor [this entry]
      "ENTRY as the floor lane displays it. Returns a map with the same :id and
       :T, no :ref it did not already carry, a :C no longer than ENTRY's, and
       answers itself when projected again.")))

(defonce ^:private projection-slot
  (slot/single-slot {:validate #(satisfies? FloorProjection %)}))

(defn set-projection!
  "Install PROJECTION as the active floor projection. Returns PROJECTION."
  [projection]
  (slot/install! projection-slot projection))

(defn get-projection
  "The active floor projection, or nil when none is installed."
  []
  (slot/current projection-slot))

(defn projection-set?
  "True iff a floor projection is installed."
  []
  (slot/present? projection-slot))

(defn clear-projection!
  "Remove the active floor projection. Returns nil."
  []
  (slot/clear! projection-slot))

(defn project-or-identity
  "ENTRY through the installed floor projection, or ENTRY itself when none is
   installed. The host's one call site; the fallback is the reason a host may
   run with no implementation at all."
  [entry]
  (if-let [p (get-projection)]
    (project-floor p entry)
    entry))

;; =============================================================================
;; Contracts
;; =============================================================================

(def Entry
  "A buffered entry as a floor projection sees it; open beyond these keys."
  [:map
   [:id {:optional true} :any]
   [:T {:optional true} :any]
   [:C {:optional true} :any]])

(def Projection
  "A FloorProjection implementation. Generates the identity projection."
  [:fn {:error/message "must satisfy FloorProjection"
        :gen/schema    [:= :identity]
        :gen/fmap      (fn [_] (reify FloorProjection
                                 (project-floor [_ entry] entry)))}
   #(satisfies? FloorProjection %)])

(m/=> set-projection! [:=> [:cat Projection] Projection])
(tc/ann-op set-projection!)
(m/=> get-projection [:=> [:cat] [:maybe Projection]])
(tc/ann-op get-projection)
(m/=> projection-set? [:=> [:cat] :boolean])
(tc/ann-op projection-set?)
(m/=> clear-projection! [:=> [:cat] :nil])
(tc/ann-op clear-projection!)
(m/=> project-or-identity [:=> [:cat Entry] :map])
(tc/ann-op project-or-identity)
