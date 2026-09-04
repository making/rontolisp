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

## Tests
ci-spec `argument-evaluation-order-left-to-right` and
`array-operations-enablement-language-group` (four backends);
`JvmLispCompilerTest#compileArgumentFormsEvaluateLeftToRight`,
`WasmLispCompilerIntegrationTest#argumentFormsEvaluateLeftToRight`,
`LispEvaluatorTest#aPlaceSubformEvaluatesOncePerReadModifyWriteMacro`.
