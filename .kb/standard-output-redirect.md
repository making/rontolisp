# `*standard-output*` redirect of the stream-argument-less print family

The stream-argument-less print family (`print`/`prin1`/`princ`/`terpri`/
`fresh-line`/`write-char`/`write-string`/`write-line`, and `format` with the
`t` destination, which lowers to `princ`/`terpri`/`fresh-line`) resolves its
destination from the CURRENT -- dynamic-first -- value of `*standard-output*`
at call time, on the interpreter, the JVM and both wasm-GC backends. The value
`t` (the seeded default) means the process standard output; a stream handle
routes through the same dispatch the explicit 2-argument forms use. This is
what makes `(with-output-to-string (*standard-output*) ...)` capture output
from CALLED functions -- s-sql's `to-sql-name`/`sql-escape-string`/
`sql-template` shape, the direct trigger (todo-195); the general problem
statement was `.todo/149` (whose INPUT side -- `*standard-input*` and
`*error-output*` -- is still open there). Landed 2026-07-28.

## The activation rule (do not regress it)

`*standard-output*` becomes a special variable exactly when the program BINDS
it somewhere -- `SpecialVarCollector.collectForm` runs
`collectDynamicallyBound(form, {*STANDARD-OUTPUT*})` on every top-level form,
so a `let`/`let*` binding (directly, or through a built-in binding macro like
`with-output-to-string` via `expandBuiltinMacro`) implicitly proclaims it.
A program that never binds it compiles BYTE-IDENTICALLY to before: the print
compilers keep their hard-coded stdout paths, and the JVM/WASM ExprCompilers
keep compiling a bare read of `*standard-output*`/`*error-output*` to the
constant `t`. Gate everywhere: the variable is in `ctx.globals` (JVM) /
`ctx.globalIndices` (WASM) only when the redirect is active.

## Per backend

- **Interpreter**: `Environment` has a `defaultOutput` supplier (set by
  `LispEvaluator.registerEval`) that reads `DynamicBindings` first, then the
  global cell; `emitTo`'s null-destination path and `fresh-line` consult it.
- **JVM**: `JvmStringStreamCompiler.defaultStreamArg(ctx)` answers the symbol
  `*STANDARD-OUTPUT*` when `ctx.globals` contains it; each print compiler's
  no-stream branch then compiles the existing 2-argument stream path with that
  read (dynamic-first via `_dget`, `.kb/dynamic-special-variables.md`). The
  global `_g$` field is seeded to `"T"` in `<clinit>` (minted only when the
  redirect is active). `_freshLine(Object)` is a new `JvmIoRuntimeBuilder`
  method; `_writeLine`'s stdout test is now `instanceof Long` (nil AND t mean
  stdout) and resets `_col`.
- **WASM (GC + component)**: `WasmEmitHelper.defaultStreamArg(ctx)`, same
  pattern over `_write_stream_str` (which already dispatched null/t
  → stdout, negative i31 → string-stream record, fd → `fd_write`). The module
  global is seeded with `(call $_t_sym)` in `_start`. `_fresh_line_stream`
  (`FUNC_FRESH_LINE_STREAM`, body in `WasmStringStreamRuntimeBuilder`) walks
  the record's chunk chain for the last written byte. `_write_line`'s fd
  dispatch is now i31-tested so a `t` value cannot trap. `--no-gc` is
  unchanged (it rejects specials).

## fresh-line's cross-backend rule

`fresh-line` now takes an optional stream argument everywhere. On a string
stream the line-start test is EXACT (contents inspected: `StringWriter` buffer
on interpreter/JVM, chunk-chain walk on WASM -- empty writes are skipped). On
any other stream (a file/fd) the column is unknown and a newline is ALWAYS
written -- deliberately the same rule on every backend, instead of per-handle
column tracking only some backends could afford. Stdout keeps the existing
`atLineStart`/`_col`/`LINE_START_ADDR` tracking.

## Pinning tests

`LispEvaluatorTest.evalWithOutputToStringBindingStandardOutputCapturesStreamlessPrints`
(+ `evalLetBoundStandardOutputRedirectsAndRestores`),
`JvmLispCompilerTest.withOutputToStringBindingStandardOutputCapturesStreamlessPrints`,
`WasmLispCompilerIntegrationTest.withOutputToStringBindingStandardOutputCapturesStreamlessPrints`,
and the `s-sql-enablement-language-group` ci-spec case (all four backends,
including the after-the-binding "still goes to stdout" assertion).

## `format`'s destination is decided at RUN time when it is not a literal

`format` with the literal `t` lowers to the `princ`/`terpri` family above and the
literal `nil` folds into a string concatenation, both at compile time. Any OTHER
destination expression builds the string the same way and then tests the value at
run time: a stream takes one `write-string` and the call answers nil, a `t` value
takes the stream-argument-less `write-string` (so the redirect above still
applies), and a **nil value returns the string**.

That test cannot be hoisted, and the reason is the whole point: `nil` as a
`format` destination does not name a stream -- it IS the "build and return the
string" destination -- so a call whose destination is a VARIABLE has no compile-time
answer. The CL convention that makes this ordinary is a renderer forwarding its own
optional: `(defun render-uri (uri &optional stream) (format stream ...))`, quri's
exact shape. Lowering the nil case to a write printed the URI to stdout and
returned nil where CL returns the string. `LispMacroExpander.formatDestinationDispatch`;
the rendered string is bound to a temp so it is built once whichever branch consumes
it. Pinned by the `quri-enablement-language-group` ci-spec case (all four backends)
and `LispEvaluatorTest.formatDestinationNilReturnsTheStringEvenThroughAVariable`.
