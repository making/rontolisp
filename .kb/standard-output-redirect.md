# `*standard-output*` / `*standard-input*` / `*error-output*` redirect of the stream DESIGNATOR

**Invariant**: the print family (`print`/`prin1`/`princ`/`terpri`/`fresh-line`/`write-char`/
`write-string`/`write-line`/`force-output`, and `format` with the `t` destination) and the
read family (`read-line`/`read-char`/`read`/`peek-char`/`listen`) resolve their stream from
the CURRENT — dynamic-first — value of `*standard-output*` / `*standard-input*` at call time,
on all four backends, so `(with-output-to-string (*standard-output*) ...)` captures output
from CALLED functions. `t` (the seeded default) means process standard output.

## Designator rules
- **Omitted stream argument == explicit `nil`**; only `t` names process stdout. The argument
  arrives in a VARIABLE, so the test cannot be hoisted: `compiler/StreamDesignators` (shared,
  backend-free AST rewrite) turns a non-literal stream expression into
  `(or <expr> *standard-output*)` — evaluated exactly once — and an omitted/literal-nil
  argument into a bare `*standard-output*` read. Gates:
  `JvmStringStreamCompiler.streamArg` / `WasmEmitHelper.streamArg`, input side
  `StreamDesignators.resolveInput` behind `inputStreamArg`; interpreter `resolveOutputDest` /
  `Environment.defaultInput`.
- **First-class values forward the stream by the same rule.** The family's
  `BuiltinFunctionWrappers` wrappers forward the optional stream UNCONDITIONALLY --
  `(lambda (a &optional s) (princ a s))`, no presence dispatch -- because the bound nil an
  omitted optional carries IS the standard-stream designator (the presence-dispatched
  `unaryOptionalSecond` shape would be wrong here). `#'listen` reaches the WASM compiler's
  call-time unsupported stub.
- **A SYNONYM stream is a value riding this seam, not a designator**:
  `StreamDesignators.throughSynonym` wraps the result in a `%STREAM-TARGET` call, gated on the
  program spelling `make-synonym-stream` (`.kb/read-load-streams.md`).

## Input side
The read helpers' "standard input" test must NOT be `handle == null` -- a `t` designator is
not null. JVM: `JvmIoRuntimeBuilder.emitResolveReader` / `_readLineStream` test
`handle instanceof Long`. WASM: `_read_char`/`_peek_char` take an eq ref and dispatch on
`ref.test (ref i31)`, while `_read_line`/`_read` take an **i32 fd**, so call sites unbox
through `WasmEmitHelper.streamFdOrStdin`.

- **`--component` spliced dispatchers resolve the designator THEMSELVES**, sitting ahead of
  the native built-in: `sockets.lisp` / `stdin-dispatch.lisp`'s `%io-read-line` /
  `%io-read-char` and `stdin.lisp`'s `%stdin-read-*-or-raw-f` bind `(or s *standard-input*)`
  and dispatch on `(integerp in)`. Do NOT turn either back into a null test.
- `listen` has no WASM implementation outside a `--component` socket stream, so the input
  redirect covers it on interpreter + JVM only. `WasmSocketsRewrite` redirects `open-stream-p`
  onto `%IO-OPEN-STREAM-P` whenever it runs, so `stdin-dispatch.lisp` must define it;
  **`%IO-LISTEN` is still missing there** — the same landmine for `(listen ...)`.
- **`--component` limit**: a dynamic binding may not span an `await` (`WasmLetCompiler`
  rejects it) and the rewrite promotes top-level reads to awaits, so
  `(with-input-from-string (*standard-input* ...) (read-line))` must sit inside a plain
  `defun`.

