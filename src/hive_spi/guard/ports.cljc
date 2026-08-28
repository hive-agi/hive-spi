(ns hive-spi.guard.ports
  "SPI ports for the guard subsystem — vendor-independent enforcement of the
   house rules over agent-harness tool calls.

   Pure protocol stubs — NO implementations live here. Each method docstring
   states its CONTRACT (argument shapes + return shape + failure semantics).

   Three ports, one per axis the design separates:

     IGuardRuleSource — WHERE rules come from (hive memory axioms carrying a
                        :guard/rule datum, a RoleCard's tool grant, an EDN file).
     IGuard           — the DECISION: a GuardEvent in, a GuardDecision out.
     IGuardProjection — the VENDOR: decode one harness's raw payload into a
                        GuardEvent, encode a GuardDecision into that harness's
                        wire shape, and render the rule set as that harness's
                        native config.

   The split is what makes enforcement vendor-independent. Rules are authored
   once against IGuardRuleSource, evaluated once by IGuard, and every harness
   is an IGuardProjection that translates at the edge. Adding a vendor adds a
   projection and changes no rule.

   This leaf is MIT and depends on no concrete component, so the MCP server
   itself — the one surface every vendor already speaks — can gate its own tool
   dispatch against it."
  (:require [hive-spi.guard.decision]
            [hive-spi.guard.event]
            [hive-spi.guard.rule]))

;; MIT License - Copyright (c) 2026 Pedro Gomes Branquinho (BuddhiLW)

(def decide-ext-key
  "The host extension key the guard publishes its decision seam under.

   One definition, two projections: the addon registers its `decide` fn under
   this key, and the host's tool-dispatch gate looks it up by this key. They
   live in different artifacts and must not each carry their own literal."
  :guard/decide)

(def register-projection-ext-key
  "The host extension key the guard publishes its PROJECTION REGISTRAR under.

   Same lever as `decide-ext-key`, in the other direction: the guard addon
   registers a (fn [projection] -> harness-id) here, and every vendor library
   looks it up to contribute its own `IGuardProjection` without naming the
   registry's namespace. A vendor lib is thus a plain IAddon that contributes
   a projection, the way an addon already contributes commands — adding a
   harness changes no host code.

   One definition, N vendors: the key lives here so no artifact carries its
   own literal."
  :guard/register-projection)

(defprotocol IGuardRuleSource
  "A source of GuardRules (see hive-spi.guard.rule/GuardRule)."

  (source-id
    [this]
    "Stable keyword identifying this source, e.g. :source/memory-axioms.
     Returned on each rule's provenance so a surprising deny can be traced to
     the source that supplied the rule. Pure; never throws.")

  (load-rules
    [this]
    "Load this source's current rule set.
     Returns: a vector of conformant GuardRule maps (possibly empty).
     Malformed rules are the SOURCE's problem: it must either repair or drop
     them, never emit a non-conformant rule for the engine to trip over.
     Throws: ex-info {:error :guard/source-unavailable} when the underlying
             store cannot be read — an unreadable source is not an empty one,
             and must not silently disarm every rule it holds."))

(defprotocol IGuard
  "Evaluate a normalized harness event against a rule set."

  (evaluate
    [this event]
    "Decide what may happen at this moment in this harness.
     Arguments:
       event — a conformant GuardEvent (hive-spi.guard.event/GuardEvent).
     Returns: a conformant GuardDecision. When several rules fire, their
              verdicts are folded with decision/strongest, so the result is
              independent of rule ORDER.
     Total: an event matching no rule returns a bare :allow, never nil.
     Throws: ex-info {:error :guard/invalid-event :explanation <malli>} when
             `event` does not conform — a guard must never decide on a payload
             it could not parse, because an unparsed payload silently allows.")

  (rules
    [this]
    "The rule set this guard is currently enforcing.
     Returns: a vector of conformant GuardRule maps (possibly empty). Pure read
              of the guard's current state; never throws.")

  (refresh!
    [this]
    "Reload rules from this guard's sources.
     Returns: {:loaded int :sources [keyword] :dropped int}.
     Effectful. On a source failure the guard KEEPS its previous rule set and
     reports the failure rather than enforcing nothing."))

(defprotocol IGuardProjection
  "Translate one agent harness to and from the normalized guard vocabulary.

   Vendors differ on all three of: the payload shape they emit, the wire shape
   they accept back, and where their configuration lives. A projection owns all
   three, so nothing above it names a vendor."

  (harness-id
    [this]
    "Stable keyword identifying the harness, e.g. :claude-code, :eca,
     :hive-agent, :mcp. Matches the `:guard/harness` this projection stamps
     onto the events it decodes. Pure; never throws.")

  (decode-event
    [this raw]
    "Decode this harness's raw event payload into a normalized GuardEvent.
     Arguments:
       raw — the vendor's own payload (a parsed hook JSON map, an RPC params
             map, an in-process call context).
     Returns: a conformant GuardEvent, or nil when `raw` describes a moment
              this projection does not map onto a phase (nil = not our event,
              which the caller treats as :allow).
     Throws: ex-info {:error :guard/undecodable-event} when `raw` IS one of
             this harness's guarded moments but cannot be read. Failing loudly
             beats decoding half an event and denying on the missing half.")

  (encode-decision
    [this decision]
    "Encode a GuardDecision into this harness's native wire shape.
     Arguments:
       decision — a conformant GuardDecision.
     Returns: the vendor value to hand back (e.g. Claude Code's
              {:hookSpecificOutput {…}}, an eca chat/toolCallReject params map,
              or the decision itself for an in-process gate).
     Every verdict MUST be encodable: a harness with no :warn channel degrades
     it to its advisory channel, never silently to :allow.
     Pure; never throws.")

  (render-config
    [this rule-set]
    "Render `rule-set` as this harness's native configuration.
     Arguments:
       rule-set — a vector of conformant GuardRules.
     Returns: {:files [{:path string :content string :mode string}]
               :notes [string]} — the files to write for this harness to route
              its guarded moments here, plus any human-facing notes.
     Pure: computes the content, WRITES NOTHING. The caller decides whether to
     install it, which is what keeps a generated hook reviewable before it can
     start denying. A harness needing no configuration (an in-process gate)
     returns {:files [] :notes [...]}."))
