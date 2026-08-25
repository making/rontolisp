# 522. The default `rontolisp app.lisp` is the tree-walking interpreter, 20x-200x off the compiled backends

Difficulty: High (this is a policy decision about what the CLI IS, and the
honest version of it needs the compile path to get faster first)

Child of `.todo/517`. Read `.todo/518`-`.todo/521` first: they are the reason the
compiled backends are worth reaching for.

## The situation

`rontolisp app.lisp` with no `-o` runs `LispEvaluator`, a tree-walking
interpreter. Everything a user does by default therefore pays interpretation,
while the backends `.todo/517` measures at 1.6x-3.4x of SBCL sit behind a flag
they have to know to pass. Measured 2026-08-25, this machine:

| program | SBCL `--script` | `java -jar rontolisp` (interp) | compiled + run |
| --- | --- | --- | --- |
| `(loop for i from 1 to 10000000 sum i)` | 0.031 s | 7.22 s | ~1.2 s |
| 10^7 x `(+ s (random 1000000))` | 0.180 s | 8.10 s | ~1.5 s |
| fill a 10^6 vector | 0.025 s | 2.88 s | ~1.3 s |
| `(print 'hi)` | ~0.01 s | 0.44 s | ~1.1 s |

**233x, 45x, 115x** on the three real loops. SBCL is not faster here because its
interpreter is better -- it does not have one on this path. `--script` compiles
every top-level form and runs the code.

## Why this is not simply "compile by default"

The last row. rontolisp's compile step costs ~0.63 s over bare startup, on hello
world as much as on anything else -- it is library splicing, pruning and class
emission, not program size. Compiling by default would make the loops 6x faster
and the one-liners 2.4x slower, and one-liners are most invocations. So the
essential answer has to include making the compile path cheap, not only making
it the default. Note the shape of the number: a fixed ~0.63 s that does not vary
with the program is a prelude/splice cost, which is the part most likely to be
cacheable or prunable earlier.

Directions, none of them yet chosen:

- **Compile and run in memory.** `cli/JvmSourceCompiler` is already the exact
  backend half the CLI's `-o out.class` uses (CLAUDE.md, `.kb/jvm-export.md`),
  so the missing piece is a class loader and a decision about what `main` means,
  not a new backend. Measure what fraction of the ~0.63 s survives when nothing
  is written to disk.
- **Attack the fixed compile cost directly** -- it is worth doing whether or not
  the default changes, because it is also what every `-o` build pays. Start by
  measuring where it goes; `LibraryDefunPruner` running at the END of the splice
  chain (`.kb/library-defun-pruning.md`) means the whole prelude is built and
  then thrown away every time.
- **Cache compiled output** keyed on source hash, so the second run of a script
  is the compiled one. Interacts with `.kb/emitted-output-determinism.md`, which
  already promises byte-identical output for identical input -- that promise is
  what makes a cache sound.
- **Compile only `defun` bodies**, keeping the top level interpreted. Smaller
  and it matches where the time actually is in script-shaped programs, but it
  needs the two representations to interoperate, which is `.todo/013`'s
  bidirectional-environment problem.
- **Do nothing to the default, and say so** in the docs, loudly, with these
  numbers. A legitimate outcome -- but then the docs must stop letting a reader
  assume the default path is the fast one.

Whatever is chosen, the interpreter stays: it is the REPL, it is `eval`, it is
the semantics the other three backends are pinned against.

## Decision (2026-08-25)

**The default stays the interpreter, and the docs now say so loudly**
(direction 5) -- recorded in full, with the measurements and the re-evaluation
triggers, in `.kb/default-run-path.md`. The two facts that decided it:

- **The ~0.63 s hypothesis is falsified.** Measured in process, the whole
  frontend (read, splice chain, macro expansion, pruner) costs **4 ms warm**;
  the backend emit ~140 ms warm. The fixed cost is class loading + JIT warm-up
  of the compiler itself on a cold JVM (268 ms + 613 ms cold), which the
  native binary's AOT already removed: its whole compile is ~0.16 s. There is
  no splice work to cache and no algorithmic target to attack.
- **Compile-by-default is impossible on the native binary and regressive on
  `java -jar`.** A closed-world native image cannot define classes at run time
  (GraalVM 25's `-H:RuntimeClassLoading` is experimental and interprets what
  it loads), and in-memory compile+run under `java -jar` makes `(print 'hi)`
  0.44 s -> 0.99 s while the loops go 6x faster -- exactly the trade the
  acceptance forbids. The source-hash cache is mechanically sound
  (`.kb/emitted-output-determinism.md`) but auto-switching engines between
  run 1 and run 2 turns every open interpreter/compile-path divergence
  (`.todo/384`, `.todo/446`, `.todo/434`, `.todo/444`) into a silent
  run-to-run behavior change; it unlocks when that backlog closes.

Delivered: `.kb/default-run-path.md` (decision record + triggers: GraalVM
Crema executing at compiled speed, the divergence backlog, `.todo/412`),
"Interpretation Speed" in `doc/*/getting-started/file-interpretation.md`, and
the execution-modes list in `doc/*/index.md` -- a reader can no longer assume
the default path is the fast one. `(print 'hi)` and every backend's output are
untouched (no code change).

## Acceptance

Deliberately open -- this item's first deliverable is a decision, recorded here,
backed by a measurement of where the ~0.63 s goes. Once decided:

- The four `.todo/517` benchmarks, run the way a user runs a script, land within
  2x of SBCL.
- `(print 'hi)` does not get slower.
- `ci-spec.yaml` and `ExamplesE2eTest` byte-identical on all four backends.