## `*error-output*` is a stream VALUE, not the `t` designator
`t` already names stdout, so `*error-output*` is seeded with a stream VALUE over the reserved
handle `2` — `StreamDesignators.standardError()` (compile backends) / `standardErrorValue()`
(interpreter) — whose handle is literally the WASI stderr fd. Self-describing, so
`(streamp *error-output*)` answers off the value. NAMING the variable turns the instance gate
on; a global cell without naming seeds the raw handle instead. Interpreter and JVM number
their own stream tables, so both **reserve handles 0/1/2** (`Environment.registerIO` starts at
`StreamDesignators.FIRST_USER_HANDLE`; the JVM's `_addStream` at 3).

- **JVM**: no JDK `Writer` over `System.err` writes THROUGH, so
  `JvmIoRuntimeBuilder.emitStderrBranch` intercepts the handle in `_writeStr`, `_writeLine`,
  `_freshLine`, `_forceOutput`, `_close`, `_openStreamP`. Branches AND the table reservation
  gate on `programUsesSymbol(program, *ERROR-OUTPUT*)`; `JvmWarnCompiler` keeps its direct
  `System.err.println` unless the program BINDS the variable.
  **Trap: the reserved SLOTS must exist from the start, not just the count** -- a lazy
  `_streams` left the table null while handle 2 was live and `_writeString`'s socket probe
  dereferenced it. `<clinit>` allocates them whenever `usesErrorOutput`.
- **WASM**: nothing to intercept, fd 2 IS stderr. `WasmWarnCompiler` passes the constant i31 2
  unless the program binds the variable; `_start` seeds the global with i31 2, not `_t_sym`.
  **Those are the COMPLETE list of ways a compiled wasm module can put handle 2 into a stream
  designator, and `--component` depends on it**: `WasmLispCompiler` scans the SOURCE for
  `*ERROR-OUTPUT*` / `WARN` / `%WARN` (gives up under `--dynamic`) and, finding none, retains
  the adapter's stdout-only `fd_write`. Anything new materializing
  `StreamDesignators.STANDARD_ERROR_HANDLE` must join that scan
  (`WasmComponentBuilder.Narrowing`, `.kb/optimize-dead-code-elimination.md`); miss it and
  `--optimize` turns the write into a trap. The eval runtime's `GLOBAL_ENV` seed is safe by
  construction (same gate, `.kb/symbol-runtime-api.md`).
- **`_close` on a standard stream is a no-op on every backend**: the wasm guard is
  `fd >= FIRST_USER_HANDLE`, not `fd >= 0` -- it used to `fd_close(2)` for real. Do not
  revert; ungated, it is the whole byte delta a redirect-free wasm program sees.
- `--no-gc` rejects `warn` and a top-level `format`. Deliberately unguarded: the READ family
  on handles 1/2. The CLI's top-level `Error: ...` line stays on standard output.

## Activation rule (do not regress it)
These become special exactly when the program BINDS one --
`SpecialVarCollector.collectForm` runs `collectDynamicallyBound` on every top-level form, so a
`let`/`let*` or a built-in binding macro implicitly proclaims it. A program that never binds
compiles BYTE-IDENTICALLY to before: a bare read compiles to the constant `t`, one of
`*error-output*` to the constant handle 2. Gate: the variable is in `ctx.globals` (JVM) /
`ctx.globalIndices` (WASM) only when the redirect is active. Second, independent gate: the
eval runtime's global-environment mirror is seeded when the program merely NAMES the variable
-- naming is weaker than binding.

## Per backend, and the two derived rules
- Interpreter `Environment.defaultOutput` (set by `LispEvaluator.registerEval`) reads
  `DynamicBindings` first, then the global cell; JVM
  `JvmStringStreamCompiler.defaultStreamArg(ctx)` reads dynamic-first via `_dget`
  (`.kb/dynamic-special-variables.md`), global `_g$` seeded to `"T"` in `<clinit>`; WASM
  `WasmEmitHelper.defaultStreamArg(ctx)` over `_write_stream_str` (null/t -> stdout, negative
  i31 -> string-stream record, fd -> `fd_write`), global seeded `(call $_t_sym)` in `_start`.
  `_fresh_line_stream` (`FUNC_FRESH_LINE_STREAM`) reads the last written byte out of the
  record's byte buffer (index `len`; index 0 is the frame quote).
- `fresh-line`: on a STRING stream the line-start test is EXACT (contents inspected); on any
  other stream the column is unknown and a newline is ALWAYS written -- same on every backend.
- `format`'s literal `t` lowers to `princ`/`terpri` and literal `nil` folds into string
  concatenation, but any OTHER destination builds the string then tests at RUN time: a stream
  takes one `write-string`, a `t` value the stream-argument-less `write-string`, a **nil value
  returns the string**. Not hoistable -- `nil` IS the "build and return" destination, so a
  VARIABLE destination has no compile-time answer.
  `LispMacroExpander.formatDestinationDispatch`.

## Pinning tests
Each name below exists in `LispEvaluatorTest` (`eval`-prefixed), `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest`:
`withOutputToStringBindingStandardOutputCapturesStreamlessPrints`,
`explicitNilStreamArgumentIsTheStandardOutputDesignator`,
`synonymStreamOverStandardOutputFollowsALaterBinding`,
`funcallOfPrintFamilyForwardsItsStreamArgument` (the JVM copy also asserts `#'listen`),
`errorOutputIsTheProcessErrorStream`, `bindingErrorOutputCapturesWarnAndRestores`,
`bindingStandardInputRedirectsTheStreamlessReadFamily`,
`makeSynonymStreamOverStandardInputFollowsALaterBinding`, plus
`LispEvaluatorTest.formatDestinationNilReturnsTheStringEvenThroughAVariable`.

ci-spec: `s-sql-enablement-language-group`, `postmodern-language-incidentals`,
`synonym-stream-value`, `first-class-print-family-stream-argument`,
`error-output-designator` (the driver compares stdout, so its first assertion is that the
stderr lines do NOT appear there), `quri-enablement-language-group`, `pm-input-designators`.
