(ns hive-spi.swarm.ports.messaging
  "MESSAGING ports, the seam between the swarm subsystem and the host's
   messaging fabric. PLATFORM-AGNOSTIC by contract: this ns knows nothing
   about any particular agent platform, client or push transport. A
   platform is ONE registered sink behind IInboxSink; conversation reads
   are IConversationStore.

   The message envelope is plain data (no schema lib needed):

     {:inbox/to         \"agent-or-coordinator-id\"  ; required, string
      :inbox/from       \"sender-id\"                 ; required, string
      :inbox/kind       :shout                        ; :shout | :ask | :directive | :result | other kw
      :inbox/body       \"text\"                      ; required, string
      :inbox/context-id \"opaque\"                     ; optional: A2A contextId for threading
      :inbox/attrs      {:severity \"high\"}}          ; optional, keyword -> any; sinks project it

   Protocols by ISP:

     IEventBus            local typed pub/sub routing (core.async backed;
                          the REMOTE backbone stays on
                          hive-contracts.event-backbone/IEventBackbone)
     IFrontendPush        push events to connected UI clients + liveness
     IAudience            coordinator-lane reader identity (pure)
     IInboxSink           one platform's way to REACH recipients: each
                          registered sink answers sink-id, accepts? and
                          deliver!; the port routes, sinks own any
                          transport vocabulary
     IConversationStore   the READ side, separate from delivery:
                          register-message-source!, fetch-conversation,
                          new-conversation-id
     IBroadcastGovernance broadcast admission policy + volume ledger
     IContextStore        large-payload elision + reference store
     IDeliveryFanout      fanout across registered delivery backends

   Sinks live in ONE multi-slot registry keyed by sink-id; deliver!
   routes to the first sink that accepts? the recipient, trying sinks in
   ascending (:inbox/priority (meta sink) 100) order, ties by sink-id,
   and degrades to {:delivered? false :sink nil :reason :no-sink}.

   Contract, same as every hive-spi port: no method throws; absence is a
   value (nil / false / 0 / empty seq) that the caller's existing degraded
   branch already handles; pure data in and out, no host types leak. Types
   crossing the seam are plain maps / keywords / strings / booleans / longs,
   plus one external value: subscribe! returns a clojure.core.async channel,
   passed through opaquely, so this ns never requires it.

   Reload-safety: each defprotocol is wrapped in a defonce-guarded
   compare-and-set! (house style, see hive-spi.time.ports); re-evaluating
   this ns must not mint fresh protocol classes and orphan installed
   adapters. IInboxSink's delivery method is -deliver!, and the public
   deliver! below is the router over the registered sinks."
  (:require [hive-spi.slot :as slot]))

;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; IEventBus: local typed event routing
;;; ============================================================================

(defonce ^:private -ieventbus-defined? (atom false))

(when (compare-and-set! -ieventbus-defined? false true)
  (defprotocol IEventBus
    "Local typed event routing: the seam over the host's core.async
     publish/pub/sub bus. Events are plain maps routed on their :type
     keyword; handlers run on the caller's own go-loops. REMOTE subject
     pub/sub is a different abstraction and stays on
     hive-contracts.event-backbone/IEventBackbone."

    (publish! [this event]
      "Publish EVENT (a map with a :type key) to the local bus.
       Fire-and-forget: returns nil whether or not anyone is subscribed.")

    (subscribe! [this event-type]
      "Subscribe to events whose :type equals EVENT-TYPE. Returns a
       core.async channel of matching events, or NIL when no bus exists :
       callers must treat nil as 'bus absent' and skip their go-loop.")

    (unsubscribe! [this event-type ch]
      "Unsubscribe CH from EVENT-TYPE and close it. Safe when already
       unsubscribed or when nothing is installed.")))

;;; ============================================================================
;;; IFrontendPush: UI clients + liveness
;;; ============================================================================

(defonce ^:private -ifrontendpush-defined? (atom false))

(when (compare-and-set! -ifrontendpush-defined? false true)
  (defprotocol IFrontendPush
    "Push to every connected UI client (Emacs over the channel socket,
     browsers over WebSocket) and report that surface's liveness. Failure
     to deliver is never an error: UI clients are spectators, not
     participants."

    (broadcast! [this msg]
      "Send MSG (a JSON-serialisable map) to all connected channel clients.
       No-op when none are connected. Returns nil.")

    (emit! [this event-type data]
      "Emit a typed event to all connected clients: the event-type name and
       an epoch-millis timestamp ride the envelope, DATA keys are
       stringified on the wire. One verb, possibly two transports: the
       adapter's choice. Returns nil.")

    (frontend-status [this]
      "Liveness of the push surface as data:
       {:channel-connected? bool :ws-connected? bool :ws-clients long}.")))

;;; ============================================================================
;;; IAudience: coordinator-lane reader identity (pure)
;;; ============================================================================

(defonce ^:private -iaudience-defined? (atom false))

(when (compare-and-set! -iaudience-defined? false true)
  (defprotocol IAudience
    "Coordinator-lane reader identity. A coordinator lane is ONE operator
     window: the host transport spells it `coordinator:<session>`
     (optionally `-<project>`); a bare `coordinator` prefix matches every
     lane."

    (coordinator-reader? [this reader-id]
      "True when READER-ID names a coordinator lane (prefix test; nil counts
       as a coordinator in a real adapter).")

    (coordinator-session [this id]
      "The session token of a coordinator-lane id (`coordinator:<session>`,
       optional `-<project>` suffix ends at the first dash), or nil when the
       id names the lane without a session: or when no adapter answers.")))

;;; ============================================================================
;;; IInboxSink: one platform's way to reach recipients (delivery)
;;; ============================================================================

(defonce ^:private -iinboxsink-defined? (atom false))

(when (compare-and-set! -iinboxsink-defined? false true)
  (defprotocol IInboxSink
    "One platform's way to reach message recipients. A sink registers
     itself under its sink-id and competes for envelopes by recipient
     match and priority; WHICH transport a sink represents is the
     adapter's business and invisible to callers. Sinks project their own
     transport's limits: the port's vocabulary stops at the envelope."

    (sink-id [this]
      "Keyword naming the sink (e.g. :piggyback). The sink registry's key.")

    (accepts? [this recipient]
      "True when this sink can reach RECIPIENT (the envelope's :inbox/to
       id). Pure: safe to call on every registered sink while routing.")

    (-deliver! [this envelope]
      "Deliver ENVELOPE (the plain-data map documented in the ns
       docstring). Returns {:delivered? bool :sink <sink-id> :receipt any}.
       Never throws: a failed delivery is logged by the sink and returned
       as {:delivered? false ...}.")))

;;; ============================================================================
;;; IConversationStore: the READ side, separate from delivery
;;; ============================================================================

(defonce ^:private -iconversationstore-defined? (atom false))

(when (compare-and-set! -iconversationstore-defined? false true)
  (defprotocol IConversationStore
    "The READ side of a conversation, deliberately separate from delivery.
     Reads never deliver and delivery never reads."

    (register-message-source! [this source-fn]
      "Register a zero-arg fn returning the message rows a digest transport
       is built from. A push-only adapter may ignore it.")

    (fetch-conversation [this context-id opts]
      "Every message of the A2A conversation CONTEXT-ID, oldest first,
       WITHOUT moving any read cursor, the redemption of the contextId a
       peer-traffic digest names. OPTS is {:limit n} (default 100). Rows
       carry {:a agent-id :e event-type :m message :ts timestamp}, with :to
       :t :ref when present. Returns [] on miss, never nil, so callers seq
       it without a nil guard.")

    (new-conversation-id [this]
      "A fresh opaque A2A contextId (shape is the adapter's). Nil when no
       adapter is installed, which callers already treat as 'no
       conversation threading'.")))

;;; ============================================================================
;;; Sink registry: ONE multi-slot keyed by sink-id
;;; ============================================================================

(defonce ^:private sink-slot
  (slot/multi-slot {:validate #(satisfies? IInboxSink %)}))

(defn register-sink!
  "Register SINK under its sink-id (replacing any sink of the same id).
   Returns SINK."
  [sink]
  (slot/reg-put! sink-slot (sink-id sink) sink))

(defn unregister-sink!
  "Remove the sink registered under ID. No-op when absent. Returns nil."
  [id]
  (slot/reg-remove! sink-slot id))

(defn sinks
  "Every registered sink, in no defined order. deliver! sorts for you."
  []
  (vals (slot/reg-snapshot sink-slot)))

;;; ============================================================================
;;; Routing: pure over the sink registry, never throws
;;; ============================================================================

(defn- envelope-valid?
  "Minimal envelope validation: :inbox/to, :inbox/from and :inbox/body
   are present, non-empty strings. Never throws."
  [{:inbox/keys [to from body]}]
  (and (string? to) (seq to)
       (string? from) (seq from)
       (string? body) (seq body)))

(defn- sinks-in-delivery-order
  "Every registered sink sorted by ascending (:inbox/priority (meta sink)
   100), ties broken by sink-id."
  []
  (sort-by (juxt #(or (:inbox/priority (meta %)) 100) #(sink-id %))
           (sinks)))

(defn deliver!
  "Route ENVELOPE to the registered sinks that accepts? its :inbox/to,
   tried in ascending (:inbox/priority (meta sink) 100) order, ties by
   sink-id. The first result with :delivered? true wins; a sink that
   accepts but fails (or throws) falls through to the next one, so a
   specific sink can sit in front of a universal fallback. Returns that
   result, else the last failure, else {:delivered? false :sink nil
   :reason :no-sink}. An invalid envelope returns {:delivered? false
   :sink nil :reason :invalid-envelope}. Never throws."
  [envelope]
  (if-not (envelope-valid? envelope)
    {:delivered? false :sink nil :reason :invalid-envelope}
    (loop [candidates (filter #(accepts? % (:inbox/to envelope))
                              (sinks-in-delivery-order))
           last-failure nil]
      (if-let [sink (first candidates)]
        (let [result (try (-deliver! sink envelope)
                          (catch Exception _
                            {:delivered? false :sink (sink-id sink) :reason :sink-threw}))]
          (if (:delivered? result)
            result
            (recur (rest candidates) result)))
        (or last-failure {:delivered? false :sink nil :reason :no-sink})))))

;;; ============================================================================
;;; IBroadcastGovernance: admission policy + volume ledger
;;; ============================================================================

(defonce ^:private -ibroadcastgovernance-defined? (atom false))

(when (compare-and-set! -ibroadcastgovernance-defined? false true)
  (defprotocol IBroadcastGovernance
    "Broadcast admission: the volume policy plus the ledger it reads. One
     protocol because the swarm's only caller uses them as one gesture:
     consult the ledger, apply the policy, charge the admitted."

    (apply-policy [this msg opts]
      "Rewrite MSG's routing fields to what the policy decided. OPTS is nil
       or {:recent-broadcasts n}. An admitted broadcast keeps :broadcast?
       and gains the normalised :broadcast-reason; a refused one LOSES
       :broadcast? and gains :broadcast-refused: the message itself always
       survives (refusal downgrades, never drops).")

    (spent-recently [this project-id]
      [this project-id now]
      "How many broadcasts PROJECT-ID has been admitted inside the live
       volume window (epoch-millis NOW, default: adapter's clock). Feeds
       the next apply-policy opts.")

    (record-broadcast! [this project-id]
      [this project-id now]
      "Charge ONE ADMITTED broadcast to PROJECT-ID's budget (refused ones
       cost nothing and are not recorded). Returns nil.")))

;;; ============================================================================
;;; IContextStore: large-payload elision + reference store
;;; ============================================================================

(defonce ^:private -icontextstore-defined? (atom false))

(when (compare-and-set! -icontextstore-defined? false true)
  (defprotocol IContextStore
    "Large-payload handling. Shouts larger than the inline cap travel as a
     preview plus a ref; the body waits in the store."

    (elide! [this s put-fn]
            [this s cap put-fn]
      "Bound string S to CAP characters (adapter default when omitted).
       When S fits, {:text s}. When it does not, PUT-FN is called with the
       FULL string and must return a fetchable id: the result is
       {:text \"<preview>… [ref:<id>]\" :ref id}. A PUT-FN that throws or
       returns nil degrades to a plain truncation: a message that arrives
       cut is worth more than a message that does not arrive. The noop
       never stores, so it always truncates plainly.")

    (context-put! [this data opts]
      "Store DATA under a fresh opaque ctx-id, OPTS being {:tags set
       :ttl-ms long}. Returns the id, or nil when no store exists. The
       caller passes this method's var (eta-expanded) as elide!'s
       PUT-FN.")))

;;; ============================================================================
;;; IDeliveryFanout: fanout across registered delivery backends
;;; ============================================================================

(defonce ^:private -ideliveryfanout-defined? (atom false))

(when (compare-and-set! -ideliveryfanout-defined? false true)
  (defprotocol IDeliveryFanout
    "Fanout across every registered delivery backend. The backend protocol
     itself stays host-side: the swarm only needs to fire and to answer
     'is anything up?', so the port exposes those two verbs and hides
     registration."

    (fanout! [this payload]
      "Deliver PAYLOAD ({:type keyword, ...}) to every registered delivery
       channel. Fire-and-forget: failures are non-fatal and swallowed by
       the adapter. Returns nil.")

    (delivery-channels [this]
      "Inventory of registered backends as [{:id kw :available? bool}] :
       the frontend-agnostic view status surfaces reports. Empty seq when
       nothing is registered.")))

;;; ============================================================================
;;; The Noop: the degraded host. Answers with the shape each caller's
;;; existing failure branch already handles. Never throws, never blocks.
;;; ============================================================================

(defn- noop-elide
  "The noop's elision: plain truncation, never a store round-trip."
  [s cap]
  (if-not (and (string? s) (int? cap) (< cap (count s)))
    {:text s}
    {:text (str (subs s 0 (max 1 (dec cap))) "…")}))

(def noop
  "The absent-host implementation of every messaging port. The default
   value of the slot: callers degrade instead of crashing. With no sinks
   registered, deliver! answers :no-sink."
  (reify
    IEventBus
    (publish! [_ _event] nil)
    (subscribe! [_ _event-type] nil)                ; callers when-let / skip the go-loop
    (unsubscribe! [_ _event-type _ch] nil)

    IFrontendPush
    (broadcast! [_ _msg] nil)
    (emit! [_ _event-type _data] nil)
    (frontend-status [_]
      {:channel-connected? false :ws-connected? false :ws-clients 0})

    IAudience
    (coordinator-reader? [_ _reader-id] false)      ; no host -> no coordinator lanes
    (coordinator-session [_ _id] nil)

    IConversationStore
    (register-message-source! [_ _source-fn] nil)
    (fetch-conversation [_ _context-id _opts] [])   ; never nil, so callers seq freely
    (new-conversation-id [_] nil)

    IBroadcastGovernance
    (apply-policy [_ msg _opts]
      (dissoc msg :broadcast? :broadcast-reason))   ; refusal downgrade, no reason
    (spent-recently [_ _project-id] 0)
    (spent-recently [_ _project-id _now] 0)
    (record-broadcast! [_ _project-id] nil)
    (record-broadcast! [_ _project-id _now] nil)

    IContextStore
    (elide! [_ s _put-fn] (noop-elide s 240))       ; adapter-default cap; never stores
    (elide! [_ s cap _put-fn] (noop-elide s cap))
    (context-put! [_ _data _opts] nil)

    IDeliveryFanout
    (fanout! [_ _payload] nil)
    (delivery-channels [_] ())))

;;; ============================================================================
;;; Slot: install/read/clear, same shape as hive-spi.time.ports
;;; ============================================================================

(defonce ^:private port-slot
  (slot/single-slot {:validate #(satisfies? IEventBus %)
                     :on-empty (constantly noop)}))

(defn set-messaging!
  "Install ADAPTER as the active messaging port implementation. Returns
   ADAPTER. Throws on a programming error (the value is not an IEventBus :
   which every full messaging adapter satisfies)."
  [adapter]
  (slot/install! port-slot adapter))

(defn get-messaging
  "The active messaging adapter: the installed one, else the noop."
  []
  (slot/current port-slot))

(defn clear-messaging!
  "Remove the installed messaging adapter, so consumers fall back to the
   noop. Returns nil."
  []
  (slot/clear! port-slot))

(defn messaging-set?
  "True iff a messaging adapter is explicitly installed. The noop default
   does not count."
  []
  (slot/present? port-slot))
