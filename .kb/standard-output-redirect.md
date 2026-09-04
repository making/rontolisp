# `*standard-output*` / `*standard-input*` / `*error-output*` redirect of the stream DESIGNATOR

**Invariant**: the print family (`print`/`prin1`/`princ`/`terpri`/`fresh-line`/`write-char`/
`write-string`/`write-line`/`force-output`, and `format` with the `t` destination, which
lowers to `princ`/`terpri`/`fresh-line`) and the read family (`read-line`/`read-char`/`read`/
`peek-char`/`listen`) resolve their stream from the CURRENT — dynamic-first — value of
`*standard-output*` / `*standard-input*` at call time, on the interpreter, the JVM and both
wasm-GC backends. So `(with-output-to-string (*standard-output*) ...)` captures output from
CALLED functions.

`t` (the seeded default) means the process standard output; a handle routes through the same
dispatch the explicit 2-argument forms use.

## Designator rules

- **Omitted stream argument == explicit `nil`** (CL's rule); only `t` names process stdout.
  The argument arrives in a VARIABLE, so the test cannot be hoisted:
  `am.ik.rontolisp.compiler.StreamDesignators` (shared, backend-free AST rewrite) turns a
  non-literal stream expression into `(or <expr> *standard-output*)` — evaluated exactly once
  — and an omitted argument / literal nil into a bare `*standard-output*` read. Backend gates:
  `JvmStringStreamCompiler.streamArg` / `WasmEmitHelper.streamArg`; input side
  `StreamDesignators.resolveInput` behind `JvmStringStreamCompiler.inputStreamArg` /
  `WasmEmitHelper.inputStreamArg`. Interpreter: `resolveOutputDest` in
  `Environment.createGlobal`, applied by `emitTo`, `fresh-line`, `write-line`, `force-output`;
  input via `Environment.defaultInput` (the `defaultOutput` twin), consulted by `read-line`,
  the shared `inputReader` (read-char/peek-char), `read`, `listen`.
- **First-class VALUES (`#'princ` and the rest) forward the stream by the same rule.**
  `BuiltinFunctionWrappers` builds the wrapper defun every `#'` reference of a built-in
  compiles to (its `lambdaFor` is also the interpreter's catalog answer), and the family's
  wrappers carry the optional stream and forward it UNCONDITIONALLY -- `(lambda (a &optional
  s) (princ a s))`, no presence dispatch -- because the bound nil an omitted optional
  carries IS the standard-stream designator (the presence-dispatched `unaryOptionalSecond`
  shape would be wrong for an operator whose nil is legal). Covered for
  `print/prin1/princ/terpri/fresh-line/write-line/write-string` (its `:start`/`:end`
  keywords ride the `boundedSequenceIo` re-extraction), `force-output/finish-output/
  clear-output/read-line/read-char/listen`; `#'peek-char` already forwarded
  peek-type + stream, `#'unread-char` by its binary shape. `#'listen` reaches the WASM
  expression compiler's call-time unsupported stub (the sockets rewrite runs before the
  catalog is injected, and no non-blocking probe exists on this target).
- **A SYNONYM stream is a value riding this seam, not a designator.**
  `(make-synonym-stream 'sym)` builds an instance carrying a closure reading `sym`;
  `StreamDesignators.throughSynonym` wraps the result in a `%STREAM-TARGET` call, so every
  operator resolving here forwards through the synonym per operation, for ANY symbol. Gated on
  the program spelling `make-synonym-stream`. Mechanics: `.kb/read-load-streams.md`.

## Input-side runtime work

The read helpers' "standard input" test was `handle == null`, and a `t` designator is not null:
- JVM: `JvmIoRuntimeBuilder.emitResolveReader` and `_readLineStream` test
  `handle instanceof Long`; `_readStream` inherits via `_readLineStream`.
- WASM: `_read_char`/`_peek_char` take an eq ref, dispatch on `ref.test (ref i31)` not
  `ref.is_null`. `_read_line`/`_read` take an **i32 fd**, so CALL SITES unbox —
  `WasmEmitHelper.streamFdOrStdin` replaces the bare `castI31GetS` ("i31 handle -> unbox,
  else fd 0"). Also fixed `(read-line nil)`, which read stdin on interpreter/JVM and TRAPPED
  on both wasm backends (`ref.cast (ref i31)` on null traps).
- **`--component` spliced dispatchers resolve the designator THEMSELVES**, since they sit
  ahead of the native built-in: `sockets.lisp` / `stdin-dispatch.lisp`'s `%io-read-line` /
  `%io-read-char` and `stdin.lisp`'s `%stdin-read-*-or-raw-f` each bind
  `(or s *standard-input*)` and dispatch on `(integerp in)` — a handle (WASI fd, or the
  negative handle of a string input stream) to native `%read-*-raw`, anything else (nil, and
  the `t` an unbound `*standard-input*` reads as) to the host stdin stream. Input twin of the
  `WasmSocketsRewrite` write-side default; do NOT turn either back into a null test.
- `listen` has no WASM implementation outside a `--component` socket stream (compile-time
  throw), so the input redirect covers it on interpreter + JVM only.
- **`--component` limit**: a dynamic binding may not span an `await` (`WasmLetCompiler`
  rejects it). At component top level the socket/stdin rewrite promotes reads/writes to
  awaits, so `(with-input-from-string (*standard-input* ...) (read-line))` must sit inside a
  plain `defun`; the ci-spec case wraps the input assertions in `pm-input-designators`.

## `*error-output*` is a stream VALUE, not the `t` designator

`t` already names stdout, so `*error-output*` is seeded with a stream VALUE over the reserved
handle `2` — `compiler.StreamDesignators.standardError()` (constructor form the compile
backends seed by compiling) / `standardErrorValue()` (the instance the interpreter seeds) —
whose handle is literally the WASI stderr fd. Self-describing, so `(streamp *error-output*)`
and `(check-type *error-output* stream)` answer off the value. NAMING the variable turns the
instance gate on (as `*default-pathname-defaults*` does); given a global cell without naming
it (progv gives every special one) it seeds the raw handle instead.

Hence `(format *error-output* ...)` reaches stderr on every backend, and `warn`'s report
DEFAULTS to the variable, so `(with-output-to-string (*error-output*) (warn ...))` captures it.

Interpreter and JVM number their own stream tables, so both **reserve handles 0/1/2**:
`Environment.registerIO` starts `nextStreamHandle` at `StreamDesignators.FIRST_USER_HANDLE`
and registers a write-through `Writer` over `System.err` at 2 (so no interpreter stream
operation needs a special case); the JVM's `_addStream` starts `_streamCount` at 3.

- **JVM**: no JDK `Writer` over `System.err` writes THROUGH, so the handle is intercepted.
  `JvmIoRuntimeBuilder.emitStderrBranch` prepends
  `if (handle instanceof Long && (int) handle == 2) { ... }` to `_writeStr`, `_writeLine`,
  `_freshLine`, `_forceOutput`, `_close`, `_openStreamP` (`_writeString` inherits via
  `_writeStr`, but only for code AFTER its own socket probe). Branches AND the table
  reservation gate on `programUsesSymbol(program, *ERROR-OUTPUT*)`. `JvmWarnCompiler` keeps
  its direct `System.err.println` unless the program BINDS the variable, then routes through
  `_writeLine` with a dynamic-first read.
  **Trap: the reserved SLOTS must exist from the start, not just the count.** `_addStream`
  created `_streams` lazily, so with nothing opened the table was null while handle 2 was
  live, and `_writeString`'s socket probe dereferenced it (shape: a served handler reporting
  through lack's `:backtrace` middleware). `<clinit>` allocates `_streams` with the three
  reserved slots empty whenever `usesErrorOutput`. Do not make it lazy again.
- **WASM**: nothing to intercept — fd 2 IS stderr for `_write_stream_str` / `_write_line`,
  and in `--component` mode the adapter's `fd_write` fd 2 branch drives `wasi:cli/stderr`.
  `WasmWarnCompiler` passes the constant i31 2 unless the program binds the variable (then the
  global read); `_start` seeds the global with i31 2, not `_t_sym`.
  **Those are the COMPLETE list of ways a compiled wasm module can put handle 2 into a stream
  designator, and `--component` depends on it**: `WasmLispCompiler` scans the SOURCE for
  `*ERROR-OUTPUT*` / `WARN` / `%WARN` (gives up under `--dynamic`) and, finding none, retains
  the adapter's stdout-only `fd_write` so `wasi:cli/stderr` leaves the component. Anything new
  materializing `StreamDesignators.STANDARD_ERROR_HANDLE` must join that scan
  (`WasmComponentBuilder.Narrowing`, `.kb/optimize-dead-code-elimination.md`); miss it and
  `--optimize` turns the write into a trap (the stdout-only half answers fd 1 and
  `unreachable`s the rest). A fourth materializer — the `_start` seed of the eval runtime's
  `GLOBAL_ENV` mirror, which makes `(symbol-value '*error-output*)` answer the stream value
  (`.kb/symbol-runtime-api.md`) — is safe BY CONSTRUCTION: its gate is the same
  `programUsesSymbol(*ERROR-OUTPUT*)` scan. Keep any future one on that gate.
- **`_close` on a standard stream is a no-op on every backend.** The wasm `_close` guard is
  `fd >= FIRST_USER_HANDLE`, not `fd >= 0` — it used to `fd_close(2)` for real, so
  `(close *error-output*)` silenced every later `warn` on wasm-GC. Do not revert. NOT gated;
  it is the whole byte delta (one constant byte) a redirect-free program sees on wasm.
- `--no-gc` rejects `warn` and a top-level `format` outright.
- Deliberately unguarded: the READ family on handles 1/2. No backend supports it; only the
  failure shape differs (NPE on JVM, zero-byte `fd_read` on wasm).

**Pre-existing `--component` trap**: `WasmSocketsRewrite` redirects `open-stream-p` onto
`%IO-OPEN-STREAM-P` whenever it runs, and only `sockets.lisp` defined that name — so in a
SOCKET-FREE component (`stdin-dispatch.lisp` splice) `open-stream-p` compiled to a call-time
error and TRAPPED. `stdin-dispatch.lisp` now defines it as `(if s t nil)`. **`%IO-LISTEN` is
still missing there** — the same landmine for `(listen ...)`; fix it the same way when needed.

Deliberately out of scope: the CLI's top-level `Error: ...` line stays on standard output.

## Activation rule (do not regress it)

`*standard-output*` / `*standard-input*` become special exactly when the program BINDS one —
`SpecialVarCollector.collectForm` runs
`collectDynamicallyBound(form, {*STANDARD-OUTPUT*, *STANDARD-INPUT*})` on every top-level
form, so a `let`/`let*` binding (directly, or via a built-in binding macro like
`with-output-to-string` / `with-input-from-string` through `expandBuiltinMacro`) implicitly
proclaims it. A program that never binds compiles BYTE-IDENTICALLY to before: print/read
compilers keep hard-coded stdio paths, a bare read of `*standard-output*`/`*standard-input*`
compiles to the constant `t`, and one of `*error-output*` to the constant handle 2. Gate: the
variable is in `ctx.globals` (JVM) / `ctx.globalIndices` (WASM) only when the redirect is
active.

Second, independent gate: the eval runtime's global-environment mirror is seeded with the same
defaults when the program has that runtime AND merely NAMES the variable
(`.kb/symbol-runtime-api.md`). Naming is weaker than binding.

## Per backend

- **Interpreter**: `Environment.defaultOutput` supplier (set by `LispEvaluator.registerEval`)
  reads `DynamicBindings` first, then the global cell; `emitTo`'s null-destination path and
  `fresh-line` consult it.
- **JVM**: `JvmStringStreamCompiler.defaultStreamArg(ctx)` answers the symbol
  `*STANDARD-OUTPUT*` when `ctx.globals` holds it; each print compiler's no-stream branch
  compiles the 2-argument stream path with that read (dynamic-first via `_dget`,
  `.kb/dynamic-special-variables.md`). Global `_g$` field seeded to `"T"` in `<clinit>`.
  `_freshLine(Object)` is a new `JvmIoRuntimeBuilder` method; `_writeLine`'s stdout test is
  `instanceof Long` (nil AND t mean stdout) and resets `_col`.
- **WASM (GC + component)**: `WasmEmitHelper.defaultStreamArg(ctx)` over `_write_stream_str`
  (already dispatched null/t -> stdout, negative i31 -> string-stream record, fd ->
  `fd_write`). Module global seeded with `(call $_t_sym)` in `_start`. `_fresh_line_stream`
  (`FUNC_FRESH_LINE_STREAM`, body in `WasmStringStreamRuntimeBuilder`) reads the last written
  byte out of the record's byte buffer (index `len`; index 0 is the frame quote).
  `_write_line`'s fd dispatch is i31-tested so a `t` value cannot trap.

## fresh-line's cross-backend rule

`fresh-line` takes an optional stream argument everywhere. On a string stream the line-start
test is EXACT (contents inspected: `StringWriter` buffer on interpreter/JVM, the record's byte
buffer on WASM; a stream nothing was written to is at a line start). On any other stream
(file/fd) the column is unknown and a newline is ALWAYS written — the same rule on every
backend. Stdout keeps the existing `atLineStart`/`_col`/`LINE_START_ADDR` tracking.

## `format`'s destination is decided at RUN time when it is not a literal

Literal `t` lowers to the `princ`/`terpri` family; literal `nil` folds into string
concatenation — both at compile time. Any OTHER destination builds the string the same way
then tests the value at run time: a stream takes one `write-string` and the call answers nil;
a `t` value takes the stream-argument-less `write-string` (so the redirect applies); a **nil
value returns the string**. Not hoistable — `nil` is not a stream name, it IS the "build and
return the string" destination, so a VARIABLE destination has no compile-time answer (quri's
`(defun render-uri (uri &optional stream) (format stream ...))`).
`LispMacroExpander.formatDestinationDispatch`; the rendered string is bound to a temp so it is
built once whichever branch consumes it.

## Pinning tests

- `LispEvaluatorTest.evalWithOutputToStringBindingStandardOutputCapturesStreamlessPrints`,
  `.evalLetBoundStandardOutputRedirectsAndRestores`;
  `JvmLispCompilerTest.withOutputToStringBindingStandardOutputCapturesStreamlessPrints`;
  `WasmLispCompilerIntegrationTest.withOutputToStringBindingStandardOutputCapturesStreamlessPrints`;
  ci-spec `s-sql-enablement-language-group`.
- nil designator: `explicitNilStreamArgumentIsTheStandardOutputDesignator`,
  `synonymStreamOverStandardOutputFollowsALaterBinding` (JVM + WASM);
  `evalExplicitNilStreamArgumentIsTheStandardOutputDesignator`,
  `evalSynonymStreamOverStandardOutputFollowsALaterBinding`,
  `evalMakeSynonymStreamResolvesTheNamedVariable`; ci-spec `postmodern-language-incidentals`,
  `synonym-stream-value`.
- first-class values: `funcallOfPrintFamilyForwardsItsStreamArgument` (JVM + WASM; the JVM
  copy also asserts `#'listen`), ci-spec `first-class-print-family-stream-argument`.
- `*error-output*`: `errorOutputIsTheProcessErrorStream`,
  `bindingErrorOutputCapturesWarnAndRestores` (JVM + WASM);
  `evalErrorOutputIsTheProcessErrorStreamAndWarnFollowsARebinding`,
  `evalErrorOutputIsAnOpenStreamThatSurvivesAClose`; ci-spec `error-output-designator` (the
  driver compares stdout, so its first assertion is that the three stderr lines do NOT appear
  there).
- input: `bindingStandardInputRedirectsTheStreamlessReadFamily`,
  `makeSynonymStreamOverStandardInputFollowsALaterBinding` (JVM + WASM);
  `evalBindingStandardInputRedirectsTheStreamlessReadFamily`,
  `evalMakeSynonymStreamOverStandardInputFollowsALaterBinding`.
- `format` destination: ci-spec `quri-enablement-language-group`,
  `LispEvaluatorTest.formatDestinationNilReturnsTheStringEvenThroughAVariable`.
