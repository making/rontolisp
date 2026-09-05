# The default run path is the tree-walking interpreter -- by decision

`rontolisp app.lisp` with no `-o` runs `LispEvaluator`, deliberately. Do not flip the
default or auto-switch engines per program. Docs state the cost and the way out (`-o`):
`doc/*/getting-started/file-interpretation.md` "Interpretation Speed"; `doc/*/index.md`.

## Cost shape
- Interpretation is 20x-240x off SBCL on loops; compiled JVM output is within a small factor.
- The ~0.6 s fixed compile cost on `java -jar` is CLASS LOADING + JIT WARM-UP OF THE
  COMPILER ITSELF, not the splice/prune chain (warm: `CompileFrontend.run` ~4 ms,
  `JvmSourceCompiler.compileProgram` ~140 ms). Nothing to cache; the only fix is AOT, i.e.
  the native binary (~0.16 s total compile there).

## Blockers on switching
- Compile+run in memory (`cli/JvmSourceCompiler` + `ClassLoader.defineClass`) makes
  `(print 'hi)` 2.2x slower on `java -jar` and is IMPOSSIBLE on the native binary: a
  closed-world image cannot define classes at run time (GraalVM 25 `-H:RuntimeClassLoading`
  is experimental and interprets).
- A source-hash class cache (`.kb/emitted-output-determinism.md`) is unusable for the same
  reason, and an engine switch between runs turns every interpreter/compile-path divergence
  into a silent behavior change.
- Compiling only `defun` bodies needs the two representations to share one environment
  bidirectionally; blocked on that.

Surviving constraints: `(print 'hi)` must not get slower on any distribution;
`ci-spec.yaml` + `ExamplesE2eTest` stay byte-identical across all four backends.
