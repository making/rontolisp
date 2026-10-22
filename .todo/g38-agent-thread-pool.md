# g38: Agent thread pool with error modes

Difficulty: Hard

An agent runs its actions one at a time in send order, but on the sender's
thread: a send drains the queue inline when no thread is running the agent,
and there is no thread pool
(`src/main/resources/am/ik/rontolisp/eval/clojure.lisp:13671-13681`).
Error handling (`:fail` vs `:continue`, handler call, queue kept until
`restart-agent`) is already modeled there, so the state machine exists --
only the executor is missing. The oracle runs agent actions on a pool of
threads while `await` blocks the caller until the queued actions complete.

A CPU-bound action therefore blocks the sender here (including a request
thread under `concurrent-served-requests`), and `await`'s marker-action
machinery (`clojure.lisp:13930-13958`) can only observe inline execution.

## What to change

- Execute agent actions on a shared pool on the interpreter and JVM legs
  (reuse the pool g35 introduces for futures if one exists by then; otherwise
  a small dedicated executor behind the same seam -- do not build two pool
  designs, sequence the items).
- Keep the guarantees the comment block already promises: per-agent FIFO,
  actions outside every lock, sends during a transaction/action held until it
  completes and dropped on failure, failing actions calling the handler then
  `:fail` (keep error, stop, refuse sends/`await` until `restart-agent`) or
  `:continue` (queue goes on).
- `send`/`send-off` stay non-blocking; `await`/`await-for`
  (`ClojureStateLowering.java:1287-1330`) block on completion markers, with
  `await-for`'s timeout answering the oracle's boolean.
- WASM legs keep sender-thread execution (single-threaded module, guards are
  no-ops): documented per-backend difference, same printed values, ordering
  pins stay backend-independent.

## Acceptance

- New tests: a slow action no longer blocks the sender (mailbox order pin:
  sends return at once, values converge after `await`); `:fail`/`:continue`
  transitions, `restart-agent`, `agent-error`, `set-error-handler!` diffed
  against `clj` on interpreter and JVM; WASM legs pin the sender-thread
  deviation.
- `clojure-spec.yaml` agent rows extended (error-mode transitions are pure
  enough to print).
- `.kb/clojure-frontend.md` (State/agents section) and
  `doc/en+ja/clojure/deviations.md` updated (en/ja mirrored in the same
  commit).
- Full suite green (merge `origin/develop` right before the run, one maven
  run per worktree, no `src/` edits during it).

## Non-goals

- The `future`/`promise` primitives -- that is g35 (this item reuses, or
  precedes with a shared design for, its pool).
- STM retry -- that is g37.
