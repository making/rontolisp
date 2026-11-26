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
- **A Gray instance bound to the variable** is reached by the stream-less families through the
  Gray dispatch helpers, gated on this activation rule ([gray-streams.md](gray-streams.md),
  "Stream-less calls").
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
- `listen` has no WASM implementation outside a string-stream record and a
  `--component` socket stream, so the input redirect covers it on interpreter + JVM only.
  `WasmSocketsRewrite` redirects `open-stream-p` and `listen` onto `%IO-OPEN-STREAM-P` /
  `%IO-LISTEN` whenever it runs, so `stdin-dispatch.lisp` must define both; they fall
  through to the native built-ins under `%OPEN-STREAM-P-RAW` / `%LISTEN-RAW` (the
  `%...-raw` family, so the rewrite cannot recurse); `listen`'s record probe answers a string input
  stream and whose stub traps anything else (measured 2026-09-24: the earlier
  always-nil body made `(listen <string-input-stream>)` answer nil on `--component`
  while the other three backends answered t — ci-spec `listen-at-the-end-of-a-string-stream-and-echo-peek`).
- **`--component` limit**: a dynamic binding may not span an `await` (`WasmLetCompiler`
  rejects it) and the rewrite promotes top-level reads to awaits, so
  `(with-input-from-string (*standard-input* ...) (read-line))` must sit inside a plain
  `defun`.

## `*error-output*` is a stream VALUE, not the `t` designator
`t` already names stdout, so `*error-output*` is seeded with a stream VALUE over the reserved
handle `2` — `StreamDesignators.standardError()` (compile backends) / `standardErrorValue()`
(interpreter) — whose handle is literally the WASI stderr fd. Self-describing, so
`(streamp *error-output*)` answers off the value. NAMING the variable turns the instance gate
on; a global cell without naming seeds the raw handle instead.

