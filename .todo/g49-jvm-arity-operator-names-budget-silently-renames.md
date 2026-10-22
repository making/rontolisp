# g49: The JVM arity-operator names budget silently renames late functions

Difficulty: Medium

Parent: `.todo/g46-jvm-leg-arity-messages-lost-in-full-corpus-runs-since-g42.md` (the
span cliff is chunked; this is the remaining silent fallback on the same path).

## What diverges

`JvmArityOperators` refuses entries past 60,000 bytes of joined names
(`MAX_NAMES_UTF8`): the callee then reports as `Function` instead of in its words
(`Wrong number of args (0) passed to: my.app/f` becomes `Function expects ...`).
The clojure-spec corpus registers ~26,000 bytes, so the budget holds today, but a
program naming every dispatchable fn -- the Clojure shape -- spends ~30 bytes per
function, and past the budget the failure is silent wrong text, the same family as
g46's silent nil.

## Acceptance

- Either the names ride a second constant past the budget (like `_arityErr`'s
  chunks), or the drop turns loud (a compile refusal naming the budget), or the
  mechanism documented as intended with its pin.
- A synthetic program with more distinct operators than the budget holds, asserting
  the chosen behavior on the JVM leg.
