# 517. SBCL-class performance on the compiled backends (parent)

Difficulty: High (parent item; each child is sized on its own)

Children: `.todo/518`, `.todo/519`, `.todo/520`, `.todo/521`, `.todo/522` (all
closed), plus `.todo/527` and `.todo/528`, filed 2026-08-26 out of the residual
section below and both OPEN.
Related: `.todo/412` (the JVM boxes every integer and has no fusion) -- closed
2026-08-26 by JVM integer expression-tree fusion (`.kb/jvm-int-fusion.md`).

**Every child having landed, the four rows were re-taken on one fixed baseline
-- see "The fixed baseline" below. Two of the four are inside the 2x target and
two are not, and the two that are not are exactly `.todo/527` and `.todo/528`.**

## Where this came from

A benchmark note claiming SBCL does 100,000,000 random `nth` accesses into a
1,000,000-element list in 0.69 s -- i.e. that a linked list beats a vector at
random access -- and asking rontolisp to match it.

**The premise is a dead-code artifact and must not become an acceptance
criterion.** SBCL deletes the `nth` call outright: its value is unused and `nth`
is flushable, so only `random`'s side effect survives. Measured here
(SBCL 2.2.9, this machine, 2026-08-25):

| program (100,000,000 iterations, `(speed 3) (safety 0)`) | real |
| --- | --- |
| `(loop repeat N do (nth (random 1000000) lst))` | 0.693 s |
| `(loop repeat N do (random 1000000))` -- no `nth` at all | 0.693 s |

Identical to the millisecond, both `0 bytes consed`. The note's number is the
cost of `random`, and its conclusion ("lists are faster than arrays") does not
survive accumulating the result. So the real question is the honest one: how far
is rontolisp from SBCL on the four operations that benchmark reaches for, when
the results are actually used.

## The measurement (2026-08-25, this machine, SBCL 2.2.9 / wasmtime 47.0.3 / GraalVM)

Every rontolisp result is wall clock including runtime startup (JVM ~0.06 s,
wasmtime ~0.02 s, `java -jar` the interpreter ~0.43 s), best of three, output
verified equal to SBCL's. Sources: `size-report/programs/` sibling shapes; the
spike files are reproduced in each child item. **Each benchmark was run twice --
once written at top level the way the note writes it, once with the loop moved
inside a `defun`** -- because that difference turned out to be the single
biggest factor.

| benchmark | SBCL | interp | JVM top level | JVM in `defun` | wasm top level | wasm in `defun` |
| --- | --- | --- | --- | --- | --- | --- |
| `(loop for i from 1 to 100000000 sum i)` | 0.21 | ~72 (est.) | 2.80 | **0.39** | 2.50 | 0.83 |
| 10^7 x `(+ s (random 1000000))` | 0.18 | 8.10 | 0.78 | **0.40** | 1.94 | 1.71 |
| 10^7 x `(+ s (aref a (random 1000000)))`, `a` 10^6 wide | 0.30 | -- | 1.73 | **1.02** | 2.43 | 2.44 |
| 10^9 `cdr` steps, `(nth 999 lst)` x 10^6 | 1.18 | ~7 | 15.99 | 16.03 | 1.88 | **1.91** |

Ratios against SBCL, best backend per row: **1.9x, 2.2x, 3.4x, 1.6x**. That is
the finding: on the operations the note tests, rontolisp's compiled output is
already within a small factor of SBCL *when the program is shaped the way the
compiler is good at*. Everything between that and the 4x-75x a user actually
sees is structural, and each piece is separately fixable:

