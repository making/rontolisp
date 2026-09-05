# The pure-builtin literal fold (compile paths)

Invariant: a PURE built-in whose every argument is a literal is evaluated by the compiler and the
call deleted — with the compiler's answer byte-identical to what the backend would have computed at
run time. One curated table (`macro.PureBuiltinFolder`), one shared pass, one differential harness.

The win is REACHABILITY, not arithmetic: deleting the call removes the REFERENCE the wasm tree
shaker and JVM class shaker spend (one `(princ <computed>)` pins the generic printer, bignum print
chain, f64 renderer, ratio accessors). It composes — a folded argument is a LITERAL, so
`WasmLiteralPrint` renders it to static text and `format`'s literal lowering substitutes it;
`(princ (* 6 7))` compiles byte-identically to `(princ 42)`
(`WasmTreeShakerTest.aFoldedComputationCompilesToTheLiteralItReducesTo`).

## Where it runs
`LispMacroExpander.expandTopLevelDefinitions`, FIRST statement — ahead of `hoistLoadTimeValues`, so
a folded `(load-time-value (+ 1 2))` becomes an atom and the hoist declines to give it a slot. It
lives in `macro`, not `compiler` (`compiler` depends on `macro`, never the reverse). The
interpreter does NOT fold (it is the REFERENCE the harness measures against); `--no-gc` does not
either (never goes through `expandTopLevelDefinitions`, and its scalar numeric tower is not the
exact integer tower the table assumes).

## The harness decides what may be in the table
`FoldDifferential.PROBES` holds one row per shape: the call as written (folds) and the same call
with every argument behind a `%id` function parameter (cannot fold); both run in one program,
print with `prin1`, and the two lines must match.
`PureBuiltinFolderTest.everyTableEntryHasADifferentialRow` fails the build if the table grows
without the harness; `everyProbeActuallyFolds` fails if a probe stopped folding, which is what
keeps a passing differential from being vacuous. On a divergence: fix the buggy side and keep the
entry, or decline the shape naming the open item — **never make the fold "usually right"**.
Consumed by `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` (both
Preview 1 and `--component`) and ci-spec `pure-builtin-literal-fold`.

## In the table
- Exact integer arithmetic `+ - * / 1+ 1- abs signum isqrt min max gcd lcm mod rem expt` (`/` only
  when the quotient is an exact integer); bitwise `logand logior logxor lognot ash integer-length
  logbitp`; comparison/predicates `= /= < > <= >= zerop plusp minusp evenp oddp`.
- Characters `char-code code-char char-upcase char-downcase char= char< char> char<= char>=` — a
  character is a Unicode CODE POINT on all four backends (`.kb/characters-code-points.md`); case
  CONVERSION is checksum-verified over `[0, 0x10FFFF]` minus surrogates against
  `java.lang.Character`, and the same sweep over `string-upcase`/`string-downcase` agrees.
- Measurement `length char schar string= nth car first second third`.
- String production: `symbol-name`, `%princ-piece`, `%prin1-piece` fold to plain literals; the
  FRESH-STRING producers fold to `(%str-fresh "...")` (below).
- The packed literal table: `coerce` and `make-array`, ONLY into an `(unsigned-byte 8|16|32)`
  vector — ~11.8 bytes of wasm per element at run time where the packed vector is 4.

Justification is per GROUP; the two deviations (`/`'s exact quotient, case-insensitive operators
being OUT) carry their own sentences.

## The fresh-string producers fold to a per-evaluation copy
`string-upcase`, `string-downcase`, `(concatenate 'string ...)` and `subseq` fold their VALUE, but
compiled results are MUTABLE character vectors with identity (`.kb/string-write-runtime.md`), so
one shared literal would forge aliasing and two evaluations of one fold must not be `eq`. They
return `LispString.foldFresh` and `foldedLiteral` spells `(%str-fresh "...")`, which the backends
compile as the interned literal plus ONE mutable-copy wrap (`_toMutStr` / `_to_mut_str`). Three
integration points: `literalValue` reads through it so nested folds still reduce in one pass;
`MutableStringProducers` counts `%str-fresh` as a producer; `WasmLiteralPrint.rendered` reads the
TEXT out of it so the print side stays static.

