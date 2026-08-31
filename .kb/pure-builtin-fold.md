# The pure-builtin literal fold (compile paths)

**Invariant: a PURE built-in whose every argument is a literal is evaluated by the
compiler, and the call is deleted — with the compiler's answer byte-identical to what
the backend would have computed at run time.** One curated table, one shared pass, one
differential harness; the one-off folds that came before it (`#S(...)` literals, the
ASDF pathname primitives, the literal byte specifier of `ldb`/`dpb`, the print
family's static text) are instances of the same rule and are listed under "what this
does not replace" below.

Owner: `am.ik.rontolisp.macro.PureBuiltinFolder`. Pinned by `PureBuiltinFolderTest`
plus the four-backend differential in `am.ik.rontolisp.macro.FoldDifferential`
(consumed by `LispEvaluatorTest`, `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest` in both its Preview 1 and its `--component` shape)
and the `pure-builtin-literal-fold` `ci-spec.yaml` case for the native binary.

## Why: reachability, not arithmetic

`(+ 1 2)` costs three instructions; deleting it saves three instructions. What it
actually buys is that **the reference is gone**, which is the currency the wasm tree
shaker and the JVM class shaker spend. One call to a generic runtime helper pins its
whole dispatch tree: `(princ <computed>)` pins the generic printer, whose integer arm
pins the whole bignum print chain plus the f64 renderer and the ratio accessors;
`(length x)` pins the sequence-length dispatch; `(concatenate 'string ...)` pins the
concatenate family.

The fold composes with the folds that were already there, and that composition is
where the bytes are: a folded argument is a LITERAL, so `WasmLiteralPrint` renders it
to static text and `format`'s literal lowering substitutes it instead of binding it to
a temporary. `(princ (* 6 7))` is therefore not "one multiply cheaper" than before —
it is the whole printer cheaper, and compiles to exactly the same module as
`(princ 42)`.

Measured at `--optimize` on 2026-08-07, both columns from the same tree with the one
`expandTopLevelDefinitions` line toggled, Preview 1 core / `--component` bytes:

| program | core before | core after | component before | component after |
| --- | --- | --- | --- | --- |
| `(princ 42)` (unchanged control) | 410 | 410 | 1,561 | 1,561 |
| `(princ (* 6 7))` | 5,740 | **410** | 6,913 | **1,561** |
| `(format t "~a~%" (* 6 7))` | 5,752 | **430** | 6,925 | **1,583** |
| `(princ (length "Hello World!"))` | 5,103 | **410** | 6,278 | **1,561** |
| `(princ (concatenate 'string "Hello" " " "World!"))` | 4,886 | **497** | 6,063 | **1,649** |

The control row is the point of the table: the folded spellings do not merely shrink,
they land on the floor the literal spelling already had — **byte for byte**, which is
what `WasmTreeShakerTest.aFoldedComputationCompilesToTheLiteralItReducesTo` asserts
(`(princ (* 6 7))` and `(princ 42)` are the same 410 bytes, not merely the same size).

## Where it runs, and why there

`LispMacroExpander.expandTopLevelDefinitions`, first statement — ahead of
`hoistLoadTimeValues`, because a folded `(load-time-value (+ 1 2))` becomes an atom
and the hoist then correctly declines to give it a slot. That is the ONE whole-program
pass both compilers already call, so the fold needs no registration in the CLI, the
playground, the corpus tree-shaker tests or the ASDF E2E harness — the same reasoning
that put the `load-time-value` hoist there (`.kb/compiler-macros.md`).

It therefore lives in `macro`, not `compiler`: `compiler` depends on `macro` and never
the reverse, so a pass the expander itself invokes cannot live in `compiler`.

**The interpreter does not fold**, and that is deliberate rather than an omission: it
has no reachability to win (there is no module to shake), and it is the REFERENCE the
harness measures the compile backends against. `--no-gc` does not fold either — it
does not go through `expandTopLevelDefinitions` at all, and its scalar numeric tower
is not the exact integer tower the table assumes.

## Who is the authority on the value

The fold evaluates in JAVA at compile time; the program would have evaluated on one of
four backends. **The pass is only correct where those two answers are byte-identical**,
so the deliverable is not the pass, it is the table plus the harness that decides what
may be in it.

`FoldDifferential.PROBES` holds one row per shape: the call as written (which folds)
and the same call with every argument behind a `%id` function parameter (which cannot).
Both run in one program and print with `prin1`, and the two lines must be the same
text. **An entry with no row does not ship** — `PureBuiltinFolderTest`
`everyTableEntryHasADifferentialRow` fails the build if the table grows without the
harness, and `everyProbeActuallyFolds` fails if a probe stopped folding, which is what
keeps a passing differential from being vacuous (two identical runtime calls agree
whether or not a fold exists).

### What is in the table, and the reason per group

- **Exact integer arithmetic** — `+ - * / 1+ 1- abs signum isqrt min max gcd lcm mod
  rem expt`. Every backend implements the integer tower exactly at any magnitude
  (`.kb/wasm-bignum.md`) and `java.math.BigInteger` is the same mathematics. `/` folds
  only when the quotient is an exact integer (see the ratio exclusion below).
- **Bitwise** — `logand logior logxor lognot ash integer-length logbitp`. Two's
  complement of an unbounded integer, which is what `BigInteger` and
  `.kb/integer-bitwise-fast-paths.md` both implement.
- **Numeric comparison and predicates** — `= /= < > <= >= zerop plusp minusp evenp
  oddp`, result `t`/`nil`.
- **Characters** — `char-code code-char char-upcase char-downcase char= char< char>
  char<= char>=`. A character is a Unicode CODE POINT on all four backends
  (`.kb/characters-code-points.md`), so these are code-point arithmetic. Case conversion
  is included over the FULL range, and that was CHECKED rather than assumed: a checksum
  of `char-upcase` and `char-downcase` over every code point in `[0, 0x10FFFF]` minus
  the surrogates is identical on the interpreter, the JVM, both WASM backends and plain
  `java.lang.Character` (the WASM one goes through the compressed table todo-267
  generated from those very methods). The same sweep over `string-upcase` /
  `string-downcase` agrees too — CL folds character by character, so no mapping changes
  a string's length. The first draft of this table restricted case conversion to ASCII
  on the assumption that `.todo/269` covered it; running the sweep is what showed that
  `.todo/269` is about the case-insensitive COMPARE operators (`char-equal`,
  `string-equal`, `alpha-char-p`), none of which is in this table.
- **String and list measurement** — `length char schar string= nth car first second
  third`. A string is indexed by code point everywhere.
- **String production** — `symbol-name princ-to-string prin1-to-string`. The
  FRESH-STRING producers (`string-upcase` / `string-downcase` / `concatenate 'string`
  / `subseq`) left the table on 2026-08-31 — see "The fresh-string producers left the
  table" below.
- **The packed literal table** — `coerce` and `make-array`, and ONLY into an
  `(unsigned-byte 8|16|32)` vector. `(coerce '(<literals>) '(vector (unsigned-byte
  32)))` is how every CL library spells a lookup table, and building it at run time
  costs **~11.8 bytes of wasm per element** (the cons list, then the fill) where the
  packed vector it becomes is 4. See the section below for why an array may be this
  fold's result when the identity rule excludes every other one.

The justification is written per GROUP rather than per entry on purpose: within a
group every entry rests on the same property (exact integer arithmetic, code-point
characters, code-point string indexing), and restating it per entry would hide the two
places where an entry deviates from its group -- `/`, which folds only an exact
quotient, and the case-conversion four, which stop at ASCII. Those two carry their own
sentence.


## The fresh-string producers left the table (2026-08-31, `.todo/596`)

`string-upcase`, `string-downcase`, `(concatenate 'string ...)` and `subseq` no longer
fold. Their compiled results are MUTABLE character vectors with identity
(`.kb/string-write-runtime.md`, "The remaining producers are flipped"), so a fold to
one shared literal would forge exactly the aliasing the flip provides —
`(let* ((s (string-upcase "abc")) (a s)) (setf (char s 0) #\x) (list s a))` must
answer `("xBC" "xBC")` on every backend, and a folded literal answered
`("xBC" "ABC")`. The identity rule above always excluded values with identity; these
four gained one, so the rule now excludes them. (The `subseq` fold had been forging it
since the subseq flip itself: `(subseq "lit" 0)` was folded to a shared literal while
`(copy-seq "lit")` — the same operation — answered a fresh mutable vector.)

**What it costs, measured 2026-08-31.** The reachability wins in the table at the top
are LOST exactly for a literal-argument producer call, and only there — a program
whose producer arguments are computed never folded. Minimal programs, `--optimize`
wasm / JVM class bytes, before -> after:
`(princ (concatenate 'string "Hello" " " "World!"))` 574 -> 9,959 / 2,633 -> 5,636;
`(print (string-upcase "abc"))` 579 -> 18,788 (the Unicode case-fold tables now ride
along) / 2,983 -> 6,007; `(print (subseq "abcdef" 1 3))` 578 -> 14,755 /
2,982 -> 13,356. The corpus barely moves — hello_world and pi_approx are
byte-identical, zlib +110 bytes — because real programs compute their arguments.
`WasmTreeShakerTest.aFoldedComputationCompilesToTheLiteralItReducesTo` traded its
concatenate row for a `symbol-name` one, and
`#anInterningProgramOffersPerEntryRangesRowsFallingWithTheirBytes` makes its runtime
name with `subseq` instead of `string-upcase` (whose case-fold tables would otherwise
drown the data-section bound the test is about).

**Re-evaluation trigger:** if these ever need to fold again, the fold's RESULT must
carry the mutable-fresh-per-evaluation property the packed-vector section describes —
i.e. the folded value would have to materialize as a fresh character vector per
evaluation at every use site, not as a shared literal. Nothing today wants that badly
enough to pay for it.

### When the harness finds a divergence: fix, do not route around

The rule this pass commits to, in order:

1. **If the divergence is a BUG on one side, fix it** and keep the entry. Both
   divergences the fold actually surfaced went this way in the same session -- the
   JVM's silent `sipush` truncation and `assert`'s lost diagnostic, both below.
2. **If the two answers are each defensible and the fix is a known open item, decline
   the shape and name the item.** Non-ASCII case conversion (`.todo/269`) and float
   arithmetic (unpinned contagion/edge semantics, see below) are here. Declining is the only interim answer that cannot be
   wrong, and the trigger is written down so the next visitor can widen it.
3. **Never make the fold "usually right".** A fold that prints a value differently from
   the runtime is worse than no fold, because the program now has two spellings of one
   value and neither site says so.

### What is deliberately OUT, with its re-evaluation trigger

- **Float ARITHMETIC** — the print half of the old exclusion is retired: since
  todo-431 every backend prints the same Schubfach shortest decimal
  (`.kb/format.md`, "The float printer"), so a float literal is accepted as an
  ARGUMENT and the print-family entries fold it (`(princ-to-string 1.5)` ->
  `"1.5"`; `WasmLiteralPrint` folds float print literals the same session). What
  stays out is folding float-COMPUTING entries: every arithmetic entry still
  declines a float itself (the integer guards), because the contagion rules, the
  zero divisor and the overflow-to-infinity cases are not pinned across the four
  backends. **Trigger:** pin those semantics with float rows in
  `FoldDifferential`, then widen the arithmetic entries.
- **Every RATIO** — as an argument and as a result, which is why `(/ 7 2)` declines and
  `(/ 100 5)` folds. The WASM ratio tier has **i32 components** (`.kb/wasm-bignum.md`),
  so a folded ratio literal is not representable at every magnitude the compiler can
  compute one. **Trigger:** widen the wasm ratio components.
- **The case-INSENSITIVE operators** — `char-equal`, `string-equal`, `alpha-char-p`
  (and `alphanumericp` above it). These really are ASCII-only on the WASM backends
  while the interpreter and the JVM are full-Unicode, which is `.todo/269`: folding
  them would pick one of two answers a live divergence already disagrees on.
  **Trigger:** `.todo/269`. (Case CONVERSION is a different set of operators and is
  in the table — see above.)
- **Any value with IDENTITY** — a cons, a general array, a hash table, an instance. A
  fold at two sites would hand out two objects where the program built one, and a fold
  of a sublist would alias storage the program can `rplacd`. This is enforced as a
  property of the RESULT (`isFoldableResult`), not as a per-entry rule, so
  `(cdr '(1 2 3))` and `(nth 0 '((1) (2)))` decline by construction while
  `(nth 1 '(a b c))` folds.
  A STRING result is IN only for producers whose runtime answer is itself an
  immutable value with no writable identity (`symbol-name`, `princ-to-string`,
  `prin1-to-string`). The premise this paragraph used to rest on — "a string literal
  materializes FRESH on each evaluation on both compile backends" — is measured FALSE
  today: a literal is ONE shared object on all four backends (`(eq (fs) (fs))` is `T`,
  `.kb/string-write-runtime.md`), and the compiled results of the fresh-string
  producers are MUTABLE character vectors with identity, so a fold of those to a
  shared literal forges aliasing. That is why they left the table (below). The
  interpreter still does not fold, for the original reason.
  A PACKED INTEGER VECTOR result is in for that same reason, and it is the reason
  rather than the type that decides: both compile backends allocate the array and fill
  it AT THE SITE (`JvmQuoteCompiler.compileLiteralIntVector` builds a new `long[]`;
  `WasmQuoteCompiler.compileIntVectorLiteral` allocates and copies its data segment
  in), so two evaluations of one folded table are two independently mutable vectors —
  pinned as `(eq a b)` = `nil` after mutating one, in the ci-spec case and in each
  backend's own test. A general `#(...)` array is NOT in: nothing bakes one freshly
  per evaluation and no measurement asked for it.
- **The `floor` family and every other multiple-value producer.** Folding
  `(floor 7 2)` to `3` would silently drop the secondary value a
  `multiple-value-bind` reads (`.kb/multiple-values.md`).
- **`(+)`, `(*)`, `(gcd)` and the other zero-argument n-ary calls.** Their identity
  values are worth nothing, and a one-element list whose head is a table name is
  exactly the shape a non-evaluated position can hand the walker by mistake. Declining
  them removes a class of accidents for free.
- **An unbounded result.** `(expt 2 1000000)` and `(ash 1 1000000)` decline at 4096
  bits, and a folded string at 4096 code points: a fold must not make the COMPILER do
  unbounded work, nor bake a megabyte of digits into the output. A packed table's
  ceiling is 65,536 ELEMENTS and is a different kind of bound — the elements are
  already spelled out in the source and the folded table is smaller than the call it
  replaces, so it bounds only the compiler's work.
- **A `make-array` with no `:initial-contents`.** The size is then the only thing
  known, and folding `(make-array 8192 :element-type '(unsigned-byte 8))` would bake
  8 KB of zeros in place of one `array.new_default`. `:initial-element`,
  `:fill-pointer`, `:adjustable`, `:displaced-to`, a rank other than 1 and a dimension
  that disagrees with the contents all decline the same way.
- **An out-of-range table element.** `(coerce '(256) '(vector (unsigned-byte 8)))`
  declines rather than folding to the masked 0: the fold is element-type EXACT, so a
  value that does not fit is the program's bug and the run-time builder's masking
  answer — the one the interpreter gives — is the one that stands. A non-integer
  element, an improper list and a `deftype` ALIAS designator decline too; the alias
  because the fold runs before the deftype registry is populated, and the run-time
  `%seq-int-vector` lowering resolves it anyway.

## The packed literal table (todo-319)

The one fold whose win is BYTES rather than reachability, and the one whose result is
an object. It rests on two other pieces, both of which had to be true first:

1. **`coerce` had to keep the element type.** `(coerce '(...) '(vector (unsigned-byte
   32)))` used to build a GENERAL vector on all four backends, so folding it to a
   packed literal would have invented a different value. That divergence was already
   written down as a re-evaluation trigger and is retired in the same pass
   (`.kb/concatenate-result-families.md`) — the fold is now a compile-time evaluation
   of exactly what the run-time `%seq-int-vector` builder produces.
2. **The wasm backend had to bake the literal as DATA.** A `LispIntVector` literal used
   to compile to `array.new_default` plus one `array.set` per element, ~12-17 bytes
   each — worse per element than the cons list it replaced. Past 16 elements the
   elements now go into the module's static data at the element width and the site is a
   copy loop over them (`.kb/packed-integer-vectors.md`).

An argument may be a `#(...)` vector or a packed vector as well as a quoted list, so
`literalValue` answers one for those two types too — chipz spells half its tables as
vector literals. That widening is about ARGUMENTS only: what may be BAKED is still
decided by `isFoldableResult`, and a general array is not in it.

**Did the fold change the interpreter? No — the `coerce` widening did, and on all four
backends at once.** The interpreter still folds nothing, so a folded table and the table
the interpreter builds are the same value by construction: `equalp` (pinned by the
ci-spec case's `fold-check-seq`, which uses `equalp` precisely because two arrays are
`equal` only when they are one object) and identically mutable. What DID move for every
backend, together, is that `(coerce seq '(vector (unsigned-byte N)))` now answers a
specialized vector instead of a general one — a semantic change with its own tests, made
first so that the fold has something exact to be a compile-time evaluation OF.

Measured at `--optimize=size`, a program that binds one table and prints one element
(the same probe the todo used), core Preview 1 bytes:

| table | before | after |
| --- | ---: | ---: |
| 3 × `(unsigned-byte 32)` | 11,843 | **5,437** |
| 256 × `(unsigned-byte 32)` | 14,809 | **6,482** |
| marginal, per element | 11.7 | **4.1** |

and on the `zlib` size-report rows (chipz, ~700 constant-table elements):
423,094 → 365,142 plain, 243,840 → 193,382 at `--optimize`, 191,872 → **161,976** at
`--optimize=size`, 196,613 → 166,656 as a component. More than the tables' own bytes,
because a packed table also stops boxing every element and the whole cons-list build
path shakes out with it.

## The three things that make it safe

### 1. Only an EVALUATED position folds

`(let ((max 3)) ...)` holds a cons whose head is a table name and whose argument is a
literal — and it is a binding, not a call. So does `(cond (max 3))`, `(do ((i 0))
(max 3))`, `(defstruct box (length 0))`, `(defun f (&optional (max 9)) ...)`,
`(case x ((min 2) ...))`. `PureBuiltinFolder.foldForm` classifies the positions of
every surface form that HAS non-evaluated ones and never folds in one; the case list
mirrors `UserMacroExpander.expandAllLocated`, which solves the identical problem for a
user macro call and is the reference for it.

The default — every argument is an expression — is right for a function call and for
every special form whose subforms are all expressions (`if`, `progn`, `and`, `block`,
`tagbody`, …), because the elements those do not evaluate are ATOMS, which no fold can
touch. **The two shapes that are not obvious:**

- **`cond` clauses and a `do` termination clause are LISTS OF FORMS, not forms.**
  `(cond (max 3))` tests the variable `max` and yields 3; read as a call it folds to
  `3` and the clause disappears. Both have their own branch.
- **A `setf`-family PLACE is not a call.** `setf`/`psetf`/`setq`/`psetq` fold their
  value positions only (odd indices), `incf`/`decf` their delta, `push`/`pushnew`
  their value; `pop`/`rotatef`/`shiftf`/`remf`/`with-slots`/`with-accessors` are
  skipped whole.

And one position that IS evaluated and still must not fold: **`assert`'s test form**.
`(assert (= 1 2))` reports "The assertion (= 1 2) failed." — the message quotes the
test's SOURCE TEXT, so folding it reports "The assertion NIL failed." and the whole
diagnostic is gone. Only `assert`'s format datum arguments fold, for the same reason
`check-type`'s place and type stay verbatim. Pinned by
`JvmLispCompilerTest.compileAndRunAssert`, which is how it was found.

`PureBuiltinFolderTest.aNonEvaluatedPositionIsNeverFolded` pins the list, and it pins
it by OBJECT IDENTITY: the pass must hand back the very list it was given, which is
both the correctness assertion and the cons-identity rule.

### 2. A user definition of the name wins

`shadowedOperators` blocks every table name the program defines: a top-level
`defun`/`defmethod`/`defgeneric`/`defmacro`/`define-compiler-macro`, and a
`flet`/`labels`/`macrolet` local anywhere in the program. That is deliberately
**coarser than either existing answer** to "is this name still the built-in" —
`compiler.ShadowedBuiltins` covers a `defmethod` on the compile path,
`LispEvaluator.defineDispatcher` covers the interpreter, and neither covers a plain
`(defun length ...)`; a local blocks the fold program-wide rather than for its lexical
extent. Being more conservative than every consumer is always sound, and no real
program defines `+`. Names are matched by both the exact spelling and the
package-stripped member name, so a library's `(defun cl-user::length ...)` blocks
`length` too.

**A program that mentions `*print-case*` blocks `princ-to-string` / `prin1-to-string`**
(2026-08-15, `.todo/041`): `nil` and `t` render as SYMBOLS, so
`(princ-to-string nil)` is `"nil"` under a `:downcase` binding and the two entries stop
being constant. The block is on the OPERATORS rather than on the two literal types --
one line instead of a type carve-out, and a program that binds the printer variable is
not the one measuring bytes. Nothing else in the table renders a symbol: `symbol-name`
and `string` answer the NAME, which `*print-case*` does not touch
(`.kb/pretty-printer.md`).

**The plain `(defun length ...)` is DIAGNOSED, not honoured (decided 2026-08-09,
`compiler.ClRedefinitionWarnings`)**: the fold declines it, but the expression
dispatchers still compile the standard operator at the call site, so the definition
runs on the interpreter and not on the compile paths. All THREE dispatchers (wasm-GC,
JVM, `--no-gc`) now arm a flag before their operator switch (`redefinesClFunction` = the
name is a top-level defun AND `PackageRegistry.isClFunctionName`) and disarm it in the
`default` arm -- the ordinary call path, which DOES resolve the defun -- then report
through `CompileWarnings`, once per name, at the first call site's position. On
`--no-gc` the armed set is the program's DEFINED names, not its reachable index: a
`(defun sqrt ...)` is never enqueued there precisely because every `(sqrt ...)` site
compiles to the built-in. Armed/disarmed rather than pre-computed
because "does this backend intercept this name" is only knowable at the dispatch: a `cl`
name that falls through stays silent, which is what keeps `wait.lisp`'s `(defun sleep
...)` and `compile-runtime.lisp`'s `(defun compile ...)` -- deliberate Lisp-source
definitions of standard functions -- from warning in every program that splices them.
**Why not honour it**: CLHS 11.1.2.1.2 leaves it undefined (SBCL refuses outright with a
package lock), and the interpreter's honoured set is an accident of which names
`LispEvaluator.evalCons` expands before consulting the environment -- it honours `car`
but not `first`, `length` but not `nth` -- so "make the compilers agree" means freezing
that accident into a hand-kept list on three dispatchers, while honouring EVERY `cl`
function collides with this fold, with inlining and with the dispatch gate. The defect
was the silence, and that is what the warning removes. Cross-backend pin:
`compiler/ClRedefinitionWarningsTest` (interpreter answers the definition, both compile
backends warn, a non-intercepted name and an uncalled definition stay quiet) plus
`JvmLispCompilerTest.compileAndRunUsesTheStandardOperatorWhenAProgramRedefinesACommonLispFunction`
(the compiled program computes the STANDARD answer while `#'length` still names the
definition). If package locks ever land, this message is what the lock reports.

