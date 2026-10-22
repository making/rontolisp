# g42: Run-time value arms for satisfies?/extends?/extend (g40 follow-up)

Difficulty: Hard

Parent: `.todo/g40-run-time-value-arms-for-lower-time-only-verbs.md` (whose
multimethod-six slice shipped; this item is the protocol-three slice).

## Background

g40 gave the six multimethod verbs run-time value arms behind a run-time
registry (`%clojure-multimethods`: `defmulti` registering getters over its
tables beside the dispatch function, the value arms reading the same tables
the calls read). Three count-then-refuse arms remain from the same family:

- `satisfies?`, `extends?`, `extend`: the protocol's tables live under the
  literal protocol name; a run-time protocol value (its methods table) reaches
  neither the inline-implementation table nor the `Object` default (a table
  read alone would answer wrong booleans, so the arms refuse instead of
  approximating; `extenders`/`find-protocol-impl`/`find-protocol-method`
  already read the table best-effort with a nil default).

Pins: `ClojureLoweringTest#lowerTimeOnlyVerbsRefuseAtRunTimeAsValues`
(`of a computed protocol` / `of a computed type or protocol` asserts),
clojure-spec `core-functions-resolve-as-values` (runnable shapes).

## What would change

Give the three a run-time implementation that answers what the oracle
answers for values (not just literals), without changing head-position
behavior. The likely shape follows g40: a run-time registry the definitions
populate (`defprotocol` registering its methods/inline/default tables beside
the methods table the protocol var already holds) that the value arms read --
but the registry writes must not change any head call's lowered shape or
answered value, and every backend (interpreter, JVM, both WASM) must agree.

Known hard parts (from the g40 investigation, 2026-10-26):

- `satisfies?` is more than a methods-table read: the inline-implementation
  table wins first (a body implementation lives there), the `Object` default
  answers true when present, and a walking protocol (`walks`) or an interface
  guard consults the walked classes behind lower-time knowledge
  (`interfaceGuard`, `walkedClasses`, reducer rows). The registry record must
  carry enough of that (inline table, default, walk spec) or the arm must
  refuse where it cannot decide -- never approximate.
- `extends?` needs the type-to-tag mapping `extendKeyForm` computes at lower
  time (record/deftype tags, class-spelling kind keywords, chained class
  keywords); the oracle also takes `nil` (false) and throws ClassCastException
  for keywords, NPE for non-protocols.
- `extend` never arity-fails in the oracle (only the 0-count signals; any
  other count partitions `proto+mmaps` by 2, dropping a trailing odd entry,
  and answers nil), checks each proto (`<proto> is not a protocol`
  IllegalArgumentException), ignores non-map method maps and entries naming no
  method, and installs through the same rows a literal `extend` stores
  (inline table, reducer rows, walk keys, typed targets). The head lowering
  refuses degenerate counts and unknown methods at lower time -- that stays.
- Arities stay oracle-identical in both positions where the head already is
  (`satisfies?`/`extends?` take exactly 2); where the head deviates (extend's
  lower-time count check) the value arm follows the oracle and the deviation
  is documented, like g40's arity lines.

## Acceptance

- The verb runs as a value where the oracle runs it (higher-order and
  `(def x NAME)` application), with oracle-identical arities in both
  positions.
- Probe (`probe4.py` in `.todo/artefacts/g15-clojure-core-presence-probe/`)
  counts unchanged or better; full suite green.

## Non-goals

- Changing any head-position behavior or the already-pinned refusal wordings.
- `load` (only literal paths load, where the call stands: a run-time load
  needs the compiler at run time, which the compiled backends have not got)
  and `push-thread-bindings`/`pop-thread-bindings` (the pairing never exists
  around a value): evaluated as keep-refused in g40, no todo.
