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

A float site (`JvmArithCompiler.compileUnboxedOperands`, `WasmArithCompiler.compileF64Operands`)
holds back only an operand whose conversion can fail and that has an observable operand
after it; those, up to the last observable one, wait in temporaries and convert in order
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
level, P1 and component) and ci-spec `fast-paths-keep-the-evaluation-order`.
