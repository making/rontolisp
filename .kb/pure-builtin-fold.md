# The pure-builtin literal fold (compile paths)

Invariant: a PURE built-in whose every argument is a literal is evaluated by the compiler and
the call deleted -- with the compiler's answer byte-identical to what the backend would have
computed at run time. One curated table, one shared pass, one differential harness.

Owner: `am.ik.rontolisp.macro.PureBuiltinFolder`. Pinned by `PureBuiltinFolderTest`, the
four-backend differential `am.ik.rontolisp.macro.FoldDifferential` (consumed by
`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` in both Preview 1
and `--component` shapes), and the `pure-builtin-literal-fold` ci-spec case.

## Why: reachability, not arithmetic

Deleting the call removes the REFERENCE, which is the currency the wasm tree shaker and the JVM
class shaker spend: one call to a generic helper pins its whole dispatch tree (`(princ
<computed>)` pins the generic printer -> bignum print chain, f64 renderer, ratio accessors;
`length` pins the sequence-length dispatch; `concatenate 'string` pins the concatenate family).
It also composes: a folded argument is a LITERAL, so `WasmLiteralPrint` renders it to static
text and `format`'s literal lowering substitutes it. `(princ (* 6 7))` compiles to exactly the
same module as `(princ 42)`, byte for byte
(`WasmTreeShakerTest.aFoldedComputationCompilesToTheLiteralItReducesTo`).

## Where it runs

`LispMacroExpander.expandTopLevelDefinitions`, FIRST statement -- ahead of
`hoistLoadTimeValues`, so a folded `(load-time-value (+ 1 2))` becomes an atom and the hoist
correctly declines to give it a slot. That is the one whole-program pass both compilers already
call, so no registration in the CLI, playground, corpus tree-shaker tests or ASDF E2E harness is
needed. It lives in `macro`, not `compiler` (`compiler` depends on `macro`, never the reverse).

The interpreter does NOT fold: it has no reachability to win and it is the REFERENCE the harness
measures the compile backends against. `--no-gc` does not fold either (it never goes through
`expandTopLevelDefinitions`, and its scalar numeric tower is not the exact integer tower the
table assumes).

## The harness decides what may be in the table

The fold evaluates in JAVA at compile time; the program would have evaluated on one of four
backends, so the pass is only correct where the two answers are byte-identical.
`FoldDifferential.PROBES` holds one row per shape: the call as written (folds) and the same call
with every argument behind a `%id` function parameter (cannot fold). Both run in one program and
print with `prin1`; the two lines must match. An entry with no row does not ship --
`PureBuiltinFolderTest.everyTableEntryHasADifferentialRow` fails the build if the table grows
without the harness, and `everyProbeActuallyFolds` fails if a probe stopped folding (which is
what keeps a passing differential from being vacuous).

On a divergence, in order: (1) if it is a BUG on one side, fix it and keep the entry; (2) if both
answers are defensible and the fix is a known open item, decline the shape and name the item;
(3) never make the fold "usually right" -- a fold that prints differently from the runtime is
worse than no fold.

## What is in the table

- **Exact integer arithmetic**: `+ - * / 1+ 1- abs signum isqrt min max gcd lcm mod rem expt`.
  Every backend implements the integer tower exactly at any magnitude (`.kb/wasm-bignum.md`) and
  `BigInteger` is the same mathematics. `/` folds only when the quotient is an exact integer.
- **Bitwise**: `logand logior logxor lognot ash integer-length logbitp` (two's complement of an
  unbounded integer; `.kb/integer-bitwise-fast-paths.md`).