`(setf (symbol-function <computed>) ...)` can install anything under any name, so it
stands the WHOLE pass down; a literal `(setf (symbol-function 'max) ...)` blocks only
that name.

**Under `--dynamic` nothing folds at all** — every name resolves at run time, so the
compile path may not decide what a call means. Same bail as the funcall-dispatch gate
(`.kb/dynamic-late-binding.md`).

### 3. A fold that would signal declines

`(length 5)` is a runtime error the program may never reach on a cold branch, and
`(char "ab" 9)` is one the compile backends do not even bounds-check (`.todo/186`), so
folding it would invent an answer the program would not have got. Every table entry
returns `null` rather than throwing, and `foldCall` swallows a `RuntimeException` from
one — `expandFormat`'s established fallback shape.

## What the fold found by making a path reachable

A general fold routes values into emitters that had only ever seen them computed, and
two of those emitters were wrong. Both were fixed in the same pass rather than routed
around, because a fold that has to avoid a shape is a fold nobody can reason about:

- **`JvmEmitHelper.emitIntConst` truncated any constant outside the signed 16-bit
  `sipush` range**, silently. A folded `(code-char 128512)` is a `#\U+1F600` literal
  and a JVM character is an `int[]{cp}`, so pushing 0x1F600 produced -2560 and
  `Character.toString` threw at run time. Now `ldc`/`ldc_w`; the pool-free sibling
  throws loudly. Full record: `.kb/jvm-method-size-limits.md`.
