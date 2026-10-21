# Portable `java.util.Random` for `data.generators` on wasm

Difficulty: Medium

`org.clojure/data.generators` 1.1.1 (no transitive deps) loads and runs
oracle-identical on the interpreter and the JVM (g20 probe: 12 calls --
`int`, `byte`, `boolean`, `float`, `double`, `printable-ascii-char`,
`rand-nth`, `tuple`, `weighted`, `one-of`, `(count (vec ...))`, `string` --
byte-for-byte against `clj -M probe.clj`, default seed 42, deterministic).
Both wasm backends refuse the namespace at load: its `*rnd*` root is
`(java.util.Random. 42)`, and construction of the host class is refused where
there is no host.

## Oracle vs here (g20 probe, 2026-10-21)

Oracle (`clj` 1.12.6.1673), first three lines:

```
977378563
46
false
```

Interpreter and JVM: identical, all 12 lines, warning-free. P1 and component:

```
Unhandled condition: The function JAVA:NEW is undefined
```

## What to build

- Either a portable `java.util.Random` (the algorithm is public: the 48-bit
  LCG, `next`/`nextInt`/`nextLong`/`nextFloat`/`nextDouble`/`nextBoolean`/
  `nextGaussian`/`setSeed`) that answers bit-identically on all four backends
  (`.kb/random.md` is the in-program generator precedent; entropy still comes
  from the host), or a documented refusal story for host `Random`
  construction on wasm that `data.generators` can use (e.g. a `:local/root`
  patch point -- but the top-level `*rnd*` root runs at load, so patching
  around it is not a two-line shim).
- Whichever it is, the g20 `data.generators` probe (12 calls above) must go
  green on all four backends, and the row joins the "Library probes" table
  (`.kb/clojure-frontend.md`) with the version pinned.
- Pin with a corpus case (`.kb/test-execution.md`: prefer
  `src/test/resources/ci-spec.yaml` over a per-backend test): seeded draws
  print the oracle's numbers on all four backends.

## Non-goals

- Full `java.util.*` portability beyond what this library loads (the probe
  names the next blocker if one is behind `Random`).
- Changing the interpreter/JVM legs: they already use the host `Random` and
  match the oracle bit for bit.

## Context

Cut from g20 (`.todo/g20-library-probe-batch.md`): greens join the probe
table, reds become their own item. Transitive deps: none (pom declares no
dependencies).
