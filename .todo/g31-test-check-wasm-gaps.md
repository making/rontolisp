# test.check 1.1.3's remaining wasm gaps (proxy ThreadLocal, Math/Long statics)

Difficulty: Hard

Cut from g28 (2026-10-21): the unchecked-math wrapping works on all four
backends (clojure-spec `unchecked-math-wraps-star-and-plus-for-splittable-random`),
and the 12-call seeded probe prints the oracle's lines on the interpreter and
the JVM. Both wasm backends refuse the namespace at LOAD, before any draw:

```
Unhandled condition: The function JAVA:SUBCLASS is undefined
```

## Oracle vs here

- `clojure.test.check.random`'s seedless `next-rng` builds a `proxy` of
  `ThreadLocal` at load. A `proxy` over a class lowers to `java:subclass`,
  which is a call-time error where there is no host -- so the module's start
  function dies during the load, on Preview 1 and `--component` alike.
- Past the proxy, every draw needs host statics the portable lowering does
  not cover (`.kb/clojure-frontend.md`, "Portable number statics"):
  `Math/floor` in `calc-long` (every `choose` draw), `Math/abs`,
  `Long/bitCount` in `mix-gamma` (every split), `Long/numberOfLeadingZeros`
  in `shrink-long`. Each is a `JAVA:STATIC` call-time error on wasm.

## What to build

- Either portable answers for the statics above on wasm (extending
  `ClojurePortableStaticsLowering` with oracle-checked workers, f81/f99
  precedent) plus a load-time story for the `ThreadLocal` proxy (a portable
  cell where the target is single-threaded, or a documented refusal story
  the library can use), or a documented refusal with the enumerated list.
- Whichever it is, the g28 12-call probe (three seeded `quick-check`s, nine
  seeded `gen/generate`s) prints the oracle's lines on both wasm backends,
  and the "Library probes" row goes `=` on all four.
- Shrinking past small values is NOT this item: even with the statics,
  `int-rose-tree` builds its child lists eagerly (strict `map` over the
  shrink arrays -- `.todo/g33-*`), so large `choose` ranges hang on every
  backend. Keep this probe to the small-valued generators until that lands.

## Non-goals

- General `proxy`-on-wasm (host dynamic class machinery); only what this
  namespace loads and draws.
- Changing the interpreter/JVM legs: verbatim 1.1.3 already prints the
  oracle's lines there.

## Context

g28 (`.todo/g28-unchecked-math-wrapping.md`): `*`/`+` wrap mod 2^64 while
`*unchecked-math*` is truthy. No transitive deps (only test-scope
clojurescript). The probe: three `quick-check`s with `:seed` + `:max-size 10`
and nine small-valued `gen/generate`s, all oracle-identical on interp/JVM.