- **`assert`'s failure message quotes its test form**, so folding the test lost the
  diagnostic — see above.

Two existing tests also had their premise dissolved by the fold and were rewritten to
hide their input from it rather than weakened:
`formatUserFunctionDirectiveSignalsWhenTheCompileNeverSawTheDirective` built its
control string with `(concatenate 'string "~" "/name/")`, which the fold now reduces
to a literal directive the compile CAN see (so the call succeeds instead of
signalling — an improvement, but not what that test is for), and
`WasmTreeShakerTest.orphanedCaseFoldTableSegmentsAreDropped` case-folded a literal
character, so the case-fold tables shook out after all. The latter now asserts all
three states: no fold at all, a runtime fold that keeps the tables, and a literal fold
that drops them.

## Source positions

A fold rewrites a form, so every cons on the path from the top-level form down to the
fold is rebuilt, and a rebuilt cons is a new key in `SourceProvenance`'s identity table.
Left alone that would blank out the `file:line:column` of most of a real program, since
literal arithmetic is everywhere. `SourceProvenance.inherit(original, rewritten)` — added
with this pass — gives the rewritten cons the position of the one it replaces, and every
rebuild in the folder goes through it. The unchanged case still goes through
`LispCons.rebuiltList` and hands back the original (`.kb/source-positions.md`).

