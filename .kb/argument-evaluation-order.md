# Argument evaluation order is LEFT TO RIGHT on every backend

**Invariant: the argument forms of any call, and the element forms of `list` (and of
everything lowering onto it -- backquote with unquotes, `make-array :initial-contents`), are
evaluated left to right on the interpreter, the JVM and both WASM backends.**

- A cons chain is LINKED from the last element back, so compiling each element as it is
  consumed runs side effects right to left while still producing the correct value.
- `compiler/ArgumentOrder.isOrderIndependent` decides whether an argument may be reordered;
  `Jvm/WasmListCompiler` pre-evaluate every argument that cannot into a temp, in source
  order, then link from the temps.
- Order-independent = self-evaluating literals, `nil`/`t`/keywords, `(quote DATUM)`. **A bare
  variable reference is NOT** -- an earlier argument may `setq` it.
- `rontolisp:await`-promoted reads were always correct (`WasmAwaitNormalizer` hoists them
  into sequenced bindings).

## Sibling: read-modify-write macros evaluate a place's subforms ONCE

**`incf`/`decf`, `push`/`pushnew`/`pop`, `rotatef`/`shiftf` evaluate each subform of their
place exactly once**, on every backend, via `LispMacroExpander.PlaceTemps` (each CALL
argument of the place bound to a temp in a `let*` in front of the expansion; temp names from
`freshObjVar`, `.kb/clos.md`).

- A symbol, literal, `(quote ...)` or `#'...` subform stays written out.
- **`the`/`values`/`apply`/`ldb`/`mask-field` places hoist nothing** -- their non-atomic
  argument is read STRUCTURALLY by the `setf` expanders.

## Sibling: a setf-function place evaluates its arguments before the value