Removing the four entries would pay the WHOLE runtime the fold keeps out (~9.5 KB of Unicode
case-fold tables) where fold+copy pays ~0.9 KB. `%str-fresh` is also the general Lisp-level name
for the mutable-result wrap. **Trigger**: if a backend ever compiles `%str-fresh` as the bare
literal, the forgery returns. Pins:
`PureBuiltinFolderTest.aFreshStringProducerFoldsToAStrFreshConstantAndNotASharedLiteral`, the
`fold-fresh` ci-spec rows, `Jvm`/`Wasm`'s
`compileALiteralArgumentProducerCallAnswersAFreshMutableStringPerEvaluation`.

## Deliberately OUT
- **Float ARITHMETIC** — a float literal is fine as an ARGUMENT and the print family folds it
  (every backend prints the same Schubfach shortest decimal, `.kb/format.md`), but contagion, zero
  divisor and overflow-to-infinity are not pinned. Trigger: float rows in `FoldDifferential`.
- **Every RATIO**, argument and result: `(/ 7 2)` declines, `(/ 100 5)` folds — the WASM ratio tier
  has i32 components (`.kb/wasm-bignum.md`). Trigger: widen those components.
- **Case-INSENSITIVE operators** (`char-equal`, `string-equal`, `alpha-char-p`, `alphanumericp`):
  ASCII-only on WASM, full-Unicode elsewhere.
- **Any value with IDENTITY** (cons, general array, hash table, instance) — enforced as a property
  of the RESULT (`isFoldableResult`), not per entry, so `(cdr '(1 2 3))` declines by construction
  while `(nth 1 '(a b c))` folds. A STRING result folds as a PLAIN literal only for producers whose
  runtime answer is itself immutable. A PACKED INTEGER VECTOR is IN because both compile backends
  allocate and fill AT THE SITE (`JvmQuoteCompiler.compileLiteralIntVector`,
  `WasmQuoteCompiler.compileIntVectorLiteral`); a general `#(...)` array is NOT.
- **The `floor` family and every multiple-value producer** (folding drops the secondary value,
  `.kb/multiple-values.md`); zero-argument n-ary calls `(+)`, `(*)`, `(gcd)`.
- **Unbounded results**: `expt`/`ash` decline at 4096 bits, a folded string at 4096 code points, a
  packed table at 65,536 ELEMENTS.
- **`make-array` with no `:initial-contents`**; `:initial-element`, `:fill-pointer`, `:adjustable`,
  `:displaced-to`, rank != 1 and a mismatched dimension all decline.
- **An out-of-range table element**: `(coerce '(256) '(vector (unsigned-byte 8)))` declines rather
  than folding to the masked 0. Non-integer elements, improper lists and `deftype` ALIAS
  designators decline too (the alias because the fold runs before the deftype registry is
  populated; the run-time `%seq-int-vector` lowering resolves it).

## The packed literal table
The one fold whose win is BYTES and whose result is an object. Prerequisites: `coerce` had to KEEP
the element type (`.kb/concatenate-result-families.md`) and the wasm backend had to bake the
literal as DATA past 16 elements (`.kb/packed-integer-vectors.md`). An ARGUMENT may be a `#(...)`
or packed vector as well as a quoted list. The interpreter folds nothing, so a folded table and the
interpreter's are `equalp` (ci-spec `fold-check-seq` uses `equalp` precisely because two arrays are
`equal` only when they are one object) and identically mutable.