`inherit` is the general answer for any REWRITING pass, not just this one; the identity
rule alone only covers passes that change nothing.

## What this does not replace (yet)

The one-off folds are all instances of the same rule and were left where they are,
because each does something this pass does not:

- `StructLiteralFolder` (`#S(...)`) needs the `ClosRegistry` and runs per top-level form.
- `CompileTimePathnameFolder` (the four ASDF/UIOP pathname primitives) needs the
  compile-time system registry, reads the filesystem, and lives in `cli`.
- `PackageResolver`'s literal package designator runs before the compilers see anything.
- The literal byte specifier of `ldb`/`dpb`/`mask-field` folds a `(byte s p)` argument
  that is not the whole call.
- `WasmLiteralPrint` folds an argument to static TEXT rather than to a value, which is
  a wasm emission concern.

`define-compiler-macro` + `load-time-value` (`.kb/compiler-macros.md`) stays the
USER-facing seam for the same idea; a library can hand-write what this table does not
cover.

**The identified next step, deliberately not taken here:** a branch whose test folded
to a constant still compiles both arms — `(if (stringp "x") A B)` keeps `B`. That is a
different rule ("a branch with a constant test has one reachable arm") with its own
`--dynamic` and source-position questions, and it is what would make the type
predicates (`stringp`, `null`, `consp`, …) worth adding to the table; they buy nothing
on their own, which is why they are not in it.
