# The default run path is the tree-walking interpreter -- by decision

`rontolisp app.lisp` with no `-o` runs `LispEvaluator`, and that is a DECISION,
not an accident: every alternative was measured and rejected on this machine
(2026-08-25), and the docs now say loudly that the flagless path is the slow
one (`doc/*/getting-started/file-interpretation.md`). This file records the
measurements, why each alternative lost, and the triggers that reopen the
question. Do not flip the default -- or auto-switch engines per program --
without re-reading the "What reopens this" section.

## The measured landscape

Four script shapes, best of three, wall clock including startup:

| program | SBCL `--script` | interp `java -jar` | interp native binary | compile+run IN MEMORY (`java -jar`) | `-o Prog.class` then `java Prog` |
| --- | --- | --- | --- | --- | --- |
| `(print 'hi)` | 0.007 s | 0.44 s | **0.011 s** | 0.99 s | 1.01 s + 0.08 s |
| `loop 1..10^7 sum` | 0.029 s | 7.05 s | 11.9 s | **1.05 s** | 1.05 s + 0.15 s |
| 10^7 x `(+ s (random 10^6))` | 0.139 s | 7.49 s | -- | **1.29 s** | 1.01 s + 0.41 s |
| fill a 10^6 vector | 0.015 s | 2.28 s | -- | **0.99 s** | 0.86 s + 0.18 s |

Interpretation is 20x-240x off SBCL on the loops; the compiled JVM output is
within a small factor (the `.todo/517` program: the residual is `.todo/412`'s
boxed arithmetic). So the pull toward "compile by default" is real. What
stopped it is the other two columns.

## Where the fixed compile cost actually goes

`.todo/522` hypothesized the ~0.6 s fixed compile cost was the library
splice/prune chain ("the whole prelude is built and then thrown away") and
therefore cacheable. **Measured in process, that is false**:

| phase | cold (first compile in a JVM) | warm (same JVM, 2nd+) |
| --- | --- | --- |
| `CompileFrontend.run` (read, splices, macro expansion, pruner) | 268 ms | **4 ms** |
| `JvmSourceCompiler.compileProgram` (emit) | 613 ms | **140 ms** |
| defineClass + run `main` (hello) | -- | 2 ms |

The splice chain costs 4 ms warm -- there is nothing to cache. The fixed cost
is CLASS LOADING AND JIT WARM-UP OF THE COMPILER ITSELF, a property of running
the compiler on a cold JVM. The proof is the native binary, where AOT has
already paid it: the whole `rontolisp hello.lisp -o Hello.class` compile is
~0.16 s there (~0.01 s startup + ~0.15 s true compile, dominated by the
backend's ~140 ms emit). "Attack the fixed compile cost" therefore has no
algorithmic target; on `java -jar` the only fix is AOT of the compiler, which
IS the native binary.

## Why each direction lost

- **Compile and run in memory by default.** Mechanically trivial
  (`cli/JvmSourceCompiler` + a one-class `ClassLoader.defineClass`; load+run of
  the emitted class is ~2 ms). Rejected because of the two ends of the table:
  on `java -jar` it makes `(print 'hi)` 2.2x slower (0.44 s -> 0.99 s, the
  cold-compiler cost) and one-liners are most invocations; and on the NATIVE
  BINARY -- the distribution with the 0.011 s startup -- it is impossible: a
  closed-world native image cannot define classes at run time. GraalVM 25
  ships `-H:RuntimeClassLoading` (project Crema), but it is experimental,
  default-off, and executes runtime-loaded bytecode in an interpreter -- no
  faster than the tree walker it would replace.
- **Cache compiled classes keyed on source hash** (tier-up: interpret the
  first run, compile in the background when it ran long, load the cached class
  on the next run). The mechanism is sound
  (`.kb/emitted-output-determinism.md` makes the key well-defined) and on
  `java -jar` a cache hit would run hello in ~0.1 s and the loops in ~0.3 s.
  Rejected TODAY for two reasons: the native binary cannot execute the cached
  class, so the primary distribution gains nothing; and auto-switching engines
  between run 1 and run 2 makes every open interpreter/compile-path divergence
  (`.todo/384` run-time eval of a user macro, `.todo/446` runtime `load`,
  `.todo/434`, `.todo/444`, ...) a SILENT run-to-run behavior change in a
  user's script. An engine switch the user did not ask for is only acceptable
  when it is unobservable.
- **Compile only `defun` bodies, top level interpreted.** Needs the two
  representations to share one environment bidirectionally, which is
  `.todo/013`; blocked on it.
- **Do nothing and say so.** Chosen. The interpreter stays the default -- it
  is the REPL, it is `eval`, it is the semantics the other three backends are
  pinned against -- and the docs state the cost and the way out
  (`-o`; `doc/en/getting-started/file-interpretation.md` "Interpretation
  Speed", mirrored in `doc/ja`, and the execution-modes list in
  `doc/*/index.md`).

## What reopens this

Re-evaluate the default (in this order of leverage) when:

1. **GraalVM runtime class loading executes at compiled speed.** When Crema
   (`-H:RuntimeClassLoading`) JIT-compiles runtime-loaded classes, the native
   binary can do exactly what SBCL does -- compile every program in ~0.15 s
   and run the result -- and "compile by default above a size/loop threshold"
   becomes the right call there.
2. **The interpreter/compile-path divergence backlog closes** far enough that
   which engine ran is unobservable (the `.todo` items above plus the
   conformance sweep `.todo/338`). That unlocks the source-hash cache for
   `java -jar` even before (1).
3. **`.todo/412` lands** (unboxed JVM arithmetic): it widens the payoff and
   may justify revisiting (2) sooner.

Whatever changes then, two constraints survive: `(print 'hi)` must not get
slower on any distribution, and `ci-spec.yaml` + `ExamplesE2eTest` stay
byte-identical across all four backends.
