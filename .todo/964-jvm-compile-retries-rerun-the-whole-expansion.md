# Each JVM compile retry re-runs the whole expansion

Difficulty: Medium

`JvmLispCompiler.compile`'s retry loop (`GateUnderpredicted` / `MethodTooLarge`, see
`.kb/hot-path-method-size.md`) throws an attempt away and starts again from
`expandTopLevelDefinitions`. mito's `MitoE2eTest` probe takes 5 attempts.

Measured 2026-09-25, after the runtime-subtypep table fix (`.kb/declarations-type-checks.md`,
"The ancestor table resolves each name ONCE"): the probe's JVM compile is 51 s wall, JFR
(`settings=profile`, 4,468 main-thread samples) puts ~60% of it under
`JvmSourceCompiler.compileProgram` -> `JvmLispCompiler.compile` (~5-6 s per attempt) and ~25% in
`CompileFrontend` (quickload and the splice chain, run once). The component compile
(`--component --optimize`) of the same program is 32 s.

## Plan

- Record which signal each of the 5 attempts raises and for which functions.
- Keep what an attempt learns that does not depend on the forced set -- or predict it up front
  (the oversized defuns of one attempt are known before Pass 2 of the next) -- so a large program
  pays one or two attempts, not five.
- Prove the emitted classes byte-identical on the probe and the example corpus.
