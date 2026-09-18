(ns hive-spi.swarm.agent
  "Unified agent lifecycle protocol.

   Protocols:
   - IAgent — agent lifecycle: spawn, dispatch, kill, status, claims.

   ISP-segregated: registry and LLM-backend protocols remain in
   hive-mcp.agent.protocol (they are hive-mcp concerns).")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol IAgent
  "Unified agent lifecycle protocol for lings"
  (spawn! [this opts]
    "Spawn the agent. Returns agent-id.")
  (dispatch! [this task-opts]
    "Send a task to the agent. Returns task-id.")
  (kill! [this]
    "Terminate the agent and release resources.")
  (status [this]
    "Get current agent status map.")
  (agent-type [this]
    "Returns the agent type keyword (e.g. :ling)")
  (can-chain-tools? [this]
    "Returns true if agent can chain multiple tool calls")
  (claims [this]
    "Get list of files currently claimed by this agent.")
  (claim-files! [this files task-id]
    "Claim files for exclusive access during task.")
  (release-claims! [this]
    "Release all file claims held by this agent."))
