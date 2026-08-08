# `*standard-output*` / `*standard-input*` / `*error-output*` redirect of the stream DESIGNATOR

The print family (`print`/`prin1`/`princ`/`terpri`/`fresh-line`/`write-char`/
`write-string`/`write-line`/`force-output`, and `format` with the `t`
destination, which lowers to `princ`/`terpri`/`fresh-line`) resolves its
destination from the CURRENT -- dynamic-first -- value of `*standard-output*`
at call time, on the interpreter, the JVM and both wasm-GC backends. The value
`t` (the seeded default) means the process standard output; a stream handle
routes through the same dispatch the explicit 2-argument forms use. This is
what makes `(with-output-to-string (*standard-output*) ...)` capture output
from CALLED functions -- s-sql's `to-sql-name`/`sql-escape-string`/
`sql-template` shape, the direct trigger (todo-195); the general problem
statement was `.todo/149`, closed by the `*error-output*` section below.
Landed 2026-07-28.

**An OMITTED stream argument and an EXPLICIT `nil` are the same designator**
(CL's rule; landed 2026-07-31, `.todo/200`'s half of `.todo/149`). Only `t`
names the process standard output. The case that needs it is a renderer
forwarding its own optional -- `(defun emit (x &optional stream) (princ x stream))`
called as `(emit x)` passes nil down -- which before this reached raw stdout on
every backend and so escaped the redirect. Because the argument arrives in a
VARIABLE the test cannot be hoisted: `am.ik.rontolisp.compiler.StreamDesignators`
(the shared, backend-free AST rewrite) turns a non-literal stream expression into
`(or <expr> *standard-output*)` -- evaluated exactly once -- and an omitted
argument / literal nil into the bare `*standard-output*` read. Both backends
call it through their existing gate (`JvmStringStreamCompiler.streamArg`,
`WasmEmitHelper.streamArg`), so a program that never binds `*standard-output*`
is still byte-identical to before. The interpreter's half is one
`resolveOutputDest` hop in `Environment.createGlobal`, applied by `emitTo`,
`fresh-line`, `write-line` and `force-output`.

**Consequence: `(make-synonym-stream '*standard-output*)` is no longer a
snapshot.** It answers the `nil` designator, so that synonym forwards PER
OPERATION exactly like CL's -- the re-evaluation trigger `.kb/read-load-streams.md`
left behind, retired in the same pass. A synonym over any OTHER symbol is still
resolved once at construction (see that file for why).

## The INPUT mirror: `*standard-input*` (landed 2026-07-31, `.todo/149`)

Everything above holds on the input side, name for name. `*standard-input*` is a
`cl` variable seeded to the SAME `t` designator (`Environment.createGlobal`, the
JVM `<clinit>` seed, the WASM `_start` seed), it is auto-proclaimed special by
the same `SpecialVarCollector.collectDynamicallyBound` rule when the program
binds it, and `read-line`/`read-char`/`read`/`peek-char`/`listen` take their
source from `StreamDesignators.resolveInput` behind
`JvmStringStreamCompiler.inputStreamArg` / `WasmEmitHelper.inputStreamArg`. So
`(with-input-from-string (*standard-input* "...") ...)` redirects the read
family inside CALLED functions, and a reader forwarding its own optional
(`(defun next-line (&optional stream) (read-line stream))`) follows it.
Interpreter: `Environment.defaultInput`, the `defaultOutput` twin, consulted by
`read-line`, the shared `inputReader` (read-char/peek-char), `read` and `listen`.

**Unlike the output side this one DID need runtime work**, because the read
helpers' "standard input" test was `handle == null` and a `t` designator is not
null:
- JVM: `JvmIoRuntimeBuilder.emitResolveReader` and `_readLineStream` now test
  `handle instanceof Long` (`_listen` already did). `_readStream` inherits it
  through `_readLineStream`.
