# g37: STM conflict retry (`dosync` / `commute` / `ensure`)

Difficulty: Hard

A transaction runs holding `%clojure-stm-guard`, one lock for every
transaction of the process: transactions run one at a time and never retry
(`src/main/resources/am/ik/rontolisp/eval/clojure.lisp:13662-13669`). A
body's side effects therefore run exactly once -- a documented deviation from
the oracle, where a conflicting body re-runs and side effects may repeat.

The single-lock model is correct but serializes all contention and teaches
the wrong program shape: STM-flavored ports (including the shcloj4 drivers
and malli-style validation pipelines) that rely on `commute` not blocking
writers behave differently here under threads.

## What to change

- Implement multi-version-style conflict detection in the spliced STM runtime
  (`clojure.lisp` STM section, `ClojureStateLowering.dosyncOf` stays the
  entry): track the read set per transaction; on commit, retry the body when
  another committed transaction wrote a read ref. `commute` applies at commit
  without invalidating readers; `ensure` pins the read (blocks writers until
  commit, like the oracle).
- Retry is Lisp-level code, so it runs identically on all four backends;
  conflicts only arise where threads exist (interpreter/JVM), and on WASM the
  behavior collapses to today's single execution -- no per-backend fork.
- Side effects in a retried body run more than once: this is the oracle's
  semantics, but it reverses the current documented guarantee, so update the
  deviation text explicitly and add a spec row pinning a counted side effect
  re-running (the oracle's own answer, diffed).
- Validators still run on final values; a refusing validator still rolls the
  log back (`clojure.lisp:13700-13708` shape kept).

## Acceptance

- New concurrency test: two threads bumping the same refs inside `dosync`
  converge to the oracle's total on interpreter and JVM (no lost updates);
  single-threaded programs print byte-identical output to before.
- `clojure-spec.yaml` rows for `commute` (no writer invalidation) and
  `ensure` (writer blocked) diffed against `clj`.
- `.kb/clojure-frontend.md` (State/STM section), `doc/en+ja/clojure/
  deviations.md` STM passage rewritten (en/ja mirrored in the same commit).
- Full suite green (merge `origin/develop` right before the run, one maven
  run per worktree, no `src/` edits during it).

## Non-goals

- `ref` `:min-history`/`:max-history` enforcement (still unimplemented;
  out of scope for this item).
- Changing the agent execution model -- that is g38.
