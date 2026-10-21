# Call-time refusals so integrant loads

Difficulty: Hard

`integrant/integrant` 1.0.1 (transitive: `weavejester/dependency` 1.0.0) fails
to LOAD on every backend in the g20 probe: `integrant/core.cljc` defines
`resources`/`load-hierarchy` over `enumeration-seq` +
`clojure.lang.RT/baseLoader` (host classloading), and every refused verb --
`future`, `enumeration-seq`, `bean`, `compile`, ... -- throws at LOWER time,
even inside a never-called `defn`. The probe never calls those functions;
their definitions alone stop the load.

## Oracle vs here (g20 probe, 2026-10-21)

Oracle (`clj` 1.12.6.1673) loads the namespace (the fns exist; only calling
`load-hierarchy` touches the host). Every backend here refuses the load:

```
error: .../integrant-1.0.1.jar!/integrant/core.cljc:269:8: enumeration-seq is not supported yet: host enumerations stop at the host boundary
```

(A companion check: `(defn f [] (future 1))` loads in the oracle and refuses
here with `future is not supported yet` -- same by design.)

## What to build

- Either move the refused verbs' refusals from lower time to call time (a
  program naming `future`/`enumeration-seq`/etc. compiles, and only the call
  signals, in the same words), so libraries that merely DEFINESUCH functions
  load, or document why load-time is load-bearing and give integrant's shape
  another path. The blast radius is every refusal row, so measure it as one
  project, not one verb: the suite must show which currently-red definitions
  turn green and that no green turns red.
- `clojure.lang.RT/baseLoader` + `.getResources` behind `resources` is a
  second host wall on the same path (no class loader exists here,
  `.todo/g26`'s `add-classpath` decision); `load-hierarchy` needs a story
  there too (refuse at call time with the classpath decision's wording, or
  read `hierarchy.edn` off the deps.edn roots).
- Whichever it is, the g20 integrant probe (12 calls: `init` with refs,
  `halt!`, `expand`, `refset`, `find-derived`, `resolve-key`, `read-string`,
  `ref?`/`refset?`/`derived-from?`, `:default` init-key, `valid-config-key?`,
  partial `init`; oracle output recorded 2026-10-21 in g20's notes) must
  print the oracle's lines on all four backends, and the row joins the
  "Library probes" table (`.kb/clojure-frontend.md`) with both coordinates
  pinned (integrant 1.0.1 + weavejester/dependency 1.0.0).
- Pin with a corpus case (`src/test/resources/ci-spec.yaml`): the probe's
  init/halt lines on all four backends.

## Non-goals

- `qbits.ex` itself: green in g20 (0.1.3, 12 calls, all four backends; needed
  `derive`/`underive` as values, which landed with the probe).
- Changing what `enumeration-seq`/`future` DO when called: still refused,
  only later.
- The `deep-merge-with`/`read-string`-of-refs shapes the probe tripped on:
  oracle-side behavior (no such var; no reader tag), not blockers.

## Context

Cut from g20 (`.todo/g20-library-probe-batch.md`): greens join the probe
table, reds become their own item. g20's `derive`/`underive`-as-value fix
(`ClojureHierarchyLowering.valueOf`, used by `swap!`'s `fnValue`) stays
regardless -- qbits.ex pins it.