**One value, one home.** Naming the variable also gives it a global (JVM `_g$` field, wasm
module global) seeded ONCE in `<clinit>` / `_start`, even when nothing binds it, and the eval
runtime's `GLOBAL_ENV` mirror is seeded by reading that global back. Until 2026-10-04 an
unbound program compiled every read to the constructor form (the `JvmExprCompiler` /
`WasmExprCompiler` fallback arm, now reached only by a read the source scan misses) and the
mirror built a second instance: `(eq *error-output* *error-output*)` was NIL on the JVM and both
wasm backends, `(eq *error-output* (symbol-value '*error-output*))` NIL even with a binding,
and Clojure's `(identical? *err* *err*)` false. The mirror costs a few bytes on wasm and
jar for a single read; a program with several reads or a `warn` comes out
smaller on wasm. Interpreter and JVM number
their own stream tables, so both **reserve handles 0/1/2** (`Environment.registerIO` starts at
`StreamDesignators.FIRST_USER_HANDLE`; the JVM's `_addStream` at 3).

- **JVM**: no JDK `Writer` over `System.err` writes THROUGH, so
  `JvmIoRuntimeBuilder.emitStderrBranch` intercepts the handle in `_writeStr`, `_writeLine`,
  `_freshLine`, `_forceOutput`, `_close`, `_openStreamP`. Branches AND the table reservation
  gate on `programUsesSymbol(program, *ERROR-OUTPUT*)`; `JvmWarnCompiler` keeps its direct
  `System.err.println` unless the variable is a global (named, bound, or a thread program).
  **The gate is "named OR a global"**: `warn` takes the `_writeLine` redirect whenever the
  variable is in `ctx.globals`, and a thread-using program puts it there without naming it
  (the make-thread stream specials), so a `(warn 'cond-class ...)` there once wrote through a
  null `_streams` (cl-postgres' `postgresql-warning` under postmodern, 2026-09-24).
  `JvmLispCompilerTest#compileWarnOfAConditionClassInAThreadUsingProgram`.
  **Trap: the reserved SLOTS must exist from the start, not just the count** -- a lazy
  `_streams` left the table null while handle 2 was live and `_writeString`'s socket probe
  dereferenced it. `<clinit>` allocates them whenever `usesErrorOutput`.
- **WASM**: nothing to intercept, fd 2 IS stderr. `WasmWarnCompiler` passes the constant i31 2
  unless the variable has a module global (named or bound); `_start` seeds the global with i31 2, not `_t_sym`.
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
compiles BYTE-IDENTICALLY to before: a bare read compiles to the constant `t`.
`*error-output*` is the exception: naming it is enough for a global ("One value, one home"). Gate: the variable is in `ctx.globals` (JVM) /
`ctx.globalIndices` (WASM) only when the redirect is active. Second, independent gate: the
eval runtime's global-environment mirror is seeded when the program merely NAMES the variable
-- naming is weaker than binding.

The compile paths collect after macro expansion. The interpreter collects a top-level form as read,
so a binding only a macro's expansion holds was lexical until some form spelled one, and captured
nothing (prove's `is-print`, measured 2026-11-04). The three are seeded in its `specialVars`, where
nothing is byte-counted. `LispEvaluatorTest#evalWithOutputToStringBindingStandardOutputCapturesStreamlessPrints`.

## Per backend, and the two derived rules
- Interpreter `Environment.defaultOutput` (set by `LispEvaluator.registerEval`) reads
  `DynamicBindings` first, then the global cell; JVM
  `JvmStringStreamCompiler.defaultStreamArg(ctx)` reads dynamic-first via `_dget`
  (`.kb/dynamic-special-variables.md`), global `_g$` seeded to `"T"` in `<clinit>`; WASM
  `WasmEmitHelper.defaultStreamArg(ctx)` over `_write_stream_str` (null/t -> stdout, negative
  i31 -> string-stream record, fd -> `fd_write`), global seeded `(call $_t_sym)` in `_start`.
  `_fresh_line_stream` (`FUNC_FRESH_LINE_STREAM`) reads the last written byte out of the
  record's byte buffer (index `len`; index 0 is the frame quote).
- `format`'s literal `t` lowers to `princ`/`terpri` and literal `nil` folds into string
  concatenation, but any OTHER destination builds the string then tests at RUN time: a stream
  takes one `write-string`, a `t` value the stream-argument-less `write-string`, a **nil value
  returns the string**. Not hoistable -- `nil` IS the "build and return" destination, so a
  VARIABLE destination has no compile-time answer.
  `LispMacroExpander.formatDestinationDispatch`.

## A stream's line start
**Invariant: `fresh-line` writes a newline only past column 0 and answers whether it wrote, on every
stream of all four backends** (SBCL 2.2.9, 2026-11-05, row by row in ci-spec
`fresh-line-asks-the-stream-it-is-given`). What it asks:

| stream | what answers | written by |
| --- | --- | --- |
| standard output | the one flag: `atLineStart`, `_col`, `LINE_START_ADDR` | every character write; the compilers' inline `(fresh-line)` too |
| a string stream | the buffer's last character, so `get-output-stream-string` leaves it at a line start | nothing, no cost per write |
| a file, standard error | one per stream: `midLineStreams`, `_midLine` (the entry, or handle 2), the fd's byte in `_line_mark`'s table | `emitTo` and `write-line`; `_writeStr`, `_writeLine`; `_write_stream_str`, `_write_line` |
| a socket | the same; on `--component` and `--native` the entry's `mid-line` (`%sock-track-line`) | `write-string`, `write-line`, `terpri`, a string `write-sequence` |
| a composite stream | `stream-fresh-line` asks the output; a broadcast stream the FIRST component, then a newline to the rest (SBCL's `charpos`) | |

- **Only characters move it.** On a `:default` file, a socket and `sb-sys:*stdout*` alike, SBCL's `write-byte` and
  byte `write-sequence` leave the column: `(write-string "x") (write-byte 10 s) (fresh-line s)` writes a
  second newline, `(write-byte 65 s) (fresh-line s)` none. So no byte path keeps anything, a binary stream
  answers nil and writes nothing (it was an error on the interpreter and the JVM, a written newline on wasm), and
  `(fresh-line t)` after `(write-byte 10 t)` is that second newline; until 2026-11-05 standard output's octets moved
  its flag, all four, from a choice made when they disagreed with SBCL unread.
- A stream starts at column 0 whatever it is opened on (`:append` onto an open line answers nil, written `partialx`).
  A read and `file-position` move nothing. An empty write moves nothing.
- `print` writes `x` then a newline here, so `(print 1 s) (fresh-line s)` adds none where SBCL's trailing space does.
- A `print-object` method or a condition's report writes on the string its printer builds, so `(princ c s)` past column
  0 with a report opening `~&` adds no newline where SBCL's writes one: the text is built from column 0.
- A closed stream and an input stream refuse on the interpreter and the JVM, as any write does (SBCL names both).
  On wasm they answer nil: nothing marks them, and a write to one is no signal there either.
- The interpreter and the JVM key by what stays put under concurrent requests: a concurrent set, not a flag per
  handle growing beside `_streams`, whose copy would lose a write that raced it.
- `_write_line` to fd 1 stored nothing until 2026-11-05: `(princ "a") (write-line "b") (fresh-line)` wrote a blank
  line on the interpreter, P1 and the component, none on the JVM and SBCL.

**Gates.** A program that cannot ask carries none of it: `(fresh-line)` on an unbound `*standard-output*` is the
compilers' inline flag, and a stream's ask is what the writes pay for. wasm decides at emission: a compiled
`_fresh_line_stream` call allocates the table's global (`QuoteGlobals.lineStartTable`), and the runtime bodies built
after it read the answer; `_fresh_line_stream` and `_line_mark` are `unreachable` otherwise. The JVM's runtime is built
before the bodies, so a census predicts it (`LispMacroExpander.mayAskStreamLineStart`) and `_freshLine` exists only
under it; a call the census missed (a condition's report lives in the registry) is an unresolved method,
`GROUP_LINE_STARTS`, a re-run. A linked class forces it: one class writes a stream another's `fresh-line` asks, so
`_midLine` is shared state (`JvmLinkage`). The catalog wrapper of `#'fresh-line`, which a program compiled whether or
not it could reach it and whose body asks, is left out where the name cannot be reached
(`BuiltinFunctionWrappers.reachesFreshLineValue`, or a program naming anything at run time). A statement takes the
offer to answer nothing (`.kb/toplevel-statement-values.md`), so a `~&` keeps no `_t_sym` reachable.

**A line start is no column.** One bit per stream is what `fresh-line` needs and what standard output always kept;
`.kb/pretty-printer.md`, "What a stream with no column cannot do", wants a count, and those write paths are where it
would be kept.

## Pinning tests
Each name below exists in `LispEvaluatorTest` (`eval`-prefixed), `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest` (the first one's compiled twins are now ci-spec
`with-output-to-string-binding-standard-output-captures-streamless-prints`):
`withOutputToStringBindingStandardOutputCapturesStreamlessPrints`,
`explicitNilStreamArgumentIsTheStandardOutputDesignator`,
`synonymStreamOverStandardOutputFollowsALaterBinding`,
`funcallOfPrintFamilyForwardsItsStreamArgument` (the JVM copy also asserts `#'listen`),
`errorOutputIsTheProcessErrorStream`, `bindingErrorOutputCapturesWarnAndRestores`,
`errorOutputIsOneStreamValue`, `streamDirectionPredicatesAtTheTopLevel`,
`bindingStandardInputRedirectsTheStreamlessReadFamily`,
`makeSynonymStreamOverStandardInputFollowsALaterBinding`, plus
`LispEvaluatorTest.formatDestinationNilReturnsTheStringEvenThroughAVariable`.

ci-spec: `s-sql-enablement-language-group`, `postmodern-language-incidentals`,
`synonym-stream-value`, `first-class-print-family-stream-argument`,
`error-output-designator` (the driver compares stdout, so its first assertion is that the
stderr lines do NOT appear there), `error-output-identity-and-top-level-stream-direction`, the
standalone `error-output-is-one-value-without-a-binding` (the shared corpus binds the variable,
so only a standalone program reaches the unbound read), `quri-enablement-language-group`, `pm-input-designators`.

A stream's line start: ci-spec `fresh-line-asks-the-stream-it-is-given`, `format-opening-fresh-line-asks-the-destination`,
`binary-standard-output`, the standalone `fresh-line-on-standard-output-with-no-binding-of-it` and
`fresh-line-and-format-on-standard-error`; `LineStartFixture` (standard error's text, sockets) in
`errorOutputIsTheProcessErrorStream` and `rontolispNativesAreFunctionValues`'s twins and `NativeTcpTest`;
`JvmLispCompilerTest.onlyAProgramThatCanAskALineStartKeepsThem`, `WasmLispCompilerTest.onlyAModuleThatCanAskALineStartKeepsThem`.
