# f48. Clojure agents have no failed state, agent-error or restart-agent

Difficulty: Medium

An agent's failing action (a throw, or a refusing validator) signals on the thread running
the agent's queue -- the sender's when the agent was idle, otherwise whichever request's
thread was draining it -- and the agent stays usable (`clojure.lisp` "Refs and agents
across request threads"; `doc/*/clojure/deviations.md`). The oracle (clj 1.12.6, measured
2026-10-11) runs `:fail` mode by default: `(send (agent 1 :validator pos?) dec)` answers the
agent, `agent-error` answers the `IllegalStateException`, and the next `send` throws
`RuntimeException: Agent is failed, needs restart` until `restart-agent`. `agent-error`,
`restart-agent`, `set-error-handler!`, `set-error-mode!`, `error-handler`, `error-mode` and
`(agent x :error-handler f :error-mode m)` are unknown names here.

## Plan

1. Keep the error in the agent's state (`(running . queue)` grows a slot), stop the drain,
   refuse `send`/`await` on a failed agent with the oracle's message.
2. Add `agent-error`, `restart-agent` (`:clear-actions`), the error handler and mode
   options and setters (`.kb/adding-primitives.md`, reference pages in both trees).
3. Pin on all four backends (`clojure-spec.yaml`) against the oracle; drop the deviation.