**`(setf (name arg...) value)` evaluates the arguments left to right, then the value, then
looks the function up (CLHS 5.1.2.9), on every backend**, though the call passes the value
first. `LispMacroExpander.setfFunctionCall` builds both arms of `expandSetf`'s default
branch (a `%setf-` writer a definition makes -- `defun (setf ...)`, `defmethod (setf ...)`,
a CLOS `:accessor`, the prelude's `bit`/`sbit`/`get` -- and the late-bound `#'(setf name)`).

- No temporaries where the order is unobservable: every subform a constant or a read (a
  variable, `#'name`), a constant value, or (a writer a definition makes) every argument a
  constant. So `(setf (acc o) v)` / `(setf (acc o) 1)` are the bare call they always were.
- Otherwise every non-constant argument goes to a `let*` temp ahead of the value; a lone
  variable on either side is not enough (the other side may assign it, directly or through a
  closure). The late-bound arm binds the value too, so an undefined function signals after
  both. `incf`/`push` hand their `PlaceTemps` temps over as variables, so a non-constant new
  value binds them once more.
- Lite: a writer a definition makes is still looked up before the value on the interpreter,
  so a place whose own subforms redefine or `fmakunbound` it differs from SBCL (the compile
  paths bind it directly anyway); when every subform is a read, an undefined late-bound
  function is reported ahead of an unbound variable among them.

Measured 2026-10-07: `(setf (eo-u (f "a")) (f "b"))` / a CLOS accessor / `bit` / `get` /
`defmethod (setf ...)` were SBCL 2.2.9 `ab`, interpreter, JVM, P1 and component `ba`; a value
form assigning the argument variable redirected the write; `(setf (undefined (f "a"))
(f "b"))` ran neither before signalling. Pinned by `SetfFunctionNameFixture.EVALUATION_ORDER`
(`aSetfFunctionPlaceEvaluatesItsArgumentsBeforeTheValue` in the three backend suites) and
`LispMacroExpanderTest#aSetfFunctionPlaceBindsItsArgumentsOnlyWhereTheOrderIsObservable`.

## Sibling: a `gethash` place evaluates key, table, default, then the value

**`(setf (gethash key table [default]) value)` and a `gethash` read evaluate their operands
left to right on every backend**, the place's default included though a write never stores
it. Until 2026-10-07 the interpreter and the JVM never evaluated a `setf` default
(`LispMacroExpander.expandSetf` lowered the place to `(%puthash key table value)` and dropped
it), and P1 and the component evaluated `%puthash`'s value before its table and a read's
default before its table (`(setf (gethash (f "a") (f "b" h)) (f "c" 2))` was SBCL 2.2.9 and
interpreter/JVM `abc`, P1/component `acb`; the read `(gethash (f "a") (f "b" h) (f "x" 0))`
was `abx` and, on P1/component, `axb`).

- `expandSetf`: a constant or a read default is dropped, as before; any other default leads the
  value, `(%puthash key table (progn default value))`, so no temporary is bound.
- `WasmHashTableCompiler.compilePut` / `compileGet`: the table is evaluated into a temporary
  ahead of the value / default, and its type check follows them (a non-table signals after the
  value ran, as in SBCL), unless the order is unobservable -- the later operand a constant, or
  both a variable or constant (`isOrderFreeAfterTable`). Those sites emit what they did
  before; the JVM, being stack-based, was already in order.
- `incf`/`push` through such a place bind the default once with `PlaceTemps`, so it already ran.

Pinned by `GethashPlaceOrderFixture.EVALUATION_ORDER` (`aGethashPlaceEvaluatesItsSubformsLeftToRightIncludingTheDefault`
in the three backend suites; the fixture also covers a variable the value or default
reassigns, and a non-table with a traced value) and
`LispMacroExpanderTest#aGethashPlaceKeepsAnEffectfulDefaultAheadOfTheValue`.

## An operation applies after its operands

**Invariant: an operation applies -- and signals -- only once every argument is evaluated,
and an inner operation applies before its outer one's later arguments run; nothing
evaluated later may run before an earlier operation signals, exit before it, or store
into what it reads.** The interpreter does this by construction (CLHS 3.1.2.1.2.3). The
compiled fast paths evaluated in orders of their own until 2026-10-07: the unboxed float
paths converted (`_dbl`, `_as_f64`) each operand before the next ran, the JVM reported an
inner float-literal operation under the outer operator, integer fusion evaluated every
leaf before applying anything, and the two-argument `log` of a complex-capable program
took the number's logarithm before evaluating the base. So `(+ a (progn (princ "z") 1.5))`
lost its output, `(block nil (+ (* 2 a) (return 5)))` returned instead of signalling, and
`(+ (aref v 0) (progn (setf (aref v 0) 99) 1))` answered 100.

SBCL 2.2.9 (measured 2026-10-07) agrees for every binary operation, every inner operation
and every exit, and an earlier operation's condition wins over a later argument's.
The generic n-ary fold applied each step as it went until the same day: `(/ a 0 (f))`, and
`+ - *` wherever fusion declined (`--optimize=size`, a complex literal), signalled before
`(f)` ran. Its steps now wait like the float site's conversions (`FloatFold.waiting`), and a
float site's exact prefix (`.kb/jvm-double-arithmetic.md`, "The exact prefix") is that same
fold.
Compiled n-ary arithmetic is its one difference: `(+ 1.5 a (f))` is source-transformed to
`(+ (+ 1.5 a) (f))` and signals before `(f)` runs, while its own full call
(`notinline`, `funcall` of the symbol, `eval`) evaluates `(f)` first. Every backend here
follows the interpreter, i.e. CLHS and SBCL's full call.

The shared predicates live in `compiler/ArgumentOrder`:

- `isQuiet(form, quietVariable)`: a constant, or a read of a variable that cannot fail --
  a lexical one, or a global whose read tests no UNBOUND marker and is not dynamically
  bound (`JvmArithCompiler.isQuietVariable`, `WasmArithCompiler.isQuietVariable`: the
  read emission's own unbound test decides, so a global that comes to carry the marker
  stops being quiet with it).
- `isRealValued(form)`: a number literal or an arithmetic call -- converting its value
  cannot signal once it returns.
- `integerArithmeticVariables(form, quietVariable)`: integer arithmetic over quiet
  variables, which cannot signal once each holds an integer (fusion's leaf guard).

A float site (`JvmArithCompiler.compileOperands`, `WasmArithCompiler.compileOperands`, the
plan in `compiler/FloatFold.waiting`) holds back only an operand whose conversion -- or, for a
boxed operand, generic step -- can fail and that has an observable operand after it; those, up to the last observable one, wait in temporaries and convert in order
afterwards, every other operand converts where it stands. An inner float-literal operation
is applied where it stands, under its own operator (on the JVM it is inlined raw, so its
`_dbl` calls go through its own operator's wrapper). Why this shape: the conversion is the
only step that moves -- the same calls on the same values, no test and no allocation added
-- and a temporary costs no machine work once compiled. A site without such a pair emits
what it did before, so float code whose operands are literals, declared floats and
arithmetic (the hot shape) is untouched. Rejected: deferring every site's conversions
(temporaries at every float site, bytecode a hot method's inlining budget pays for), and a
type test of each operand before the later ones run (work on every evaluation). Fusion's
order is `.kb/jvm-int-fusion.md` / `.kb/wasm-int-fusion.md`, "The interpreter's order".

## Tests
ci-spec `argument-evaluation-order-left-to-right` and
`array-operations-enablement-language-group` (four backends);
`JvmLispCompilerTest#compileArgumentFormsEvaluateLeftToRight`,
`WasmLispCompilerIntegrationTest#argumentFormsEvaluateLeftToRight`,
`LispEvaluatorTest#aPlaceSubformEvaluatesOncePerReadModifyWriteMacro`.
The operation order: `FastPathEvaluationOrderFixture` (`LispEvaluatorTest`,
`JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest#...FastPathsKeepTheInterpretersEvaluationOrder`:
a complex-free program under handlers, one without handlers, a complex-capable one; every
level, P1 and component) and ci-spec `fast-paths-keep-the-evaluation-order`; the generic
fold's held-back steps: `ExactPrefixFloatFoldFixture`'s traced rows.