## The three things that make it safe
**1. Only an EVALUATED position folds.** `PureBuiltinFolder.foldForm` classifies the positions of
every surface form that HAS non-evaluated ones; the case list mirrors
`UserMacroExpander.expandAllLocated`. Non-obvious shapes: `(let ((max 3)) ...)`, `(cond (max 3))`,
`(do ((i 0)) (max 3))`, `(defstruct box (length 0))`, `(defun f (&optional (max 9)) ...)`,
`(case x ((min 2) ...))`. `cond` clauses and a `do` termination clause are LISTS OF FORMS, not
forms, and have their own branch. A `setf`-family PLACE is not a call: `setf`/`psetf`/`setq`/`psetq`
fold odd indices only, `incf`/`decf` their delta, `push`/`pushnew` their value;
`pop`/`rotatef`/`shiftf`/`remf`/`with-slots`/`with-accessors` are skipped whole. **Trap**:
`assert`'s TEST is evaluated and still must not fold — the message quotes the test's SOURCE TEXT,
so folding turns "The assertion (= 1 2) failed." into "The assertion NIL failed."
(`JvmLispCompilerTest.compileAndRunAssert`). `aNonEvaluatedPositionIsNeverFolded` pins the list by
OBJECT IDENTITY: the pass must hand back the very list it was given.

**2. A user definition of the name wins.** `shadowedOperators` blocks every table name the program
defines: top-level `defun`/`defmethod`/`defgeneric`/`defmacro`/`define-compiler-macro`, and a
`flet`/`labels`/`macrolet` local ANYWHERE (program-wide, not lexical). Deliberately coarser than
`compiler.ShadowedBuiltins` or `LispEvaluator.defineDispatcher`. Names match by exact spelling and
by package-stripped member name. A program mentioning `*print-case*` blocks
`princ-to-string`/`prin1-to-string` and their pieces (`nil`/`t` render as SYMBOLS); the block is on
the OPERATORS, not the literal types — `symbol-name` and `string` answer the NAME, untouched by
`*print-case*` (`.kb/pretty-printer.md`). `(setf (symbol-function <computed>) ...)` stands the
WHOLE pass down; a literal one blocks that name. Under `--dynamic` nothing folds
(`.kb/dynamic-late-binding.md`).

A plain `(defun length ...)` is DIAGNOSED, not honoured (`compiler.ClRedefinitionWarnings`): all
THREE dispatchers arm a flag before their operator switch (`redefinesClFunction` = top-level defun
AND `PackageRegistry.isClFunctionName`) and disarm it in the `default` arm, then report through
`CompileWarnings` once per name at the first call site. On `--no-gc` the armed set is the program's
DEFINED names, not its reachable index. Armed/disarmed rather than pre-computed because "does this
backend intercept this name" is only knowable at the dispatch — what keeps `wait.lisp`'s
`(defun sleep ...)` and `compile-runtime.lisp`'s `(defun compile ...)` from warning everywhere. Not
honoured because CLHS 11.1.2.1.2 leaves it undefined. Pins: `compiler/ClRedefinitionWarningsTest`,
`JvmLispCompilerTest.compileAndRunUsesTheStandardOperatorWhenAProgramRedefinesACommonLispFunction`.

**3. A fold that would signal declines.** `(length 5)` is a runtime error a cold branch may never
reach and `(char "ab" 9)` one the compile backends do not bounds-check, so folding would invent an
answer. Every entry returns `null` rather than throwing, and `foldCall` swallows a
`RuntimeException`.

## Source positions and neighbours
A fold rewrites a form, so every cons from the top-level form down is rebuilt and becomes a new key
in `SourceProvenance`'s identity table. Every rebuild goes through
`SourceProvenance.inherit(original, rewritten)`; the unchanged case goes through
`LispCons.rebuiltList` and hands back the original (`.kb/source-positions.md`). `inherit` is the
general answer for any REWRITING pass.

Not replaced by this pass: `StructLiteralFolder` (needs `ClosRegistry`, per top-level form),
`CompileTimePathnameFolder` (needs the compile-time system registry, reads the filesystem, lives in
`cli`), `PackageResolver`, the literal byte specifier of `ldb`/`dpb`/`mask-field`,
`WasmLiteralPrint`. `define-compiler-macro` + `load-time-value` (`.kb/compiler-macros.md`) stays the
USER-facing seam.

Identified next step, not taken: a branch whose test folded to a constant still compiles both arms
— `(if (stringp "x") A B)` keeps `B`. That is what would make the type predicates worth adding;
they buy nothing on their own.
