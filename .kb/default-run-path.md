# The default run path is the tree-walking interpreter -- by decision

`rontolisp app.lisp` with no `-o` runs `LispEvaluator`. Deliberate. Do not flip the default
or auto-switch engines per program without reading "What reopens this". Docs state the cost
and the way out (`-o`): `doc/*/getting-started/file-interpretation.md` "Interpretation
Speed"; `doc/*/index.md` execution-modes list.

## Cost shape
- Interpretation is 20x-240x off SBCL on loops; compiled JVM output is within a small factor.
- The ~0.6 s fixed compile cost on `java -jar` is CLASS LOADING + JIT WARM-UP OF THE
  COMPILER ITSELF, not the splice/prune chain (warm: `CompileFrontend.run` ~4 ms,
  `JvmSourceCompiler.compileProgram` ~140 ms). Nothing to cache; the only fix is AOT, i.e.
  the native binary (~0.16 s total compile there).

## Why each alternative lost
- **Compile+run in memory by default.** Trivial (`cli/JvmSourceCompiler` +
  `ClassLoader.defineClass`, load+run ~2 ms), but makes `(print 'hi)` 2.2x slower on
  `java -jar` (0.44 -> 0.99 s) and is IMPOSSIBLE on the native binary: a closed-world image
  cannot define classes at run time. GraalVM 25's `-H:RuntimeClassLoading` (Crema) is
  experimental, default-off, and interprets runtime-loaded bytecode.
- **Cache compiled classes keyed on source hash** (tier-up). Key is well-defined
  (`.kb/emitted-output-determinism.md`), but the native binary cannot execute the cached
  class, and switching engines between run 1 and run 2 turns every open
  interpreter/compile-path divergence into a silent run-to-run behavior change. An engine
  switch the user did not ask for is only acceptable when unobservable.
- **Compile only `defun` bodies, top level interpreted.** Needs the two representations to
  share one environment bidirectionally; blocked on that.
- **Do nothing.** Chosen. The interpreter is the REPL, is `eval`, and is the semantics the
  other three backends are pinned against.

## What reopens this
1. GraalVM runtime class loading at compiled speed (Crema JIT) -- then "compile by default
   above a size/loop threshold" is right for the native binary.
2. The interpreter/compile-path divergence backlog closing far enough that which engine ran
   is unobservable -- unlocks the source-hash cache for `java -jar`.
3. Unboxed JVM arithmetic landing: widens the payoff, may justify (2) sooner.

Surviving constraints: `(print 'hi)` must not get slower on any distribution;
`ci-spec.yaml` + `ExamplesE2eTest` stay byte-identical across all four backends.
