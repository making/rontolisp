# Unchecked-math wrapping mode for `test.check`'s SplittableRandom

Difficulty: Hard

`org.clojure/test.check` 1.1.3 (no compile-scope deps) fails on every backend
in the g20 probe: `clojure.test.check.random` (an immutable
`java.util.SplittableRandom` port) runs its mix functions under
`(set! *unchecked-math* :warn-on-boxed)`, so `*` wraps mod 2^64. The front end
stores the flag (`set!`/`binding` of `*unchecked-math*` work) but the
arithmetic never reads it: `*` promotes to BigInt on overflow and the next bit
op refuses.

## Oracle vs here (g20 probe, 2026-10-21)

Oracle (`clj` 1.12.6.1673), `(:result (tc/quick-check 20 ... :seed 42))`:

```
true
```

Every backend, including the interpreter, on the first probe line:

```
Unhandled condition: bit operation not supported for: class clojure.lang.BigInt
```

## What to build

- Honor `*unchecked-math*` `true`/`:warn-on-boxed` in the arithmetic the
  SplittableRandom port uses (`*`, `+`, `unsigned-bit-shift-right`, `bit-xor`,
  `bit-or` on longs, wrapping mod 2^64) on all four backends
  (`.kb/random.md` is the in-program generator precedent; this is the
  language-level flag, not the `random` builtin). Warn-on-boxed should warn
  where the oracle warns, or document the difference.
- Or implement the bit operations over BigInt with two's-complement
  semantics -- but that alone prints the WRONG numbers (promotion computes the
  exact product where the oracle wraps before shifting), so it only counts
  combined with wrapping.
- Whichever it is, the g20 `test.check` probe (12 seeded calls: three
  `quick-check`s with `:seed`, nine `(gen/generate g size seed)`) must print
  the oracle's numbers on all four backends, and the row joins the "Library
  probes" table (`.kb/clojure-frontend.md`) with the version pinned.
- Pin with a corpus case (`src/test/resources/ci-spec.yaml`): seeded draws
  and a seeded `quick-check` print the oracle's values on all four backends.
  hive-test's `properties` macros (which emit test.check `defspec`s) unblock
  with it; note that in the row.

## Non-goals

- Changing the default (checked, promoting) arithmetic: f75 pinned that
  deliberately. Only the flag's wrapping mode is missing.
- hive-test's JVM-only namespaces (linearizability over threads/futures,
  nREPL MCP servers, sandbox classloaders, re-frame CLJS): g20 probed only
  its pure `tcheck.*` + `stateful` namespaces, which are green (own LCG, no
  unchecked math). A JVM-only census of the rest is a separate item.

## Context

Cut from g20 (`.todo/g20-library-probe-batch.md`): greens join the probe
table, reds become their own item. Transitive deps: none (only test-scope
clojurescript). hive-test 0.5.1 depends on test.check 1.1.3 and is green
except through it.