- WASM: `_read_char`/`_peek_char` take an eq ref and now dispatch on
  `ref.test (ref i31)` instead of `ref.is_null`. `_read_line`/`_read` take an
  **i32 fd**, so the CALL SITES unbox -- `WasmEmitHelper.streamFdOrStdin`
  replaces the bare `castI31GetS` there ("i31 handle -> unbox, else fd 0", a
  self-contained `if`/`else`/`end` needing no `wasmCtrlDepth` bookkeeping).
  That also fixed a pre-existing cross-backend divergence with no redirect
  involved: `(read-line nil)` read stdin on the interpreter and the JVM and
  TRAPPED (`wasm trap: cast failure`) on both wasm backends, because
  `ref.cast (ref i31)` on a null ref traps.

**The `--component` spliced dispatchers resolve the designator THEMSELVES.**
`sockets.lisp` / `stdin-dispatch.lisp`'s `%io-read-line` / `%io-read-char` and
`stdin.lisp`'s `%stdin-read-*-or-raw-f` used to branch on `(null s)` and send a
nil stream to the host stdin cache -- ahead of the native built-in where the
compiler's designator rewrite lives, so the redirect was invisible there and a
`(with-input-from-string (*standard-input* ...) (read-line))` inside an ASYNC
component TRAPPED. Each now binds `(or s *standard-input*)` first and dispatches
on `(integerp in)`: a handle (a WASI fd, or the negative handle of a string
input stream) goes to the native `%read-*-raw`, anything else -- nil, and the
`t` an unbound `*standard-input*` reads as -- is the host stdin stream. A
program that never binds the variable compiles the bare read to the constant
`t`, so it keeps exactly the stdin path it had. This is the input twin of the
`WasmSocketsRewrite` write-side default; do not turn either back into a null
test.

`listen` has no WASM implementation outside a `--component` socket stream (it
throws at compile time there), so the input redirect covers it on the
interpreter and the JVM only -- the same limit as before.

**Known `--component` limit (pre-existing, now reachable from here):** a
dynamic binding may not span an `await` (`WasmLetCompiler` rejects it -- the
saved global cannot be restored across a suspension). At the top level of a
component program the socket/stdin rewrite promotes reads and writes to awaits,
so `(with-input-from-string (*standard-input* ...) (read-line))` has to sit
inside a plain `defun` (a sync context) there. That is why the ci-spec case
wraps the input assertions in `pm-input-designators`.

## `*error-output*` is a HANDLE, not the `t` designator (landed 2026-07-31, `.todo/149`)

The third stream special closes `.todo/149`. `t` already names the process standard
OUTPUT, so `*error-output*` cannot be seeded with it and still mean the error stream:
its value is the reserved stream HANDLE `2` --
`compiler.StreamDesignators.standardError()`, literally the WASI fd the wasm write
helpers already send stderr through. So `(format *error-output* ...)` reaches stderr
instead of stdout on every backend (before this it reached stdout everywhere, the
deviation `PostmodernE2eTest.programOutput` used to filter out), and `warn`'s report
DEFAULTS to the variable instead of hard-coding stderr, so
`(with-output-to-string (*error-output*) (warn ...))` -- CL's warning-capture idiom --
captures it.

