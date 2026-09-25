# The runtime-subtypep ancestor table dominates a large program's compile time

Difficulty: Medium

Compiling mito's `MitoE2eTest` probe to JVM takes ~250 s, and nearly all of it is one table.
Measured 2026-09-25 with JFR (`settings=profile`, 23,872 samples, 249 s): 89% of the samples
are inside `JvmLispCompiler.compile`, and 92% of those (19,653) are inside
`LispMacroExpander.expandTopLevelDefinitions` -> `subtypepAncestorTableForms`. The JVM
emission itself (Pass 2, the runtime builders, the split into `$PartN` classes) is a few
seconds.

## The mechanism

`subtypepAncestorTableForms` calls `subtypep(name, candidate)` for every PAIR of the
runtime-subtypep universe, which holds every registered class -- U^2 calls. Each call reaches
`ClosRegistry.findClass`, and a name that is not an exact key (most of them) falls to
`uniqueByMember` / `uniqueAliasByMember`: a linear scan over every registered class, with a
`PackageRegistry.splitQualified` per candidate. So the table costs U^2 x C. The top frames are
`ImmutableCollections$MapN.probe` (9,117 samples) and `ClosRegistry.uniqueByMember` (7,897).

The WASM legs run the same expander (the component leg of `MitoE2eTest` compiles for minutes
too, not yet measured).

## Plan

- Index the member-name fallbacks (`uniqueByMember`, `uniqueAliasByMember`) by member,
  invalidated on registration -- or memoize `findClass`'s misses per registry state.
- Better: build the table from the class graph (each class's ancestors are its precedence
  list) instead of U^2 `subtypep` calls, keeping `subtypep` itself as the oracle a test
  compares the table against.
- Measure the probe before and after (target: seconds, not minutes), and prove the emitted
  program byte-identical on the example corpus.