- **Top level costs up to 7x** (`loop sum`: 2.80 -> 0.39 on the JVM, 2.50 -> 0.83
  on wasm) because every assignment inside a top-level form -- including
  macro-generated lexicals like `__loop_acc0` -- is mirrored into the eval
  runtime's global alist. Two independent defects: `.todo/518` (the mirror fires
  for lexicals) and `.todo/519` (the eval runtime is switched on for programs
  that never call `eval`).

  **`.todo/518` is closed.** The mirror now fires only for a name with a global
  backing store, and wasm's unboxed-local trade no longer declines a top-level
  `let`. Re-measured on the same machine, TOP-LEVEL spelling, after it landed:

  | benchmark | was (JVM / wasm) | now (JVM / wasm) | the `defun` spelling now |
  | --- | --- | --- | --- |
  | `loop ... sum` | 2.80 / 2.50 | **0.37 / 0.83** | 0.38 / 0.79 |
  | 10^7 x `random` | 0.78 / 1.94 | 0.54 / 1.88 | 0.43 / 1.65 |
  | 10^7 x `aref` | 1.73 / 2.43 | 1.59 / 2.30 | 1.00 / 2.71 |
  | 10^9 `cdr` | 15.99 / 1.88 | 15.49 / 1.92 | 16.38 / 1.78 |

  The `loop sum` row is now AT the `defun` spelling on both backends; the rest of
  the top-level/`defun` gap that remains (`aref` on the JVM) is `.todo/519`.

  **`.todo/519` is closed too.** The injected `map*`/`every`/`some` wrapper
  bodies are `(apply f ...)`, so every class called an `_apply` it had not
  declared, and the post-compile self-check answered that by forcing the eval
  runtime on -- for programs with no `eval` in them. They are now injected only
  for a program that can reach one. Re-measured on the same machine, same
  spellings, after it landed:

  | benchmark | top level (JVM / wasm) | `defun` (JVM / wasm) |
  | --- | --- | --- |
  | `loop ... sum` | **0.36 / 0.81** | 0.35 / 0.83 |
  | 10^7 x `random` | **0.45 / 1.84** | 0.37 / 1.61 |
  | 10^7 x `aref` | **1.22 / 2.36** | 0.91 / 2.33 |
  | 10^9 `cdr` | 15.41 / 1.92 | 15.39 / 1.79 |

  Artifact size is what moved most: the four top-level classes are now 6.4 KB /
  6.6 KB / 12.0 KB / 6.9 KB where every one of them used to be ~34 KB, and
  `(defvar *s* 0) (setq *s* 1) (print *s*)` is 4.0 KB against 34.0 KB. The JVM
  top-level/`defun` gap that is left (`aref`, 1.22 vs 0.91) is boxed generic
  arithmetic, i.e. `.todo/412`.
- **The JVM is 8.5x slower than wasm on the same list walk** (15.99 vs 1.88 for
  10^9 `cdr`s) -- and slower than rontolisp's own tree-walking interpreter --
  because HotSpot refuses to compile the loop at all. `.todo/520`.

  **`.todo/520` is closed.** HotSpot only enters an on-stack-replacement
  compilation at a backedge whose operand stack is EMPTY, and a method entered
  once has no other route in -- so every loop head that sat under the enclosing
  expression's pending operands ran interpreted forever. `nth`'s inline `cdr`
  walk moved into its own `_nthcdr` method, and every other emitter that writes
  a backedge now spills the pending operands around it
  (`.kb/jvm-osr-backedges.md`). Re-measured on the same machine, TOP-LEVEL
  spelling, after it landed:

  | benchmark | was (JVM / wasm) | now (JVM / wasm) |
  | --- | --- | --- |
  | 10^9 `cdr` | 15.41 / 1.92 | **3.61 / 1.89** |

  4.3x on the row, 1.9x off the wasm backend and 3.1x off SBCL, with
  `-XX:+PrintCompilation` showing `_nthcdr` and the enclosing `_top$0` both
  compiled at tier 4 and no `COMPILE SKIPPED: stack not empty at OSR entry
  point` anywhere. The corpus went from 3333 hostile backedges across 92 example
  programs to zero, pinned by `JvmOsrBackedgeCorpusTest` and an `ExamplesE2eTest`
  assertion on every JVM leg. No compiled backend is slower than the interpreter
  on any row now.
- **wasm's `loop for ... to ...` is 2x its own `dotimes`** (0.83 vs 0.41) because
  the counted-loop lowering only covers `dotimes`. `.todo/521`.

  **`.todo/521` is closed.** The counted-loop treatment now recognizes the
  induction variable in the `let` + `while` sandwich `loop`'s numeric head lowers
  to, instead of only a literal-bound `dotimes`
  (`WasmCountedLoopCompiler`, `.kb/wasm-counted-loops.md`). Re-measured on the
  same machine after it landed:

  | benchmark | was (top level / `defun`) | now (top level / `defun`) |
  | --- | --- | --- |
  | wasm `loop ... sum` | 0.85 / 0.78 | **0.35 / 0.32** |

  That is the row's whole gap: the idiomatic CL spelling now matches its own
  `dotimes` (0.30 in a `defun`) and sits at 1.7x SBCL's 0.219 s -- inside the
  target. A computed limit (`for i from 0 below (length v)`) still takes the
  boxed lowering, and the reason it is a separate trade is recorded in that
  `.kb` file's re-evaluation triggers. The JVM is unmoved (0.38 either way),
  which is `.todo/412`'s question, exactly as this item said it would be.
- **The default `rontolisp app.lisp` is the interpreter**, 20x-200x off the
  compiled backends. `.todo/522`.

  **`.todo/522` is closed -- decided, not implemented away.** The default
  STAYS the interpreter, by decision recorded in `.kb/default-run-path.md`.
  The measurement falsified the cacheable-splice hypothesis (the whole
  frontend costs 4 ms warm; the ~0.63 s is the compiler's own class-load/JIT
  warm-up on a cold JVM, already absent in the native binary whose whole
  compile is ~0.16 s), and compile-by-default is impossible on the native
  binary (a closed-world image cannot define classes at run time) and makes
  `(print 'hi)` 2.2x slower under `java -jar`. The docs now say the flagless
  path is the slow one and route CPU-bound programs to `-o`
  (`doc/*/getting-started/file-interpretation.md`). Re-evaluation triggers --
  GraalVM Crema executing loaded classes at compiled speed, the
  interpreter/compile-path divergence backlog closing (unlocks the
  source-hash cache), `.todo/412` -- are in the `.kb` file. This parent's
  "run the way a user runs a script" target is therefore carried by the
  compiled backends' own rows plus `.todo/412`, not by a default switch.

