# g40: Run-time value arms for the lower-time-only core verbs (g39 follow-up)

Difficulty: Medium

Parent: `.todo/g39-core-function-value-arms.md` (which resolved all 30 names in
value position; this item is the behavior behind 12 of those arms).

## Background

g39 gave every head-only `clojure.core` function a value arm. Eighteen run what
the call runs. Twelve refuse at run time after checking the count in the call's
words, because the head's meaning is lower-time-only:

- `get-method`, `methods`, `remove-method`, `prefer-method`, `prefers`,
  `remove-all-methods`: the multimethod's tables live under the literal name the
  call names; a run-time multimethod value carries no handle to them.
- `satisfies?`, `extends?`, `extend`: the protocol's tables live under the
  literal protocol name; a run-time protocol value (its methods table) reaches
  neither the inline-implementation table nor the `Object` default (a table read
  alone would answer wrong booleans, so the arms refuse instead of
  approximating; `extenders`/`find-protocol-impl`/`find-protocol-method` already
  read the table best-effort with a nil default).
- `load`: only literal paths load, where the call stands (like
  `load-string`/`load-file` of a computed string, which refuse the same way).
- `push-thread-bindings`/`pop-thread-bindings`: the pairing never exists around
  a value (refuses with the pairing's words, like the unpaired head shape).
- `make-array` of the byte class: only a host class object names the primitive
  byte, so on wasm (and for a designator, which names the boxed class) the
  general array answers, like the call of a boxed class.

Pins: `ClojureLoweringTest#lowerTimeOnlyVerbsRefuseAtRunTimeAsValues`,
clojure-spec `core-functions-resolve-as-values` (runnable shapes),
`ClojureBytesTest#makeArrayAsAValueBuildsByteArraysOfTheByteClass` (host leg).

## What would change

Give any of these a run-time implementation that answers what the oracle
answers for values (not just literals), without changing head-position
behavior. The likely shape is a run-time registry the definitions populate
(`defmulti` registering its tables beside the dispatch function,
`defprotocol` registering its methods/inline/default tables beside the methods
table the protocol var already holds) that the value arms read -- but the
registry writes must not change any head call's lowered shape or answered
value, and every backend (interpreter, JVM, both WASM) must agree. Smaller
slices are independently shippable (one verb family at a time); each needs
oracle-diffed pins on all four backends like g39's.

## Acceptance

- The verb runs as a value where the oracle runs it (higher-order and
  `(def x NAME)` application), with oracle-identical arities in both positions.
- Probe (`probe4.py` in `.todo/artefacts/g15-clojure-core-presence-probe/`)
  counts unchanged or better; full suite green.

## Non-goals

- Changing any head-position behavior or the already-pinned refusal wordings.
