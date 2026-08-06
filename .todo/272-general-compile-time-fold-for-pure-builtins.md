# A general compile-time fold for pure builtins over literal arguments

Difficulty: High

The print family now folds a LITERAL argument to static text
(`WasmLiteralPrint`, `.kb/optimize-dead-code-elimination.md`), and `format` no
longer hides its constants behind a temp (`.kb/format.md`). Both are instances of
one rule the codebase does not state anywhere:

> If an operator is a PURE builtin and every argument is a literal, the call's
> value is known at compile time.

`(+ 1 2)`, `(length "abc")`, `(princ-to-string 42)`, `(char-upcase #\a)`,
`(string-upcase "x")`, `(concatenate 'string "a" "b")`, `(nth 1 '(a b c))`,
`(expt 2 10)` are all evaluated at run time today, on every backend.

## Why it is worth doing beyond the obvious

The size win is not the arithmetic, it is **reachability**. That is what made the
print fold worth ~4.2 KB: one call to a generic runtime helper pins its whole
dispatch tree. `(length "abc")` pins the sequence-length dispatch; a folded
`(concatenate 'string ...)` frees the concatenate family. A fold is a reference
DELETED, which is exactly the currency the wasm tree shaker and the JVM pruner
spend.

## What already exists (do not redo)

- `StructLiteralFolder` (root package) -- `#S(...)` literals.
- `CompileTimePathnameFolder` (cli) -- the four ASDF/UIOP pathname primitives.
- `PackageResolver` -- a literal package designator folds to a quoted keyword
  before the compilers see it.
- the literal byte-specifier fold in `ldb`/`dpb`/`mask-field`
  (`.kb/integer-bitwise-fast-paths.md`).
- `define-compiler-macro` + `load-time-value` (`.kb/compiler-macros.md`) -- the
  USER-facing seam for exactly this, which is why a library can already do it by
  hand and the compiler cannot.
- `WasmLiteralPrint` -- the print/write family, wasm only.

Each is a one-off. The point of this item is the pass they should all have been
instances of.

## The hard part: who is the authority on the value

The fold evaluates in JAVA at compile time; the program would have evaluated on
one of four backends. **The pass is only correct where those two answers are
byte-identical**, and they are not always: `WasmLiteralPrint` excludes FLOAT
literals precisely because `_print_f64` and `LispDouble.print()` disagree at
large magnitudes (`.todo/046`). So the deliverable is not the pass, it is:

1. a curated per-operator table with a written justification per entry, not a
   "looks pure" heuristic;
2. a differential harness -- every table entry folded vs. the same call with its
   arguments hidden behind a function parameter, run on all four backends
   (`WasmLispCompilerIntegrationTest.aFoldedLiteralPrintsWhatTheRuntimePrinterWouldHave`
   is the shape, generalized); an entry with no row does not ship;
3. a decision on where a divergence found this way gets FIXED rather than routed
   around -- a fold that silently prints a value differently from the runtime is
   worse than no fold.

## Constraints the pass must respect

- **A user may define a builtin name.** `ShadowedBuiltins` (compile path) and
  `LispEvaluator.defineDispatcher` (interpreter) already answer "is this name
  still the builtin"; the fold must ask the same question and must not answer it
  independently.
- **`--dynamic` means any name resolves at run time** -- fold nothing there, the
  same way the funcall-dispatch gate bails.
- **A fold that would SIGNAL must decline, not fail the compile.** `(length 5)`
  is a runtime error the program may never reach on a cold branch;
  `expandFormat`'s `UnsupportedOperationException` fallback is the established
  shape.
- **Cons identity** -- an AST pass must not rebuild conses it did not change, or
  source positions are lost (`.kb/source-positions.md`).
- **Emitted-output determinism** -- the table's iteration order must not reach
  output (`.kb/emitted-output-determinism.md`).

## Where it lives

`macro` (so the interpreter, both compilers and `--no-gc` share it) or
`compiler` (backend-shared, backend-free) -- note `compiler -> macro`, never the
reverse, so a pass `LispMacroExpander` itself needs cannot live in `compiler`.
Decide by whether expansion needs it, and record the reason.

## Non-goals

- Partial evaluation of user functions, inlining, or a general effect analysis.
- Folding anything whose value is an object with identity (an array, an instance):
  a fold duplicated at two sites would hand out two objects where the program
  built one. `formatArgExprs.isSelfEvaluatingLiteral` already draws that line.
