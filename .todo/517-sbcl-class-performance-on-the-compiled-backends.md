# 517. SBCL-class performance on the compiled backends (parent)

Difficulty: High (parent item; each child is sized on its own)

Children: `.todo/518`, `.todo/519`, `.todo/520`, `.todo/521`, `.todo/522`
(518, 519, 520 and 521 are closed).
Related, already open: `.todo/412` (the JVM boxes every integer and has no fusion).

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

## Target

The four rows above, in their TOP-LEVEL spelling, within **2x of SBCL** on at
least one compiled backend, and no row where a compiled backend is slower than
the interpreter. The top-level spelling is the acceptance shape on purpose: it
is how scripts are written, and it is what the note wrote.

## Measured, understood, not yet filed

Residuals that are real but small next to the four above; file them once the
children land and the numbers are re-taken against a fixed baseline:

- JVM `aref` on a simple vector costs ~50 ns/access against SBCL's ~12 ns
  (`_aref1` is a generic helper call; row 3 minus row 2).
- wasm `random` is ~4x the JVM's (row 2: 1.71 vs 0.40 in `defun`).
- Boxed generic arithmetic in every loop head and accumulator (`_cmpb`,
  `_add`, `Long.valueOf` per iteration) is the whole of the remaining 1.9x-3.4x
  on the JVM. That is `.todo/412`, already open and already scoped.
