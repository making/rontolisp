# a96: --dump-ir shows the fully-expanded form (built-in macros lowered)

Difficulty: Medium

## Premise (measured, 2026-09-30)

`--dump-ir` (a95, ab2386d86) prints the IR after USER macro expansion, and the
built-in macro calls (`unless`, `cond`, `setf`, ...) ride it unexpanded. That is
what the backends receive: the expansion to `if`/`let`/... happens BELOW the IR,
in each consumer -- the interpreter at eval time
(`LispEvaluator.expandBuiltinMacro` call), the compilers during body codegen
(`SpecialVarCollector` / `JavaDeclarations` reach the same
`LispMacroExpander.expandBuiltinMacro`). So "the compiler gets the expanded
form" is NOT true today; the request is to make the dump show the form after
that last expansion too.

## Why

The deepest shared form is the one worth diffing: a question about what a
built-in macro did becomes one diff, not a read of two expanders.

## Shape

- A dump-time lowering pass over the a95 output: repeatedly
  `LispMacroExpander.expandBuiltinMacro` through the form tree to a fixpoint,
  quote data untouched, `#'` lambda bodies walked.
- NOT a pipeline stage: the backends keep expanding where they do. The pass
  exists only for the dump, and its result must compile and run identically
  (round-trip test stays green, now on the expanded form).
- The known hard part: expansion is context-free here but the consumers expand
  with context. `SpecialVarCollector` already wraps the call in
  try/catch because "the expander may validate shapes the compiler checks
  later" -- the dump pass needs the same stance (a form the expander rejects
  stays as it is) and must not throw on programs the compilers accept.
  Symbol-macros / macrolet-shadowed names must not be expanded as builtins.
- Divergence risk to watch: the pass and a backend disagreeing on ONE form
  would make the dump lie. Before widening beyond the trivial operators, pin
  dump-equals-compiled-behavior on the ci-spec corpus, not on hand examples.

## Tests

- `unless`/`when`/`cond`/`setf` calls in the dump come out as `if`/... ; a
  quoted `'(unless ...)` does not.
- Round trip: the expanded dump re-runs to the same output (a95's test, kept).
- A program whose expansion the context-free pass cannot do (the
  SpecialVarCollector try/catch precedent) still dumps, unexpanded at that form,
  and still round-trips.
