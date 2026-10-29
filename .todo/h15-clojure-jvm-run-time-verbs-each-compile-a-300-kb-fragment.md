# Clojure on the JVM: every run-time verb compiles a 300 KB fragment

Difficulty: High

A JVM class lowers each lowering verb as it runs by compiling the lowered forms into a
fragment class (`.kb/jvm-run-time-lowering.md`). Measured 2026-10-30 on spec's
`instrument`/`check` probe (`.kb/clojure-frontend.md`, "clojure.spec.alpha", h05): 69 verbs --
41 `resolve`s (spec's registry walk, `instrument`, `check`) and 28 `require`s (gen's
`dynaload`, once per test.check combinator, most of them of a namespace already loaded) --
each a fragment of about 300 KB, most of it the per-class runtime helpers (`_hash*`, the
Gray-stream generics, the layouts' dead `_init$` fields); the probe takes about 60 s on the
JVM where the interpreter takes 8 s.

What to weigh, measured first (fragment count and bytes per verb, wall time of the probe):

1. A verb whose lowered forms define nothing and answer a constant (a `require` of a loaded
   namespace) answering without a compile.
2. A fragment linking the host's runtime helpers instead of emitting its own copy (they are
   static methods of the host already; the helper groups a fragment needs are the host's,
   `JvmLinkage.Mode`).
3. A `resolve` of a var the program already lowered answering from the host's own var
   sites.

Done when the probe's JVM time and fragment bytes are recorded before and after in the
`.kb` file, and the chosen change keeps clojure-spec's `host:` cases green.