Because the interpreter and the JVM number their stream tables themselves (only the wasm
backends' handles are real fds), both now **reserve handles 0/1/2** so no user stream can
be handed the designator: `Environment.registerIO` starts `nextStreamHandle` at
`StreamDesignators.FIRST_USER_HANDLE` and registers a write-through `Writer` over
`System.err` at 2 -- which is why every interpreter stream operation (print family,
`write-string`/`write-line`, `fresh-line`, `force-output`, `open-stream-p`) needs no
special case -- and the JVM's `_addStream` starts `_streamCount` at 3.

**On the JVM the reserved SLOTS have to exist from the start, not just the count**
(todo-283, and the reservation always assumed it): `_addStream` created `_streams`
lazily, so in a program that opens nothing the table was null while handle 2 was
already a live designator -- and the helpers whose socket probe indexes the table from
the raw handle AHEAD of their `_writeStr` delegation (`_writeString`) dereferenced it.
`<clinit>` now allocates `_streams` with the three reserved slots empty whenever
`usesErrorOutput`, so such a probe reads an empty slot and falls through to the stderr
branch. A served handler reporting through `*error-output*` -- lack's `:backtrace`
middleware -- is the shape that hits it. Do not make the allocation lazy again.

What that does NOT fix, deliberately (no consumer, and each would need its own guard):
the helpers with no stderr branch at all still take a reserved handle down their table
path and dereference the empty slot -- `(write-byte b *error-output*)` and the read
family (`read-char`/`read-byte`/`read-line`) on any of 0/1/2. Neither backend
SUPPORTS those (the interpreter answers `WRITE-BYTE expects a binary output stream`);
what differs is the shape of the failure, an NPE instead of that error. Give them the
branch, or a `>= FIRST_USER_HANDLE` guard, when something needs one.

- **JVM**: there is no JDK `Writer` over `System.err` that writes THROUGH (a
  `PrintWriter` buffers, and a warning lost at exit is worse than the branch), so the
  handle is intercepted instead: `JvmIoRuntimeBuilder.emitStderrBranch` prepends
  `if (handle instanceof Long && (int) handle == 2) { ... }` to `_writeStr`,
  `_writeLine`, `_freshLine`, `_forceOutput`, `_close` and `_openStreamP`
  (`_writeString` inherits it through `_writeStr` -- but only for code AFTER its own
  socket probe, which is why that probe needs the table to exist; see above). The whole
  group -- branches AND the table reservation -- is gated on
  `programUsesSymbol(program, *ERROR-OUTPUT*)`, so a
  program that never mentions the variable is byte-identical to before.
  `JvmWarnCompiler` keeps its direct `System.err.println` unless the program BINDS the
  variable, in which case the report goes through `_writeLine` with the dynamic-first
  read.
- **WASM**: nothing to intercept -- fd 2 IS stderr for `_write_stream_str` /
  `_write_line`, and in `--component` mode the adapter's `fd_write` fd 2 branch drives
  `wasi:cli/stderr`. `WasmWarnCompiler` passes the constant i31 2 unless the program
  binds the variable (then the global read), and `_start` seeds the global with i31 2
  rather than `_t_sym`.
  **Those are the COMPLETE list of ways a compiled wasm module can put handle 2
  into a stream designator, and `--component` now depends on that** (todo-273): a
  descriptor is a value in the core module, not an edge, so the wrapper cannot see it and
  asks the SOURCE instead -- `WasmLispCompiler` scans for `*ERROR-OUTPUT*` / `WARN` /
  `%WARN` (and gives up under `--dynamic`) and, when none of them is there, retains the
  adapter's stdout-only `fd_write` so the whole `wasi:cli/stderr` interface leaves the
  component. Anything new that materializes `StreamDesignators.STANDARD_ERROR_HANDLE`
  must join that scan (`WasmComponentBuilder.Narrowing`,
  `.kb/optimize-dead-code-elimination.md`); miss it and `--optimize` turns that write
  into a trap. It traps rather than misdirects by design -- the stdout-only half answers
  fd 1 and `unreachable`s the rest. A fourth materializer joined in todo-283 -- the
  `_start` seed of the eval runtime's `GLOBAL_ENV` mirror, which is what makes
  `(symbol-value '*error-output*)` answer the handle (`.kb/symbol-runtime-api.md`) --
  and it is safe by CONSTRUCTION rather than by remembering: its own gate is the very
  `programUsesSymbol(*ERROR-OUTPUT*)` scan above, so it cannot exist in a module the
  narrowing pruned. Keep any future one on that gate for the same reason.
- **`_close` on a standard stream is a no-op on every backend.** The wasm `_close`'s
  guard is now `fd >= FIRST_USER_HANDLE` instead of `fd >= 0`: it used to `fd_close(2)`
  for real, so a `(close *error-output*)` silenced every later `warn` on wasm-GC while
  the interpreter and the JVM kept warning. Do not turn it back into a `>= 0` test.
  This one is NOT gated -- closing stdout is a bug with or without `*error-output*` --
  so it is the whole byte delta a redirect-free program sees on wasm: measured, a
  module compiled before and after this pass differs in exactly ONE byte, that
  constant. The JVM side stays byte-identical for such a program (the `System.err`
  constant is minted only with the branches).
- `--no-gc` rejects `warn` and a top-level `format` outright, so it has no share of this.

**One pre-existing `--component` bug fell out of the ci-spec case** (the `(open-stream-p
*error-output*)` assertion, but nothing about `*error-output*` caused it):
`WasmSocketsRewrite` redirects `open-stream-p` onto `%IO-OPEN-STREAM-P` whenever it runs,
and only `sockets.lisp` defined that name -- so in a SOCKET-FREE component (the
`stdin-dispatch.lisp` splice) any `open-stream-p` call compiled to a call-time error and
TRAPPED. `stdin-dispatch.lisp` now defines it as `(if s t nil)`, the same answer
sockets.lisp gives a non-socket handle. **`%IO-LISTEN` is still missing there** -- the
same landmine for `(listen ...)` in a socket-free component; it was not fixed here
because nothing in this pass needed it, and `listen` is a documented WASM gap anyway (see
the `listen` note above). Fix it the same way when something does.

Out of scope deliberately: the CLI's own top-level `Error: ...` line stays on standard
output. It is the driver's diagnostic, not a program-visible `*error-output*` write, and
the compiled backends do not have it at all.

## The activation rule (do not regress it)

`*standard-output*` (and `*standard-input*`) becomes a special variable exactly
when the program BINDS it somewhere -- `SpecialVarCollector.collectForm` runs
`collectDynamicallyBound(form, {*STANDARD-OUTPUT*, *STANDARD-INPUT*})` on every
top-level form, so a `let`/`let*` binding (directly, or through a built-in
binding macro like `with-output-to-string` / `with-input-from-string` via
`expandBuiltinMacro`) implicitly proclaims it. A program that never binds it
compiles BYTE-IDENTICALLY to before: the print and read compilers keep their
hard-coded stdio paths, and the JVM/WASM ExprCompilers keep compiling a bare
read of `*standard-output*`/`*standard-input*` to the constant `t` (and one of
`*error-output*` to the constant handle 2). Gate everywhere: the variable is in `ctx.globals` (JVM) /
`ctx.globalIndices` (WASM) only when the redirect is active.

A SECOND, independent gate rides alongside it since todo-283: the eval runtime's
global-environment mirror is seeded with the same defaults when the program has that
runtime AND merely NAMES the variable (`.kb/symbol-runtime-api.md`). Naming is weaker
than binding, so a program that only writes to `*error-output*` pays that seed while its
reads stay the constant; a program mentioning none of the three is untouched by either
gate.

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

The nil-designator half: `explicitNilStreamArgumentIsTheStandardOutputDesignator`
and `synonymStreamOverStandardOutputFollowsALaterBinding` in the JVM and WASM
suites, `evalExplicitNilStreamArgumentIsTheStandardOutputDesignator` /
`evalSynonymStreamOverStandardOutputFollowsALaterBinding` /
`evalMakeSynonymStreamResolvesTheNamedVariable` in `LispEvaluatorTest`, and the
`postmodern-language-incidentals` ci-spec case (all four backends).

The `*error-output*` half: `errorOutputIsTheProcessErrorStream` and
`bindingErrorOutputCapturesWarnAndRestores` in the JVM and WASM suites,
`evalErrorOutputIsTheProcessErrorStreamAndWarnFollowsARebinding` /
`evalErrorOutputIsAnOpenStreamThatSurvivesAClose` in `LispEvaluatorTest`, and the
`error-output-designator` ci-spec case (all four backends -- the driver compares stdout,
so the case's first assertion is that the three stderr lines do NOT appear there).

The input half: `bindingStandardInputRedirectsTheStreamlessReadFamily` and
`makeSynonymStreamOverStandardInputIsTheNilDesignator` in the JVM and WASM
suites, `evalBindingStandardInputRedirectsTheStreamlessReadFamily` /
`evalMakeSynonymStreamOverStandardInputIsTheNilDesignator` in
`LispEvaluatorTest`, and the same ci-spec case.

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