- **Numeric comparison/predicates**: `= /= < > <= >= zerop plusp minusp evenp oddp`.
- **Characters**: `char-code code-char char-upcase char-downcase char= char< char> char<= char>=`
  -- a character is a Unicode CODE POINT on all four backends
  (`.kb/characters-code-points.md`), so these are code-point arithmetic. Case CONVERSION is
  included over the FULL range: a checksum of `char-upcase`/`char-downcase` over every code point
  in `[0, 0x10FFFF]` minus surrogates is identical on the interpreter, JVM, both WASM backends
  and `java.lang.Character`, and the same sweep over `string-upcase`/`string-downcase` agrees
  (CL folds character by character, so no mapping changes a string's length).
- **String/list measurement**: `length char schar string= nth car first second third` (a string
  is indexed by code point everywhere).
- **String production**: `symbol-name` and the expander's internal `%princ-piece` /
  `%prin1-piece` fold to plain literals; the FRESH-STRING producers
  (`string-upcase`/`string-downcase`/`concatenate 'string`/`subseq`/`princ-to-string`/
  `prin1-to-string`) fold to a `(%str-fresh "...")` constant (below).
- **The packed literal table**: `coerce` and `make-array`, and ONLY into an
  `(unsigned-byte 8|16|32)` vector -- how every CL library spells a lookup table, ~11.8 bytes of
  wasm per element at run time where the packed vector is 4.

Justification is per GROUP: within a group every entry rests on the same property, and the two
deviations (`/`'s exact quotient, and the case-insensitive operators being OUT) carry their own
sentences.

## The fresh-string producers fold to a per-evaluation copy

`string-upcase`, `string-downcase`, `(concatenate 'string ...)` and `subseq` of literal
arguments still fold -- the VALUE is computed by the compiler -- but their compiled results are
MUTABLE character vectors with identity (`.kb/string-write-runtime.md`), so one shared literal
would forge aliasing: `(let* ((s (string-upcase "abc")) (a s)) (setf (char s 0) #\x) (list s a))`
must answer `("xBC" "xBC")` on every backend, and two evaluations of one fold must not be `eq`.

Mechanism: those entries return a FOLD-FRESH value (`LispString.foldFresh`) and `foldedLiteral`
spells it `(%str-fresh "...")`; the backends compile that as the interned literal plus ONE
mutable-copy wrap (`_toMutStr` / `_to_mut_str`). Three integration points:

- `literalValue` reads a `(%str-fresh "...")` form as the string it wraps, so nested folds still
  reduce in one pass (`(length (concatenate 'string "ab" "cd"))` is `4`).
- `MutableStringProducers` counts `%str-fresh` as a producer, so a program whose producer NAMES
  were all folded away still gates the wrap in (and, on the JVM, the array runtime).
- `WasmLiteralPrint.rendered` reads the TEXT out of a `(%str-fresh ...)` argument, so the print
  side goes static and the generic printer stays shakeable; the caller still compiles the
  `%str-fresh` form for the returned object.

Removing the four entries instead would pay the WHOLE runtime the fold keeps out (the Unicode
case-fold tables alone are ~9.5 KB); fold+copy pays only the wrap chain (~0.9 KB of wasm).
`%str-fresh` is also the general Lisp-level name for the mutable-result wrap (the first-class
`#'concatenate` wrapper's `%string-concat` reduce, and the build arm of a program-written
`(coerce seq 'string)`); the interpreter binds it as a copy for the wrapper bodies it evaluates.

Re-evaluation trigger: if a backend ever compiles `%str-fresh` as the bare literal, the forgery
returns. Pins: `PureBuiltinFolderTest.aFreshStringProducerFoldsToAStrFreshConstantAndNotASharedLiteral`,
the `fold-fresh` rows of the ci-spec case, and
`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest.compileALiteralArgumentProducerCallAnswersAFreshMutableStringPerEvaluation`.

## Deliberately OUT, with re-evaluation triggers

- **Float ARITHMETIC.** A float literal is accepted as an ARGUMENT and the print-family entries
  fold it (`(princ-to-string 1.5)` -> `"1.5"`), since every backend prints the same Schubfach
  shortest decimal (`.kb/format.md`). Float-COMPUTING entries still decline (integer guards):
  contagion rules, zero divisor and overflow-to-infinity are not pinned across the four
  backends. Trigger: pin those with float rows in `FoldDifferential`.
- **Every RATIO**, as argument and as result -- `(/ 7 2)` declines, `(/ 100 5)` folds. The WASM
  ratio tier has i32 components (`.kb/wasm-bignum.md`). Trigger: widen those components.
- **Case-INSENSITIVE operators** -- `char-equal`, `string-equal`, `alpha-char-p`,
  `alphanumericp`: really ASCII-only on WASM while the interpreter and JVM are full-Unicode, so
  folding would pick one of two answers a live divergence disagrees on. (Case CONVERSION is a
  different set and is IN.)
- **Any value with IDENTITY** -- cons, general array, hash table, instance: a fold at two sites
  would hand out two objects where the program built one, and a fold of a sublist would alias
  storage the program can `rplacd`. Enforced as a property of the RESULT (`isFoldableResult`),
  not per entry, so `(cdr '(1 2 3))` and `(nth 0 '((1) (2)))` decline by construction while
  `(nth 1 '(a b c))` folds. A STRING result folds as a PLAIN literal only for producers whose
  runtime answer is itself immutable (`symbol-name`, `%princ-piece`, `%prin1-piece`); a string
  literal is ONE shared object on all four backends. A PACKED INTEGER VECTOR result is IN because
  both compile backends allocate and fill AT THE SITE
  (`JvmQuoteCompiler.compileLiteralIntVector` builds a new `long[]`;
  `WasmQuoteCompiler.compileIntVectorLiteral` allocates and copies its data segment in), so two
  evaluations are two independently mutable vectors -- pinned as `(eq a b)` = `nil` after
  mutating one. A general `#(...)` array is NOT in.
- **The `floor` family and every multiple-value producer**: folding `(floor 7 2)` to `3` drops
  the secondary value (`.kb/multiple-values.md`).
- **`(+)`, `(*)`, `(gcd)` and other zero-argument n-ary calls**: their identity values are worth
  nothing, and a one-element list headed by a table name is exactly what a non-evaluated position
  can hand the walker by mistake.
- **An unbounded result**: `(expt 2 1000000)` and `(ash 1 1000000)` decline at 4096 bits, a
  folded string at 4096 code points. A packed table's ceiling is 65,536 ELEMENTS (a different
  kind of bound -- the elements are already in the source, so it bounds only compiler work).
- **A `make-array` with no `:initial-contents`** (folding `(make-array 8192 :element-type
  '(unsigned-byte 8))` would bake 8 KB of zeros in place of one `array.new_default`);
  `:initial-element`, `:fill-pointer`, `:adjustable`, `:displaced-to`, rank != 1 and a dimension
  disagreeing with the contents all decline.
- **An out-of-range table element**: `(coerce '(256) '(vector (unsigned-byte 8)))` declines
  rather than folding to the masked 0 -- the fold is element-type EXACT and the run-time
  builder's masking answer stands. A non-integer element, an improper list and a `deftype` ALIAS
  designator decline too (the alias because the fold runs before the deftype registry is
  populated; the run-time `%seq-int-vector` lowering resolves it anyway).

## The packed literal table

The one fold whose win is BYTES rather than reachability, and the one whose result is an object.
It rests on two prerequisites: `coerce` had to KEEP the element type (it used to build a general
vector on all four backends, so the fold is now a compile-time evaluation of exactly what the
run-time `%seq-int-vector` builder produces, `.kb/concatenate-result-families.md`), and the wasm
backend had to bake the literal as DATA (past 16 elements the elements go into static data at
element width with a copy loop at the site, `.kb/packed-integer-vectors.md`; previously
`array.new_default` plus one `array.set` per element, ~12-17 bytes each).

An ARGUMENT may be a `#(...)` vector or a packed vector as well as a quoted list, so
`literalValue` answers one for those two types too (chipz spells half its tables as vector
literals). What may be BAKED is still decided by `isFoldableResult`.

The interpreter still folds nothing, so a folded table and the interpreter's table are the same
value by construction: `equalp` (the ci-spec `fold-check-seq` row uses `equalp` precisely because
two arrays are `equal` only when they are one object) and identically mutable.

## The three things that make it safe

### 1. Only an EVALUATED position folds

`PureBuiltinFolder.foldForm` classifies the positions of every surface form that HAS
non-evaluated ones and never folds in one; the case list mirrors
`UserMacroExpander.expandAllLocated`. The default (every argument is an expression) is right for
a function call and for special forms whose subforms are all expressions (`if`, `progn`, `and`,
`block`, `tagbody`, ...), since what they do not evaluate are ATOMS.

Non-obvious shapes: `(let ((max 3)) ...)`, `(cond (max 3))`, `(do ((i 0)) (max 3))`,
`(defstruct box (length 0))`, `(defun f (&optional (max 9)) ...)`, `(case x ((min 2) ...))`.

- `cond` clauses and a `do` termination clause are LISTS OF FORMS, not forms -- `(cond (max 3))`
  tests the variable `max`; read as a call it folds to `3` and the clause disappears. Both have
  their own branch.
- A `setf`-family PLACE is not a call: `setf`/`psetf`/`setq`/`psetq` fold value positions only
  (odd indices), `incf`/`decf` their delta, `push`/`pushnew` their value;
  `pop`/`rotatef`/`shiftf`/`remf`/`with-slots`/`with-accessors` are skipped whole.
- Trap: `assert`'s TEST form is evaluated and still must not fold -- the failure message quotes
  the test's SOURCE TEXT, so folding turns "The assertion (= 1 2) failed." into "The assertion
  NIL failed." Only `assert`'s format datum arguments fold, as `check-type`'s place and type stay
  verbatim. Pinned by `JvmLispCompilerTest.compileAndRunAssert`.

`PureBuiltinFolderTest.aNonEvaluatedPositionIsNeverFolded` pins the list by OBJECT IDENTITY: the
pass must hand back the very list it was given.

### 2. A user definition of the name wins

`shadowedOperators` blocks every table name the program defines: a top-level
`defun`/`defmethod`/`defgeneric`/`defmacro`/`define-compiler-macro`, and a
`flet`/`labels`/`macrolet` local ANYWHERE in the program (program-wide, not for its lexical
extent). Deliberately coarser than `compiler.ShadowedBuiltins` (compile path) or
`LispEvaluator.defineDispatcher` (interpreter), neither of which covers a plain
`(defun length ...)`. Names match by exact spelling and by package-stripped member name, so
`(defun cl-user::length ...)` blocks `length`.

A program that mentions `*print-case*` blocks `princ-to-string` / `prin1-to-string` (and their
`%princ-piece` / `%prin1-piece` pieces): `nil` and `t` render as SYMBOLS, so
`(princ-to-string nil)` is `"nil"` under a `:downcase` binding. The block is on the OPERATORS,
not the two literal types. Nothing else in the table renders a symbol -- `symbol-name` and
`string` answer the NAME, which `*print-case*` does not touch (`.kb/pretty-printer.md`).

A plain `(defun length ...)` is DIAGNOSED, not honoured (`compiler.ClRedefinitionWarnings`): the
fold declines it, but the expression dispatchers still compile the standard operator at the call
site, so the definition runs on the interpreter and not on the compile paths. All THREE
dispatchers (wasm-GC, JVM, `--no-gc`) arm a flag before their operator switch
(`redefinesClFunction` = the name is a top-level defun AND `PackageRegistry.isClFunctionName`)
and disarm it in the `default` arm -- the ordinary call path, which DOES resolve the defun --
then report through `CompileWarnings`, once per name, at the first call site's position. On
`--no-gc` the armed set is the program's DEFINED names, not its reachable index. Armed/disarmed
rather than pre-computed because "does this backend intercept this name" is only knowable at the
dispatch, which is what keeps `wait.lisp`'s `(defun sleep ...)` and `compile-runtime.lisp`'s
`(defun compile ...)` from warning in every program that splices them. Not honoured because
CLHS 11.1.2.1.2 leaves it undefined (SBCL refuses with a package lock) and the interpreter's
honoured set is an accident of which names `LispEvaluator.evalCons` expands before consulting the
environment (it honours `car` but not `first`, `length` but not `nth`). Pins:
`compiler/ClRedefinitionWarningsTest` and
`JvmLispCompilerTest.compileAndRunUsesTheStandardOperatorWhenAProgramRedefinesACommonLispFunction`.

`(setf (symbol-function <computed>) ...)` stands the WHOLE pass down; a literal
`(setf (symbol-function 'max) ...)` blocks only that name. Under `--dynamic` nothing folds at all
(`.kb/dynamic-late-binding.md`).

### 3. A fold that would signal declines

`(length 5)` is a runtime error the program may never reach on a cold branch, and `(char "ab" 9)`
one the compile backends do not even bounds-check, so folding would invent an answer. Every table
entry returns `null` rather than throwing, and `foldCall` swallows a `RuntimeException` from one
(`expandFormat`'s established fallback shape).

## Source positions

A fold rewrites a form, so every cons from the top-level form down to the fold is rebuilt, and a
rebuilt cons is a new key in `SourceProvenance`'s identity table.
`SourceProvenance.inherit(original, rewritten)` gives the rewritten cons the position of the one
it replaces, and every rebuild in the folder goes through it; the unchanged case goes through
`LispCons.rebuiltList` and hands back the original (`.kb/source-positions.md`). `inherit` is the
general answer for any REWRITING pass.

## What this does not replace

Each one-off fold does something this pass does not: `StructLiteralFolder` (`#S(...)`) needs the
`ClosRegistry` and runs per top-level form; `CompileTimePathnameFolder` (the four ASDF/UIOP
pathname primitives) needs the compile-time system registry, reads the filesystem and lives in
`cli`; `PackageResolver`'s literal package designator runs before the compilers see anything; the
literal byte specifier of `ldb`/`dpb`/`mask-field` folds an argument, not the whole call;
`WasmLiteralPrint` folds an argument to static TEXT rather than to a value.
`define-compiler-macro` + `load-time-value` (`.kb/compiler-macros.md`) stays the USER-facing seam.

Identified next step, not taken: a branch whose test folded to a constant still compiles both
arms -- `(if (stringp "x") A B)` keeps `B`. That is a different rule with its own `--dynamic` and
source-position questions, and it is what would make the type predicates (`stringp`, `null`,
`consp`, ...) worth adding; they buy nothing on their own.
