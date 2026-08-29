# 563. A type declaration buys the JVM backend nothing, so the float rows go back to SBCL

Difficulty: High (declarations are erased to `nil` before any backend sees them,
so this is a front-end carrier plus a routing change in two emitters -- and it
has to decide what a FALSE declaration does on a backend that has no `ref.cast`
to trap on)

`bench-report/programs/` carries `(declaim (optimize (speed 3) (safety 0)
(debug 0)))` and type declarations since 2026-08-29. Every implementation that
reads declarations moved; rontolisp's did not, because `declare`/`declaim`
expand to `nil` (`.kb/declarations-type-checks.md`) everywhere except the
rank-1 array accessor todo-320 gave the wasm-GC backends.

Measured 2026-08-29, 64-core Linux dev box, best of three, ms:

| | fib | mandelbrot | matmul | sieve |
| --- | ---: | ---: | ---: | ---: |
| sbcl, undeclared | 130 | 542 | 325 | 208 |
| sbcl, declared | 57 | 30 | 20 | 91 |
| ecl, declared | 159 | 36 | 22 | 126 |
| rontolisp (jvm), either | 81 | 113 | 80 | 537 |

`fib`, `mandelbrot` and `matmul` were the three rows the JVM backend led. It
still runs exactly the code it ran before; SBCL and ECL now unbox theirs.

## What is already in the tree

- `compiler/DeclaredArrayTypes` reads `(declare (type spec var...))` out of a
  body head BEFORE the expander erases it, and maps a specifier to an array
  REPRESENTATION -- rank-1 only, and nothing scalar.
- `JvmLispCompiler.hasDoubleLiteral` routes the unboxed IEEE path on a
  SYNTACTIC guess: a double literal somewhere under an operand
  (`.kb/jvm-double-arithmetic.md`). An expression over declared `double-float`
  variables with no literal in it is the case that guess cannot see. Where it
  DOES route -- `mandelbrot`'s inner loop has literals and is already on the
  unboxed path -- the value still lands in an `Object` local at every `setq`,
  so the remaining gap to SBCL's 30 ms is the round trip a declaration could
  remove by keeping the variable in a `double` slot. Measure that first: it is
  the premise of the whole item.
- The integer side is `.kb/jvm-int-fusion.md` (trees of `Long`s) and
  `.kb/jvm-typed-loops.md` (the `dotimes`-over-packed-array subset). Both infer;
  neither is told.

So the missing piece is a SCALAR declaration carrier and the routing that reads
it, not new arithmetic.

## Directions

1. A `DeclaredScalarTypes` beside `DeclaredArrayTypes`: collect `double-float` /
   `single-float` / `fixnum` / `(integer lo hi)` bindings per binding form
   (`defun` lambda list, `let`/`let*`, `do`/`dotimes` heads), and thread the
   scope through `JvmExprCompiler` the way the array types already reach the
   `aref` sites. A declared `double-float` local then routes the emitters
   `hasDoubleLiteral` routes today and stays in a `double` JVM local across the
   loop; a declared fixnum one becomes a typed leaf for the fusion.
2. Decide the false-declaration policy and write it down. wasm-GC traps
   (todo-320's decision); the JVM double path coerces every operand through
   `_dbl` and always answers something defined. Coercion keeps the four backends
   answering identically and costs a check per operand; a trap matches wasm and
   diverges from the interpreter, which ignores declarations and cannot trap.
   One of the two, in `.kb/declarations-type-checks.md`, before any emitter
   changes.
3. The interpreter stays a no-op reader: it has no representation to choose. It
   is the oracle every declared program must still match.

## Acceptance

- `bench-report/programs/mandelbrot.lisp` and `matmul.lisp` measurably faster
  under `-o Bench.class` WITH their declarations than with them stripped, and
  `bench-report/results/` regenerated to show it.
- A `ci-spec.yaml` case whose declared and undeclared spellings of the same
  arithmetic answer identically on all four backends, plus a
  `JvmLispCompilerTest` pair at both optimize levels.
- `.kb/declarations-type-checks.md` and `.kb/jvm-double-arithmetic.md` updated
  together with the emitters.

Related: `.todo/035-type-system.md` (the declaration system as parsed no-ops),
`.todo/517-sbcl-class-performance-on-the-compiled-backends` (the same gap
measured from the other end).
