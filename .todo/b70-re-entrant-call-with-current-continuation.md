# b70. Re-entrant `call/cc`

Difficulty: High

`call/cc` is escape-only: one-shot, callable only while its `call/cc` still runs, no
re-entry, `dynamic-wind` before runs once (`.kb/scheme-frontend.md`, "the lowering
table"; `doc/*/scheme/deviations.md`). R7RS-small only promises escape, so this is
beyond conformance -- but re-entrant continuations are what unlocks generators,
coroutines and backtracking, the programs people actually write with `call/cc`, and
`amb`-style search. Deliberately its own todo, split from any escape fix.

Design space, in rising blast radius:

1. One-shot upgrade to multi-shot within one thread via heap-allocated frames: needs
 the interpreter's frames and both wasm backends' locals in a copyable shape; the
 JVM's real stack makes re-entry there the hard part -- the same problem b69 solves
 from the other side, so sequence after it and share the frame materialization.
2. Delimited continuations (`reset`/`shift`) as the primitive, `call/cc` over them:
 smaller surface, non-standard, but the wasm `try_table` landing-pad rules
 (`.kb/wasm-landing-pad-refresh.md`) already model capture points.
3. Thread-per-continuation (fork the thread, park it): trivially correct on the JVM,
 no wasm story -- refuse there by name as today.

Whatever the shape: `dynamic-wind` before/after must run on every entry and exit of a
re-entered continuation (R7RS 4.2.2's example is the pin), continuation objects stay
first-class procedure values, and `call/cc` of a program that only escapes keeps its
exact today bytes (the `block`+`return-from` lowering untouched for that case).

## Pin

- The R7RS `dynamic-wind` re-entry example's output on all four backends.
- A generator built on `call/cc` (value out, value back in) to 1M iterations without
 stack growth.
- Escape-only programs byte-identical on `.class`/wasm/component.
- `guard`/`raise` interactions: a continuation captured inside a `guard` body re-entered
 after the guard left.

## Acceptance

`scheme-spec.yaml` cases on all four backends, `SchemeLoweringTest` lowering pins,
the deviations doc's call/cc bullet rewritten.
