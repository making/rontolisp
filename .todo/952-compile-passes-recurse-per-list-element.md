# The compile path's passes spend a stack frame per list element

Difficulty: Medium

An embedder compiles on its OWN thread: `JvmSourceCompiler` (the Maven plugin's
`LispSourceSet`) runs the front end and the backend on whatever stack the caller has --
1 MiB on linux-x64 for Maven's main thread -- where the CLI hands the same work 16 MiB.
Measured 2026-09-24 on a fresh JVM (`.kb/test-execution.md`, "In-process program work runs
on the CLI's stack"): `JvmSourceCompiler` on a 1 MiB thread compiles a `(progn ...)` of
1,400 forms and overflows on 2,800 (`JsonLibrary$Walker.rewrite`) -- fast-http's generated
state machine is that shape. The depth is the list's LENGTH, not the program's nesting:
~216 self-recursive `.cdr()` walks in `src/main/java` (census: a method calling itself with
an argument containing `.cdr()`), e.g. `CompileTimeBoundp.scan`/`calls`/`collectSpecials`,
`UiopLibrary.collectSymbols`, `PackageResolver.referencesRuntimePackageMutation`,
`AsdfRuntimeLibrary.referencesRuntime`, the `*Library.detect`/`references`/`collectNames`
family.

## Plan

Two independent halves; decide by measurement which to land first.

- Hand the embedder's compile to a thread of the CLI's size inside `JvmSourceCompiler` (and
  whatever the WASM embedder seam is), so the ceiling is one number for every caller, as the
  interpreter's is (`.kb/interpreter-stack.md`). Check what crosses the thread: the context
  class loader, `ThreadStdio`-style inheritable state, diagnostics written to `System.err`.
- Walk the cdr spine in a LOOP in the passes (recursing on the car only), as the evaluator's
  typecase scans already do. A shared iterative walker would replace most of the ~216 copies;
  a guard test compiling a very wide `progn` on a small fixed stack keeps new ones out.