## The fixed baseline (2026-08-26, this machine, every child landed)

SBCL 2.2.9 / GraalVM 25.0.4 / wasmtime 47.0.3, best of three, wall clock
including runtime startup, output verified equal to SBCL's. Sources and commands:
`.todo/517-sbcl-class-performance-on-the-compiled-backends/README.md`.

| benchmark | SBCL | interp | JVM top level | JVM in `defun` | wasm top level | wasm in `defun` |
| --- | --- | --- | --- | --- | --- | --- |
| `(loop for i from 1 to 100000000 sum i)` | 0.21 | 56.85 | **0.21** | 0.23 | 0.31 | 0.31 |
| 10^7 x `(+ s (random 1000000))` | 0.16 | 7.70 | **0.46** | 0.38 | 1.86 | 1.60 |
| 10^7 x `(+ s (aref a (random 1000000)))`, `a` 10^6 wide | 0.26 | 12.45 | **1.22** | 0.95 | 2.32 | 2.36 |
| 10^9 `cdr` steps, `(nth 999 lst)` x 10^6 | 1.16 | 3.99 | 3.35 | 3.31 | **1.87** | 1.82 |

The `--component` backend, `defun` spelling, for the record: `loop sum` 0.35,
`random` 2.70, `aref` 3.70, `nth` 1.89.

Ratios against SBCL in the acceptance shape (TOP-LEVEL, best compiled backend):

| row | ratio | verdict |
| --- | --- | --- |
| `loop sum` | **1.0x** (JVM 0.21) | inside the target |
| 10^9 `cdr` | **1.6x** (wasm 1.87) | inside the target |
| `random` | 2.9x (JVM 0.46) | **`.todo/528`** |
| `aref` | 4.7x (JVM 1.22) | **`.todo/527`** |

Against the first measurement in this file: `loop sum` 2.80 -> 0.21 (13x),
10^9 `cdr` 15.99 -> 3.35 on the JVM and 1.88 -> 1.87 on wasm, `random` 0.78 ->
0.46, `aref` 1.73 -> 1.22. No compiled backend is slower than the interpreter on
any row, and the interpreter itself moved (`loop sum` ~72 est. -> 56.85).

## Target

The four rows above, in their TOP-LEVEL spelling, within **2x of SBCL** on at
least one compiled backend, and no row where a compiled backend is slower than
the interpreter. The top-level spelling is the acceptance shape on purpose: it
is how scripts are written, and it is what the note wrote.

## Measured, understood, now filed

The residuals this section carried are filed, with the numbers re-taken against
the fixed baseline above:

- **`aref` -- `.todo/527`.** Filed here as "`_aref1` is a generic helper call".
  Re-measuring falsified that: on a 1,000-element array the JVM's `aref` costs
  **1.7 ns** and beats SBCL, and the cost rises with the array size
  (1.7 / 15.6 / 34.9 / **55.5** ns at 10^3 / 10^4 / 10^5 / 10^6 elements) where
  SBCL's stays flat. The helper inlines; what costs 55 ns is that a general
  array is an `ArrayList` of boxed `Long`s, so a random read is two dependent
  cold hops through 24 MB. Not the call -- the representation.
- **`random` -- `.todo/528`.** The wasm draw is 177 ns against the JVM's 24 and
  SBCL's 7.5, because `WasmRandomCompiler` calls the WASI `random_get` host
  function ONCE PER DRAW; a `perf` profile puts 12% of cycles in wasm code and
  the rest in wasmtime's export-name hashing, per-call `Vec` allocation and
  ChaCha20. The item covers the JVM half too (`Math.random()` is a shared
  `AtomicLong` CAS), because the row's acceptance is measured against the best
  compiled backend and that is now the JVM.
- **Boxed generic arithmetic** in every loop head and accumulator was the whole
  of the remaining 1.9x-3.4x on the JVM. That was `.todo/412`, closed 2026-08-26
  (`.kb/jvm-int-fusion.md`); the `loop sum` row now sits AT SBCL.

One residual is measured and still unfiled, deliberately -- it does not block
any row's acceptance, because the JVM carries both rows it touches:

- **wasm's general `aref` is size-INdependent**, ~61 ns/access on a
  1,000-element array and ~76 ns on a 1,000,000-element one. That is dispatch
  overhead, not layout, so it is a different defect from `.todo/527` and wants a
  call-shape fix. File it if wasm ever has to carry the `aref` row.
