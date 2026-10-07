# The standard constants are symbols that every backend binds (always on)

`pi`, the 32 float-range constants (`most-positive-*-float`,
`least-*-normalized-*-float`, `*-float-epsilon`, `*-float-negative-epsilon` for
SHORT/SINGLE/DOUBLE/LONG), `most-positive-fixnum`, `most-negative-fixnum`,
`array-dimension-limit`, `array-total-size-limit`, `char-code-limit`,
`internal-time-units-per-second` and `lambda-list-keywords` read as ordinary
SYMBOLS -- under `quote`, inside quoted lists, as binding names -- and every
backend binds the name as a global with its own value. A reference in code
position answers the constant; a quoted reference stays the symbol. `nil` and `t`
are deliberately out: they ARE self-evaluating and the reader answers them
directly.

The reader used to substitute the VALUE wherever the spelling appeared
(`reader/LispReader.readSymbol` via `CL_READ_TIME_CONSTANTS`), so `(car '(pi))`
was a double and `(let ((x 'double-float-epsilon)) x)` was one too. That broke
the ANSI suite's `universe.lsp`, which builds `*floats*` from a quoted list of
exactly these names (`.todo/679`).

## The one table

`rontolisp/ClConstants`: the names (`NAMES`, `FLOAT_NAMES`), the float values,
and `value(member, wasm)` -- the per-backend answer. The float values are the
exact doubles of the binary32 bounds for SHORT/SINGLE (what a one-float-type
runtime answers) and the binary64 bounds for DOUBLE/LONG. Only the fixnum and
array limits vary by backend:

| name | interpreter / JVM | WASM (all three) |
|---|---|---|
| `most-positive-fixnum` | `Long.MAX_VALUE` | `(1L << 30) - 1` |
| `most-negative-fixnum` | `Long.MIN_VALUE` | `-(1L << 30)` |
| `array-dimension-limit` / `array-total-size-limit` | `2147483639` | `(1L << 30) - 1` |

The array limits are `ClConstants.arraySizeLimit(wasm)`, which `make-array`'s dimension check
reads on every backend (`.kb/error-handling.md`, "A make-array dimension").

Everything else (`pi`, floats, `char-code-limit` `0x110000`,
`internal-time-units-per-second` `1000`, the 8-symbol `lambda-list-keywords`
list) is one value everywhere.

## Where the globals come from

- The reader substitutes nothing. A `cl:`-qualified spelling still means the
  standard name (`cl:pi` reads as `PI`), stripped before `PackageResolver` runs.
- The interpreter seeds them in `eval/Environment.createGlobal` (wasm false).
- The JVM and both WASM-GC compiles seed them in
  `macro/LispMacroExpander.injectMvSpillGlobal` as leading `defconstant` forms,
  gated per name on any mention (quote included) and valued with the wasm flag
  read off `runtimeFeatures` (`rontolisp-wasm` present). A program that names
  none stays byte-identical.
- `--no-gc` has no globals, so a code-position reference answers the literal
  directly at its three symbol sites (`typeOf`, `compileExpr`, `collectCalls`;
  a lexical binding still wins, a `setq` target still refuses). Quoted uses
  need nothing (a quote carries no type there). Values are the WASM ones, the
  set the `--no-gc` frontend reads with.
- The names are `cl` variables (`PackageRegistry.CL_VARIABLES`), so they
  resolve bare inside `:use :cl` packages instead of interning per package --
  and so `compiler/CompileTimeBoundp` refuses to fold `(boundp 'pi)`: a `cl`
  symbol is never answered there, and the runtime probe finds the seeded
  global.

## `constantp`

`(constantp 'pi)` is `T` (SBCL agrees): the `macro/expandConstantp` expansion
folds a literal quote of a constant name, and the first-class function in
`eval/Environment` (the `funcall`/`mapcar` path) tests the name too. A computed
designator keeps the runtime test -- a false negative there only narrows a
macro's view, per that expansion's contract.

## Pins

- `reader/LispReaderTest#readConstantNamesAsSymbolsEvenUnderQuote`
- `eval/LispEvaluatorTest#quotedConstantNamesReadAsSymbolsWhileCodePositionAnswersTheValue`
- `codegen/jvm/JvmLispCompilerTest#compileAndRunQuotedConstantsStaySymbolsWhileCodePositionAnswersTheValue`
- `codegen/wasm/WasmLispCompilerIntegrationTest#quotedConstantsStaySymbolsWhileCodePositionAnswersTheValue`
- `codegen/wasm/NoGcWasmCompilerTest#standardConstantsInCodePositionAnswerTheirLiterals`
- `ci-spec.yaml` `quoted-constants-read-as-symbols` (four-backend agreement; the
  fixnum assertions compare relatively because the value differs).
