# Error handling: unwind-protect + condition objects + handler-case + restarts

**Backend contract: interpreter, JVM and both wasm-GC backends (Preview 1 + `--component`, incl.
serve) are full; only `--no-gc` rejects `unwind-protect` / `handler-case` / `ignore-errors` at
compile time** (no condition objects in its value model).

wasm-GC catching uses the WebAssembly exception-handling proposal and is GATED: only a program
containing one of the three catching forms is compiled in **EH mode** (one `$lisp-cond` tag,
`try_table`/`throw`) and only such a program needs wasmtime 37+. Anything else is byte-identical to
a build that never knew about EH -- unless `--report-locations` asks for the uncaught report
("Location lines on wasm-GC" below).

- **A NON-LOCAL EXIT is not a condition**: it passes through a `handler-case` uncaught while still
  running every `unwind-protect` cleanup -- cross-lambda `return-from`/`go` and `catch`/`throw`
  share one exit channel ([do-return-block.md](do-return-block.md), which owns the
  `ctx.blockExitTag`/`blockExitChannel` gate).
- **Java frames are transparent to both**: an exit or condition a `java:` callback raises
  passes through the Java call that ran it, the compiled `_condTl` / `_nleTl` state with it
  ([java-interop.md](java-interop.md), "What a callback raises").
- **A compiled landing reads only what ITS throwable carries** (JVM, "The JVM keeps what a
  throwable carries under the throwable" below): the interpreter's `LispEvalException` and
  wasm-GC's `$lisp-cond` payload carry their condition themselves.
- **The three-point catchability spectrum**: interpreter catches `LispEvalException` only -- with
  the evaluation seam classifying an escaping `IllegalArgumentException` /
  `IndexOutOfBoundsException` (`program-error`) and cast / arithmetic / negative-size failure (the
  raw-failure classes) into one first, and only an `UnsupportedOperationException` (a limitation)
  left raw ("Argument-shape errors" below) -- JVM any `RuntimeException`, wasm-GC only `$lisp-cond`
  throws -- raw traps there (failed ref.cast, `unreachable`) are uncatchable and skip
  unwind-protect cleanups.

## Phase 1 -- unwind-protect
`LispEvaluator.evalUnwindProtect` (try/finally over both Java unwind channels, `LispEvalException`
and `BlockReturnSignal`); `JvmUnwindProtectCompiler` over the method's exception table
(`MethodCode.exceptionCatch`); `WasmUnwindProtectCompiler` (`block $u (result exnref)` +
`try_table (catch_all_ref $u)`, landing = cleanups over the exnref then `throw_ref`). A cleanup that
signals replaces the pending unwind (CL: newer exit wins).

- JVM emitters stay frame-free; the version-61 handler frames are computed as the class is
  written ([stack-map-frames.md](stack-map-frames.md)). Each `Ctx` carries a per-method
  `exceptionTable`, whose ranges the writer turns into labels, and a handler's catch type
  counts toward the method's pool entries when the class is split.
- **The `return` channel is a plain GOTO/br and would skip the cleanups**, so `Ctx.unwindScopes`
  records active scopes. JVM: `JvmReturnCompiler` compiles every ESCAPED scope's cleanups inline
  before its GOTO (escaped = `scope.blockDepth >= blockTargets.size()`, innermost first), and those
  inlined ranges are `holes` the scope's own exception entries exclude -- a throw from an inlined
  cleanup must not re-enter its own handler but must still reach OUTER ones. WASM: each scope pushes
  an `UnwindScope{cleanupForms, blockDepth, trampolineDepth}` and `WasmReturnCompiler` branches to a
  trampoline block emitted lexically OUTSIDE the try_table (the structural equivalent of `holes`),
  cascading innermost-first.
- **A cleanup's VALUES are discarded, its value COUNT included**: the cleanup sequence is bracketed
  by a save/restore of the `%mv-spill` channel on all three backends, and the save lives in each
  backend's SHARED cleanup emitter so `return`/`go`-inlined copies get it too
  ([multiple-values.md](multiple-values.md)).
- **with-\* retrofit**: `expandWithOpenFile` / `expandWithOutputToString` /
  `expandWithInputFromString` / the three usocket `with-*` take a `boolean unwindProtect` (default
  true); WASM call sites pass `false`, so interpreter/JVM close on EVERY exit, WASM on normal exit
  only.
- **A special `let` IS a region of this machinery**: `Jvm`/`WasmLetCompiler` compile the body
  through `JvmUnwindProtectCompiler.Region` / `WasmUnwindProtectCompiler.compileRegion` with the
  internal `%dyn-restore` forms as cleanups, so the dynamic-binding restore rides every channel
  above -- and a binding and a cleanup nested either way unwind innermost-first
  ([dynamic-special-variables.md](dynamic-special-variables.md)). `internalOnly` exempts those
  cleanups from the `%mv-spill` save like `%hc-depth-dec`.

## Phase 2 -- condition objects
A condition is a CLOS-subset instance ([instance-syntax.md](instance-syntax.md)):
`(%obj-new '%class-<name> slots...)`, printed `#<NAME :SLOT value ...>`, NOT a list.

- `ClosRegistry`'s constructor seeds the hierarchy from `CONDITION_SEEDS`, ONE static list with two
  consumers that must keep READING it, never a copy: the constructor, and
  `PackageRegistry.CL_CONDITION_TYPES` = `ClosRegistry.CONDITION_CLASS_NAMES`, which makes every
  seeded name a `cl` symbol ([packages.md](packages.md)).
- Eight classes carry `format-control`/`format-arguments` beyond CLHS's slot lists -- `type-error`,
  `arithmetic-error`, `program-error`, `parse-error` (so `reader-error`), `package-error` (which
  also carries its `package` designator), `file-error` (`[PATHNAME, FORMAT-CONTROL,
  FORMAT-ARGUMENTS]`, the pathname read by the prelude `file-error-pathname`) and the two
  `cell-error` leaves -- because that pair is how a BUILT-IN error carries its message. `simple-type-error` therefore adds nothing, so both keep their old
  `%obj-ref` indexes. Every user subclass of these classes inherits the pair too, and a
  report-less one prints it: `#<MY-PE :FORMAT-CONTROL NIL :FORMAT-ARGUMENTS NIL :POS 1>`.
  `stream-error` carries the offending `stream` (read by the prelude
  `stream-error-stream`); `end-of-file` inherits it, `reader-error` declares its own after the
  message pair it inherits from `parse-error` (`[FORMAT-CONTROL, FORMAT-ARGUMENTS, STREAM]`) with
  `stream-error` as its second ancestor -- the lite-multiple-parents rule applied to a seed
  (`reader-error` is both a `parse-error` and a `stream-error`, CLHS 9.1.2). A seeded class's hand-built factory instance
  (`newEndOfFileCondition`, `newReaderErrorCondition`) must mirror its seed's slot order exactly;
  the report partition groups by slot position, so a drift reads the wrong slots instead of
  failing (2026-09-16: the seed without `STREAM` grouped `reader-error` with `simple-error` and
  its report `cdr`ed the message).
- `define-condition` = `defineConditionToDefclass` -> `expandDefclass` (top-level-only on the
  compile path); `(:report x)` registered (string or lambda AST), `:documentation` dropped. **Lite
  multiple parents**: the FIRST parent provides the slot layout, the rest join the ancestor set only
  (`registerExtraAncestors`).
- `error`/`warn`/`signal` share `expandSignalDesignator`: a string designator takes the LEGACY
  `(%error message)` path (byte-identical output); a quoted-type designator builds the instance via
  the registry slot layout (`buildTypedConstruct`) and signals `(%error-cond instance message)`; an
  object designator dispatches at runtime. A literal `(make-condition 'type ...)` argument re-routes
  through the typed path.
- **A datum that is a STRING at run time is a format control and the arguments after it are its
  format arguments**: `expandObjectSignal`'s string arm renders `(%fmt-render datum (list ...))`,
  fed by three callers. Argument forms are evaluated only on that arm, so every datum-only call keeps
  its previous expansion byte for byte. **The rendering is EAGER**: the instance carries rendered
  text in `format-control` -- as its TEXT CONTROL, below -- and nil `format-arguments`.
  **Re-evaluate if** the renderer becomes free.
- **Invariant: rendered TEXT enters a `format-control` slot only as its text control** -- every `~`
  doubled, the control whose rendering is the text verbatim -- so the report, which renders the slot
  as a control, prints it exactly once. Producers: `LispMacroExpander.textControlForm` (a literal
  escaped at expansion, anything else `(%text-control x)`) at every expansion that builds a simple-*
  instance, `reportingConditionForm` (the JVM pads, `%program-error`), `lowerFileError` and the
  JVM/wasm pads' `simple-error` synthesis; `ClosRegistry.textControl` on the interpreter's Java side
  (`newReportingCondition`, the reader-/file-error factories, `synthesizeCondition`); the wasm-GC
  fixed runtime's `emitConditionThrow` (dispatcher and operand landings) through `_tilde`
  (`FUNC_TILDE`, one body for `%text-control` and `%control-text`, shaken when unused). An
  `(error <literal>)` the expander builds from a name the program chose (an
  undefined function stub) goes through `textDatum` for the same reason: a string datum IS a control.
  Consequences, pinned by ci-spec `condition-report-prints-rendered-text-once` and the three
  `aConditionCarryingRenderedTextReportsItOnce` tests: `(simple-condition-format-control e)` of
  `(error "a~~b")` is `"a~~b"`, the control the program wrote, and `(apply #'format nil control args)`
  reproduces the report for every condition. Before (2026-09-26): the report re-rendered the text,
  so `(error "a~~b")` printed `aNIL` on the interpreter and a caught `(error (format nil "~a" "~/x/"))`
  ran the `~/` directive and escaped the handler as `The function X is undefined` on all four
  backends. Cost: +417 B on a wasm-GC `handler-case` program (`_tilde` is 324 B), +229 B on its JVM
  class.
- The lite `#'error`/`#'warn`/`#'signal`/`#'cerror` WRAPPERS forward the datum only
  (`BuiltinFunctionWrappers.SIGNAL_FUNCTIONS`), so `(apply #'error c '(1 2))` drops the arguments on
  the compiled backends -- documented lite semantics, as for initargs.
- Channels: interpreter `LispEvalException` carries a nullable `condition()`; JVM `%error-cond`
  throws `RuntimeException(message)` and records the instance under it on the emitted
  `private static ThreadLocal _condTl` (that field plus `_hcDepthTl` emitted only when used,
  `JvmLispCompiler.ConditionChannel`; the record: "The JVM keeps what a throwable carries under
  the throwable" below); WASM `%error-cond` traps like `%error`.
- **The JVM message local is shared per method but NOT past its scope** (`Ctx.errorMessageSlot`):
  once `allocTemp` hands that slot to a variable, the cache drops and the next error site takes a
  fresh one. A handler-case in the SAME method resumes after the throw, so a live variable in the
  slot read the message instead of its value -- ci-spec `find-class-metaobject-substrate` printed
  its condition report in place of a `T` once `.todo/919`'s corpus case shifted the slot layout
  (2026-09-22). Pinned by `JvmLispCompilerTest#anErrorCaughtInTheSameMethodLeavesTheSpilledArgumentsAlone`;
  the fix moved 26 of 375 measured JVM artifacts (slot indices, at most +183 bytes of `max_locals`).
- `makeTypeTest` takes a `ClosRegistry` and has a class branch (descendant-tag membership, `equal`
  on the car -- WASM content-safe). `with-slots` is read-only; assignment does NOT write back.

## Phase 3 -- handler-case / ignore-errors
Surface: `(handler-case expr (type ([var]) body...)... [(:no-error ([var]) body...)])`; clause types
are `makeHandlerTypeTest` = `makeTypeTest` + an exact-tag fallback for unknown names. A
condition-less throw is caught as a synthesized `simple-error` with the message in slot 1. No match
-> rethrow (the JVM records the condition under the throwable again first). `:no-error` runs on
normal completion OUTSIDE the handler. `ignore-errors` = `expandIgnoreErrors` over
`(error (c) (values nil c))`.

- **Interpreter** `evalHandlerCase`: `try/catch (LispEvalException)`, `BlockReturnSignal` passing
  through. A per-evaluator `ThreadLocal<ArrayDeque<List<LispVal>>> handlerCaseTypes` holds every
  established handler-case's clause TYPE SPECIFIERS, and `%signal-cond` raises only when some active
  clause type MATCHES (`anyHandlerCaseMatches`). The built-in seam in `LispEvaluator.apply` wraps an
  escaping `IndexOutOfBounds`/`NegativeArraySize`/`Arithmetic`/`ClassCast` into a `LispEvalException`,
  so `aref` out of range and `(make-array -1)` are catchable here too.
- **JVM** `JvmHandlerCaseCompiler` and **WASM** `WasmHandlerCaseCompiler` share the layout: a
  catch-any / `try_table (catch $lisp-cond $h)` region over the protected expression whose landing
  synthesizes the simple-error (JVM from `Throwable.getMessage()`, quote-framed; wasm from the
  payload cdr, already a quote-framed Lisp string) and then compiles clause tests and bodies as
  ORDINARY Lisp over a `__hc_cond$<slot>` pseudo-local. `JvmSignalCondCompiler` checks `_hcDepthTl`
  (null = 0); the wasm depth global is inc/dec'd around the region. A `return` inside decrements the
  depth through the `UnwindScope` cleanup channel, the internal `%hc-depth-dec` form.
- **The JVM operand-stack spill (why handler-case works in an ARGUMENT position).** Unlike
  unwind-protect (handler ends in ATHROW, never rejoins), the handler MERGES back into the normal
  path -- and the JVM discards the operand stack on handler entry, so the two edges disagree by
  exactly the operands the ENCLOSING form had evaluated and the class does not verify.
  `Ctx.spillOperandStack()` saves live operands into fresh locals BEFORE the protected region and
  `Spill.restore` reloads them past the merge; a statement-position handler-case spills nothing and
  stays byte-identical. Liveness comes from `am.ik.jvm.OperandStack`, a typed model fed by
  every instruction `ctx.body` writes, that also supplies a real `max_stack` and raises on a
  merge-point mismatch rather than writing an unverifiable class. **An object under construction (`new`, pre-`<init>`)
  can never be spilled** -- tagged `Slot.UNINIT` and rejected. A `return` escaping a spilled region
  reloads from the outermost escaped `SpillScope` (`JvmReturnCompiler.emitStackUnwind`).
- `FreeVarAnalyzer` learned `handler-case` (clause var BOUND in the clause body), `ignore-errors`
  and `with-slots`.

## The JVM keeps what a throwable carries under the throwable
**Invariant: a compiled landing pad reads only the record of the throwable it caught.** A
`RuntimeException` has nowhere to carry an object and a compiled program ships no exception class,
so the condition a signal travels with (`_condTl`) and a wrong-type operand's datum and type
(`_teTl`, "A non-number reaching arithmetic") live per thread in a `java.util.WeakHashMap` keyed by
the throwable (`JvmThrowableRecords`; `_tlMap` makes a thread's map on its first record).

- **Before (2026-09-26): one slot per thread, read by whichever landing came next.** A plain
  `error`, a raw failure or another condition handled inside an `unwind-protect` cleanup while a
  typed condition was on its way out read the typed one as its own or took it away, and it arrived
  as a synthesized `simple-error` -- `:READ-AS-THE-TYPED-ONE` / `:LOST-ITS-TYPE` where the
  interpreter, wasm-GC and SBCL print `:PLAIN` / `:TYPED`. A wrong-type failure lost its class to a
  second one the same way (the slot held the last record only), an `await` of a plain failure in
  the cleanup CLEARED the typed condition, and a condition whose `:report` handled an error of its
  own while its message was built lost its type (the slot was set before the message ran).
- **Writers.** `%error-cond` / `%signal-cond` evaluate the condition into a local, then the message,
  then `throw _condPut(new RuntimeException(message), condition)`: nothing is recorded before the
  exception exists. `_await` / `_thread_join` record the payload's condition on the awaiting thread
  (a plain failure's: none). `_teRaw` / `_oob` / `_opTypeErr` record `{datum, type}`.
- **A condition's message is rendered, not cast** (`JvmErrorCompiler`): the two terminals pass it
  through `_lispToDisplayString`, so a nil `:format-control` reports `NIL`, the interpreter's text.
  The cast failed the throw with a `NullPointerException`, which the one slot passed off as the
  condition it held from before the message ran -- `(handler-case (error ty :code 42) (type-error
  ...))` with a computed `type-error` worked by that accident alone
  (`JvmLispCompilerTest#compileAndRunErrorWithComputedConditionType` caught it once the slot was
  keyed). What that computed case reports uncaught still differs per backend (`.todo/a50`).
- **Readers TAKE** (`_condTake`, the entry removed): a `handler-case` landing, the `_hbGuard` pad,
  an async body's `run()`, a thread's `call()`, `_jsig`. What passes the throwable on records it
  again: a landing no clause matched (`_condPut` of the record AS TAKEN), the pad (`_condRan` of
  the instance, a synthesized one included -- "Whether the handlers ran rides the flight" below),
  `_jfail`. **Why take, not read**: C2 throws one preallocated exception per class from a hot site
  (`OmitStackTraceInFastThrow`, on by default), so a record left after a flight describes the next
  failure. With a plain read, 300,000 hot `char-code` failures under a `handler-bind` ran its
  handler 5,292-5,332 times under `-XX:-UseJVMCICompiler`: the stale instance matched the global
  mark of the time (2026-09-26); a stale `_condRan` record would skip the handlers the same way.
  Graal, this machine's default JIT, allocates every exception and shows nothing, hence the child
  JVM on C2 in `JvmThrowableRecordsTest`. Only a flight abandoned between a pass-on and the next
  landing still leaves such a record. A `_teTl` record is made for a fresh exception only and is
  never taken.
- **Weak keys**: the record of an abandoned flight (a cleanup that exits, a caller outside the
  program) goes with its throwable. No record holds its throwable -- a value reaching its weak key
  never dies -- so the te record lost its `exception` slot (`_teSlot(e, 0)` answers the record).
- Byte-identical without the channel (`ConditionChannel.used`) and, for `_teTl`, without a landing
  pad (`examples/jvm/java-interop.lisp`, `examples/jvm/life-gui.lisp`,
  `examples/console/calc.lisp`: every output file). A landing is 8 bytes shorter, a no-match rethrow 3, a throw site 10 (13 where it
  normalized a character vector); the fixed part is `_tlMap` / `_condTake` / `_condPut` and ~23
  pool entries. Class bytes before -> after (2026-09-26, against develop at `797c28fec`): the
  ci-spec pin's first case as a program of its own 36,138 -> 36,706; `(ignore-errors (f 1))`
  11,139 -> 11,794; an async body's typed error 51,424 -> 52,011; a restart-mode program 45,991 ->
  46,415; `examples/console/error-handling.lisp` 64,343 -> 64,881; `examples/net/httpbin.lisp`
  185,469 -> 185,966; `examples/net/hello-clack.lisp` (205 throw sites) 950,310 -> 948,474.
- Whether the `handler-bind` handlers ran is part of the record too (`_condRan`), since
  2026-09-27: "Whether the handlers ran rides the flight" below.
- The throwable a `java:` member threw is recorded the same way, but in one weak map for the
  class (`_jexMap`), which `_hcSynth` reads to build the `java:java-exception`
  ([java-interop.md](java-interop.md), "What a member throws").
- Pins: ci-spec `condition-on-its-way-out-keeps-its-record`,
  `JvmLispCompilerTest#aConditionOnItsWayOutKeepsItsRecord*`,
  `#aConditionWhoseMessageIsNoStringIsStillSignalled`,
  `JvmAsyncCompilerTest#anAwaitHandledInACleanupLeavesTheConditionOnItsWayOut`,
  `JvmThreadTest#aJoinHandledInACleanupLeavesTheConditionOnItsWayOut`, the swallowed-plain-failure
  row of `testsupport/JavaImplementationPrograms.CALLBACK_SIGNALS`, `JvmThrowableRecordsTest`.

## WASM EH-mode specifics
- **The gate** (`WasmLispCompiler.compile`): the program (post pre-passes, libraries spliced) is
  scanned for `handler-case`/`ignore-errors`/`unwind-protect` head symbols. Only then: the tag
  section (id 13, between memory and global) with ONE tag `$lisp-cond` whose type reuses
  `TYPE_PRINT_VAL` (`((ref null eq)) -> ()`), the handler-depth global (a `(mut i32)` appended AFTER
  the user globals, `Ctx.ehDepthGlobalIndex`), the throw path and the entry wrappers. Byte-identity
  without the forms is stash-dance proven across P1 / component / http-client / sockets / serve /
  --optimize / --dynamic / --no-wasi exports / --no-gc.
- **Throw path** (`WasmErrorCompiler`): in EH mode `%error`/`%error-cond` build the payload cons
  `(condition-instance . message-string)` (instance nil for plain `%error`) and `throw $lisp-cond`;
  outside EH mode they stay a bare `unreachable` without evaluating anything. `throw` is
  stack-polymorphic like `unreachable`, so call sites are unchanged.
- **Top-level trap shape** (`WasmEmitHelper.emitCatchAllPrologue/Epilogue`): every export wrapper
  body (incl. serve's `%http-dispatch`) runs inside `block` + `try_table (catch_all)` landing on
  `unreachable`, the normal path `return`ing from INSIDE the try_table. The ENTRY function
  (`_start`/`run`) uses the reporting variant instead.
- **Walkers**: `WasmSections.scanInstr` (shared by `WasmImportInjector`) knows `throw` (0x08, tag
  immediate), `throw_ref` (0x0A), `try_table` (0x1F, blocktype + catch-clause vector) and the
  `exnref` valtype (0x69); tags are their own index space so function renumbering is unaffected. P1
  EH + `--optimize` compose. Component path: core-module-internal only. V8 hosts: exnref is
  default-on in Chrome 137+ / Node 24+; Node 22 needs `--experimental-wasm-exnref`.
- **usocket typed conditions**: `usocket.lisp` defines the hierarchy and wraps
  `socket-connect`/`socket-listen`/`socket-accept` bodies in `(usocket::%usock-guard form)`,
  expanded per backend (`expandUsocketGuard`): interpreter/JVM = `handler-case` +
  `usocket::%usock-resignal` (always `usocket:socket-error`), WASM = pass-through. The shim source
  is parsed ONCE and cached for all backends, so the branch cannot be a reader feature.

## The read family signals a TYPED end-of-file
`read-char` / `read-byte`, and `read-line` with an explicit non-nil `eof-error-p`, signal the SEEDED
`end-of-file` class on all four backends (`ClosRegistry` seeds its `:report`, `END_OF_FILE_MESSAGE`).

- Interpreter: `Environment.endOfFile()` throws carrying `ClosRegistry.newEndOfFileCondition()`, a
  static factory -- sound because the class is SEEDED (same slot-less layout in every registry) and
  `handler-case` dispatches on the instance TAG, not layout identity.
- Compiled: one shared CALL-SITE lowering, `LispMacroExpander.expandReadEofSignal`, applied by
  `Jvm/WasmExprCompiler` -- the built-in is called with the backend's `(nil nil)` eof parameters and
  the expansion tests the nil result. Sound for exactly these three operators because a successful
  read never answers nil; runtime helpers keep their old throw as a BACKSTOP. Returns null for a
  literally nil `eof-error-p` or an omitted one on `read-line`.
- **Two gates easy to miss**: `mayCreateInstances` scans the SOURCE program, so the read family is in
  `constructsInstance`; and `#'read-char`/`#'peek-char`/`#'read-byte` joined
  `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`, their wrappers now constructing a condition.
- **The `--component` socket rewrite needs the same lowering under its ALIAS**: `WasmSocketsRewrite`
  maps `(read-char s)` to `(%io-read-char s)` falling through to `rontolisp::%read-char-raw`, so
  lowering only the public name left every sockets.lisp-splicing component with the OLD uncatchable
  trap at EOF. `WasmExprCompiler` lowers `%read-char-raw`/`%read-byte-raw`/`%read-line-raw` too.
- **`read` is deliberately NOT in this family**, nor default `read-line`: both answer nil at end of
  input and `read`'s datum may legitimately BE nil.
- The one-argument `read-from-string` signals `end-of-file` / `reader-error` through its own
  call-site lowering over a parse RECORD rather than a nil test, since its datum may be nil
  (`expandReadFromStringFailure`, [read-load-streams.md](read-load-streams.md)).

## `parse-integer` signals a `parse-error`
A string that is no integer syntax without `:junk-allowed` (junk, no digit, an empty region) is a
`parse-error` reporting `parse-integer: junk in string "12a"` / `parse-integer: no integer in
string ""` on all four backends, in call position and first class. SBCL's class is
`sb-int:simple-parse-error` (a `simple-condition` too); here it is `parse-error` itself, which
carries the message pair for that (Phase 2), so `type-of` answers `PARSE-ERROR`, and the report
text keeps the operator prefix SBCL's lacks.

- The expansion (`expandParseInteger`) signals `(%parse-error message)`, the `%program-error`
  shape. Interpreter: `LispEvalException.ofClass` run through `withHandlerBindHandlersRun` at the
  signal point; the first-class `Environment.parseInteger` throws the same class from the
  built-in, whose apply seam runs the handlers. Compiled: `lowerParseError` -- behind a landing pad
  the signal `(error 'parse-error :format-control (%text-control msg))`, compiled like any typed
  signal, so in restart mode the handlers run at the signal point with the restarts around the
  call still established, as they did for the string datum it replaced (`%program-error`'s bare
  `%error-cond` would have lost them: on the compiled backends a built-in's handlers run at the
  `handler-bind` boundary); without a pad the plain `%error`, the same output as before.
- The instance is built after the scans, so `PARSE_ERROR_SITES` stands in for the tag in
  `conditionNarrowing` and `usedLayoutTags`, behind a pad (the `FILE_ERROR_SITES` situation).
- Before (measured 2026-10-06, SBCL 2.2.9 and the four backends): a `simple-error`, so a
  `parse-error` clause missed it. ANSI `numbers` (interpreter, suite `ca06bd9`): 1,274 -> 1,287 /
  1,444 (`PARSE-INTEGER.ERROR.4`-`.15`, `.5A`), zero regressed; `conditions` (553) and `reader`
  (389) unchanged by the `reader-error` layout move. Pinned by `ParseIntegerSyntaxFixture` in the
  three backend suites and ci-spec `parse-integer-signals-a-parse-error`.

## A condition's `:report` is what PRINTS it
**Invariant: the text a condition REPORTS has exactly one implementation, `%condition-report-str`,
and both the printer and the signal message go through it.** `princ`/`princ-to-string`/`format ~A`
answer the report; `prin1`/`~S` keep the `#<TYPE :SLOT value ...>` syntax -- CLHS's escape-mode
split, matching SBCL.

- **It rides the `print-object` seam** ([clos.md](clos.md)): `expandPrintObjectHook` fires when the
  program defines a `print-object` method **or** can build a condition, and the escape-off arm of
  `%print-object-str` becomes `(if (%obj-p x) (or (%condition-report-str x) (%princ-to-string x))
  (%princ-to-string x))`. A `print-object` method on a condition class still wins in BOTH modes.
- **`%condition-report-str` answers nil when the class reports nothing** and every caller supplies
  its own fallback, so an under-approximating gate degrades to pre-report text instead of failing.
- **The class partition** (`conditionReportGroups`): a class's report is its own `:report`, else the
  nearest ancestor's along the SLOT-LAYOUT parent chain, else -- when it carries
  `format-control`/`format-arguments` -- CLHS's `simple-condition` report. Groups are keyed by report
  owner / slot-index pair, NOT one clause per class: cl-postgres registers 100+ condition classes and
  a per-class dispatch is the same 90 KB-in-one-method trap
  ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- **`%format-condition` renders through `%fmt-render`** ([format.md](format.md)). A control that is a
  FUNCTION is called on the stream through FIXED-ARITY `funcall`s for 0-3 arguments: `apply` would
  drag the whole wasm eval runtime into every program that prints a condition.
- **The gate is `mayCreateConditions(program, registry)`**, the CONDITION half of
  `mayCreateInstances` sharing its `constructsInstance` case split (plus
  `#'error`/`#'warn`/`#'cerror`, and `%obj-new` restricted to `%class-` tags descending from
  `condition`). Answered TWICE in `expandTopLevelDefinitions` -- on the source program (whose only
  condition may be the `simple-error` a `handler-case` synthesizes) and on the expanded program.
  Recorded in `ClosRegistry.routesConditionReports()` rather than a `Ctx` flag, so it needs no
  `WasmAsyncEmit.freshCtx` line.
- **Trap: the generated defuns must not contain the SYMBOL `with-output-to-string`.** The wasm-GC EH
  gate (`programUsesEhForm`) scans for it and runs AFTER these defuns are spliced in, so leaving the
  macro there forced EH mode on every program that merely signals a typed condition;
  `renderedToString` pre-expands it.
- The interpreter loads the same generated AST (`ensureConditionReportRuntimeLoaded`) and RE-loads
  whenever the registry it partitions changed (a stamp over class and report counts).
- **Lite**: the rewrite is per CALL FORM, so a condition reached through a FUNCTION VALUE gets the
  raw conversion, exactly as a `print-object` method does. The string-designator path renders
  EAGERLY, and its text reaches the instance as its text control (Phase 2), so printing renders it
  once: `(error "~a" "~a")` prints `~a`.

## The condition floor is narrowed to what the program can construct
The compile path shrinks the condition runtime along three axes; the interpreter never narrows, and
every narrowing is IMPOSSIBILITY-based.

- **`conditionNarrowing`** scans the expanded program for the constructible `%class-` tag set plus
  whether any site can hand `%format-condition` an UNRENDERED control. Tag sources: literal datums of
  the signal family, literal-tag `%obj-new`, plus always the synthesized simple-* three. BAILS to
  `none()` on a computed datum, `eval`/`symbol-function`/`fdefinition`, an escaping `#'error`-family
  value or quoted designator in data, `--dynamic`. Name forgery from computed strings
  can reach a pruned arm -- the failure is the caller's fallback report text, never a lost signal.
- **Restart mode narrows like any other program** (since 2026-09-26; the bail it replaced had no
  stated reason). What it adds is seen by the scan or always in the set: the signal hook builds the
  simple-* three over a text control, restart-mode `cerror` keeps its datum inside a `restart-case`,
  and the restart runtime defuns are injected before the scan. `usedLayoutTags` dropped its
  restart-mode bail on the same grounds. A handler-bind whose handler prints its condition,
  `(car 5)` probe, wasm-GC default optimize: **113,391 -> 26,365 B** (component 117,075 -> 27,911; JVM
  output 106,911 -> 38,728 B); the same handler ignoring its condition 18,094 -> 16,614 B (the
  layout half). Output unchanged on all four backends over 43 probes covering every late-lowering
  site and raw failure under a bare handler-bind.
- **`%format-condition` declines the renderer** when every possible control has no directive but
  `~~` -- a literal whose every `~` is half of a `~~`, nil, or a `(%text-control x)` -- with nil
  arguments: the common case, since every string-datum signal site pre-renders
  (`formatMessagePieces`) and stores the text control. The declined arm is `(%control-text control)`,
  which undoubles the `~~`: the one directive such a control can hold, so declined and rendered
  artifacts print the same text. Only an explicit `:format-control` initarg with another directive
  (surface keyword check plus the baked `:initform`/`:default-initargs` cons check inside generated
  constructors' `%obj-new`) forces the renderer back. On zlib that is **-61 KB**.
- **A tag built by a LATER lowering is taken from its site's presence.** The read family's
  `end-of-file` (`expandReadEofSignal`, expression expansion) and a failed open's /
  `%file-error`'s `file-error` (`lowerFileError`, body compilation, behind a pad only) are
  constructed after both scans, so `conditionNarrowing` and `usedLayoutTags` read
  `LispMacroExpander.END_OF_FILE_SITES` / `FILE_ERROR_SITES` instead. The narrowing used to
  miss `end-of-file` entirely: a caught one `princ`ed as `#<END-OF-FILE :STREAM NIL>` on the
  compiled backends instead of `end of file` (found and fixed 2026-09-19).
- **`WasmInstanceLayouts.emit` takes a used-tag set** (`usedLayoutTags`): a `%class-`/`%struct-`
  layout ships only when its tag or bare name occurs as a symbol in the final program (plus the
  simple-* three the handler lowering synthesizes during Pass 2), with null (= bake all) under
  `--dynamic`, an embedded eval runtime, subclass enumeration,
  `find-class`/`change-class`/`allocate-instance`/`symbol-function`/`fdefinition`. The JVM already
  interned per referenced tag (`LayoutPool`).
- **`needsRuntimeErrorDispatch` no longer misreads handler clauses**: `(handler-case b (error (e)
  ...))` used to parse as `(error <computed> ...)` and bake the whole per-class construction runtime
  into EVERY handler-case artifact. With clause heads skipped, the 89,138 B probe is **23,341 B**.

## The routing gate asks whether a condition can be NAMED
`mayCreateConditions` cannot part company with `mayCreateInstances` by proving the body will not
signal -- the handler prologue synthesizes a `simple-error` for a caught RAW trap however the body
fails. What decides the gate is whether program code can ever HOLD that instance.

- **`handler-case` counts only when some clause binds a variable its own body mentions**
  (`handlerCaseBindsCondition`); otherwise the instance never leaves the landing pad. The occurrence
  test is deliberately blunt.
- **`handler-bind` counts unless every handler is a literal `lambda` whose body never mentions its
  first required parameter** (`handlerBindExposesCondition`): a handler is CALLED with the instance,
  so a `#'name`, a computed handler or a lambda list opening with `&rest`/`&optional` counts. Before
  2026-09-26 it did not count at all, and on the compiled backends `(format t "~a" c)` in a handler
  printed `#<TYPE-ERROR :DATUM 5 ...>` while the interpreter printed the report. The `(car 5)`
  probe on wasm-GC: 16,614 B with a handler that ignores its condition, 26,365 B with one that
  prints it (113,391 B until restart mode was narrowed, above).
- **`ignore-errors` counts only where a SECOND value can be read** (`receivesMultipleValues`, a
  whole-program answer): any occurrence of
  `multiple-value-bind`/`-list`/`-call`/`-setq`/`-prog1`/`nth-value`/`%mv-spill` turns it back on.
- **A keyword constructor no form references builds nothing, on both sides** (since 2026-10-04;
  the hold side alone before): `define-condition` splices `(defun %make-X ... (%obj-new ...))`
  whether or not anything can call it, so a referenced one (a `make-instance` expansion, a
  `#'%make-X`) makes the answer true and an unreferenced one is skipped, `make-instance` /
  `allocate-instance` / `change-class` naming a condition class (or an unquotable class) counting
  in its place (`instantiatesConditionClass`). The Clojure refusal class needed it: its
  constructor alone put the renderer in every class-reading program.
- **A signal reporting its own text needs no renderer** (`signalsItsTextControl`): `(error 'c ...
  :format-control (%text-control v) ...)` over a variable `v`, of a class reporting through its
  `format-control` (no `:report` along its precedence list) and with no `:format-arguments`,
  reports exactly `v`, so the broad gate does not count it, and its unrouted message is `v` itself
  (`textControlVariable`); a held instance still counts where it is held. The Clojure refusals
  (`%clojure-refuse`) and `%struct-type-error` ([defstruct.md](defstruct.md)) spell it. Measured
  2026-10-04: every example, size-report and bench program byte-identical (the version string of a
  fetch program aside).
- **A clause HEAD is not a call, and that skip is shared.** `evaluatedClauseForms` is the one helper
  answering "which sub-forms of this clause-bearing operator are EVALUATED", used by this scan and by
  `needsRuntimeErrorDispatch`. **A new scan that walks a program as code goes through it** -- the
  same misread has cost three times (a tagbody-tag `CONTINUE`, the dispatch runtime, this).
- At `--optimize=size`: `(print (handler-case (+ 1 2) (error () 0)))` 23,216 -> 5,713 B,
  `(print (ignore-errors (+ 1 2)))` 22,782 -> 5,638 B. Gating the renderer's defun on a pre-Pass-2
  scan for printing operators is NOT sound: `format`'s `~A` lowers to `%princ-piece` after it.

## Signal messages are lazy on wasm-GC
**Invariant: on the wasm-GC backends a signal's message string is rendered only where a condition
value can reach program hands -- never at the signal point.** In EH mode the entry landing pad is a
second payload reader, so both gates go broad; outside EH mode nothing is observable.

- **`WasmErrorCompiler.compileCond` / `WasmSignalCondCompiler` never compile the REPORT half of their
  message operand**: those forms always carry a real instance in the car, and the entry pad renders
  its report itself. `compileCond` compiles only what the pad cannot rebuild -- the fallback of a
  routed message (`LispMacroExpander.conditionReportFallback`, the recognizer of
  `conditionReportOr`'s own shape), below "An uncaught condition reports ONE line". The instance
  operand still compiles (initargs are evaluated at construction per CL).
- **A plain `%error`'s message operand always compiles in EH mode** -- its message IS what a caught
  raw trap becomes a `simple-error` from AND the only text the entry landing pad has; outside EH mode
  `%error` is a bare `unreachable` that evaluates nothing. The `Ctx.condMessagesObservable` flag that
  skipped it went on 2026-09-28: once every EH-mode module is compiled with `reportsUncaught`
  (below), it was true wherever it was read.
- **The routing gate narrows outside EH MODE**: `expandTopLevelDefinitions` takes a
  `macro/SignalMessages` (`WasmLispCompiler` passes `LAZY` when `!reportsUncaught`), under which the answer is
  `mayHoldConditions` = `mayCreateConditions` minus the throw-only constructions (literal-typed
  `error`/`cerror`, `signal`, the read family's EOF lowering). The keyword constructor every
  `define-condition` splices is exempted by SHAPE (`conditionConstructorName`) unless another form
  references it -- without that every library that merely DEFINES conditions (chipz) kept the whole
  renderer.
- **`reportsUncaught` is a PRE-SCAN, not the definitive `ehMode`**: it runs before the passes that
  finish deciding EH mode, so `WasmLispCompiler` scans for triggers that can accompany a signal
  (`programUsesEhForm`, `catch`/`throw`, restart mode, async mode, and
  `LispMacroExpander.runtimeErrorDispatchCatches` -- the `with-output-to-string` the injected
  `%error-runtime` helper of a lambda-`:report` class renders through; the seeded `UNBOUND-SLOT` is
  one, so every `(error <computed> initargs...)` program is in EH mode. Until 2026-09-27 the scan
  missed it and such a program with no catching form of its own printed `Unhandled condition: ` and
  nothing else. Cost, P1 / component / `--optimize=size`, bytes: `(defun f (ty) (error ty :code 42))`
  112,679 -> 125,773 / 114,300 -> 129,489 / 90,232 -> 101,321 -- what the same program with a
  `handler-case` already paid (129,109). A program with a catching form is byte-identical).
- **What the pre-scan cannot see costs a second attempt, never a wrong report**: a cross-lambda
  `return-from` is lowered by `CrossLambdaExitLowering` after the expansion (it must run after, or a
  GENERATED dispatcher's `return-from` would go unlowered), and reaches `ehMode` through
  `blockExitTag`. Where `ehMode` is decided, `ehMode && !reportsUncaught` abandons the attempt
  (`UncaughtReportUnforeseen`, the `FunctionTooLarge` retry's shape) and the next one runs with the
  pre-scan forced on, so the invariant is **EH mode implies `reportsUncaught`**, whatever produced
  the trigger. Until 2026-09-28 such a program kept the narrow gate and its pad printed
  `Unhandled condition: ` and nothing else, a plain `(error "boom ~a" x)` included. Cost, P1 /
  component, bytes (a cross-lambda `mapc` exit plus an uncaught signal): plain `error` 8,252 ->
  9,651 / 9,582 -> 11,014; a report-less typed one 10,754 -> 21,461 / 12,129 -> 22,925;
  `simple-error` with format arguments 9,178 -> 106,722 / 10,559 -> 110,222 (the renderer "hi 5"
  needs); the same exit with no signal 8,235 -> 8,199. Compile time: the expansion runs twice for
  those programs only.
- **`%no-applicable-method` signals VALUES, not prose**: `(error 'no-applicable-method-error
  :%nam-operation tail :%nam-datum-class (%class-designator arg))` against a class seeded ON DEMAND
  (`ClosRegistry.ensureNoApplicableErrorSeeded`; slot names %-fenced so `registerSlotPosition` cannot
  make a user slot ambiguous). Injection moved BEFORE the report-renderer injection (its tag must be
  in `conditionNarrowing`'s set) and AFTER the routing answer (a dispatcher alone must not flip
  routing on). Deliberate narrowing: a `(simple-error ...)` clause no longer matches it.
- zlib `--optimize=size`: P1 127,026 -> 117,118 (-7.8%), component 131,677 -> 121,723, output
  byte-identical. **The remaining floor is a floor**: zlib still carries the value printers (~4.6 KB)
  because the entry edge is `%SEQ-TO-STRING`, reachable through `%FILL-RUNTIME`/`%REPLACE-RUNTIME`,
  whose element conversion IS princ semantics. **Trigger: a program family carrying the printers ONLY
  through `%seq-to-string`/`%schar-set-runtime` and where those bytes matter.**

## An uncaught condition reports ONE line, the same one, on all four backends
**Invariant: a signaled condition escaping the top level writes `Unhandled condition: <report>` to
standard error -- the same line on all four backends -- then the process exits the way it always
did.** Built from `compiler/UncaughtReport.PREFIX` at all three emission sites; the report text is
the one `princ` writes, and nothing below changes it. One report still differs on wasm-GC (the
report-less class, "Known gap" below). A struct accessor on a non-instance agrees since 2026-09-26
([defstruct.md](defstruct.md), "Accessors check their object"), and so does an out-of-range
subscript ("An out-of-range subscript is a type-error naming its bound").

**Under it, location lines** (`UncaughtReport.atLine` / `asyncLine`, two-space indented):
`  at FILE:LINE in FUNCTION` -- the innermost form read from a named file that the condition passed
through and the PROGRAM'S named function that form is written in -- then one
`  in NAME (async), awaited at FILE:LINE` per async boundary crossed. Nothing known (a `-e`
program, only macro-built forms) prints none. **The interpreter and the JVM backend print the same
lines, for Scheme source too** (`cli/UncaughtReportParityTest`); wasm-GC prints them only under
`--report-locations` (below, "Location lines on wasm-GC").
- **Which function: LEXICAL** (every backend) -- the named function whose text holds the form. A
  form in an anonymous lambda (a callback, an `flet`/`labels` helper, a `handler-bind` handler, a
  sort predicate) belongs to the function the lambda is written in, whoever calls it; one at the
  top level, or in an async body (its hop line names the async function), to none. Only a
  function whose body was read from a named file is named (`LispLambda.sourced`,
  `JvmSourceSites.sourced`), so a library function a callback runs under never is. The answer is
  a property of the FORM, so no backend's tail calls, inlining or threads can change it -- the
  reason it is lexical. Until 2026-09-26 it was the named function DYNAMICALLY around the form,
  which each backend approximated from the frames it still had: the interpreter lost the caller
  on a tail call (then kept it, `frameFunction`), the JVM never lost it, and a wasm-GC
  `return_call` left it -- one program named three different functions, and a callback written
  in MAIN but run by RUN-CALLBACK printed `at <a line of MAIN> in RUN-CALLBACK`. A lowering's
  internal name is reported as the program spelled it (`UncaughtReport.functionName`): a method
  body is its generic (`%AREA--m0` -> `AREA`), a `%top-defun$` rename its original, a nested
  `defun` its own name (`UncaughtReport.nestedDefun`).
- **Interpreter -- recorded on the throw path only** (`eval/ConditionTrace`, on
  `LispEvalException.trace()`): `evalCons` keeps the innermost `LocatedCons` it stepped onto and
  the lexical function of the scope it stepped onto it in (a type test and two stores per loop
  step; [source-positions.md](source-positions.md) Phase 4) and hands them over from the catch
  clauses it already had; the first frame with a located form decides both. The function is a
  field of the scope (`Environment.lexicalFunction`): a program function's call scope and a macro
  expander's set it, every other scope -- a closure's included -- inherits it from its parent. A
  form `eval` runs is in the null lexical environment, so it names none.
- **Interpreter async**: `evalCons` runs `%async-run` itself (`runAsync`), naming the async
  function from its own frame -- the body's virtual thread never sees the defun -- and runs the
  thunk under a scope of no function (`asyncBody`). A condition escaping the thunk closes segment
  0 (`crossedAsync`), so the awaiter's frames are not attributed to it; the first located form
  after that is the `await`.
- **The await that re-signalled it** (every backend, since 2026-09-26): a failed future holds ONE
  condition object, which every `await` of it rethrows, so each await first rewinds the trace to
  what that future stored -- the hop's site back to none, and every hop an earlier await's path
  appended dropped. A second await after a handler caught the first names ITSELF, and one of JOB's
  future after RELAY's (which re-signalled JOB's condition) was caught prints no RELAY hop. The
  interpreter keys the hop by the future's `CompletableFuture` (`ConditionTrace.reawaited`, from
  `joinFuture`). Until then the interpreter and the JVM recorded the site once per crossing and
  named the FIRST await, while `--component` named the right one only when no hop had been added
  since.
- **JVM -- read off the stack trace** (`codegen/jvm/JvmSourceSites`, `JvmUncaughtHandler`): every
  method the program's own source compiled into carries a `LineNumberTable` whose numbers are SITE
  ids -- (file, line, function) in a table the class carries as string constants -- not lines: a
  method holds several files' forms (a top-level chunk, a macro handing back a form of its own
  file) and a class names one `SourceFile`. `Ctx.enterSite`/`leaveSite` around every form
  (`JvmExprCompiler.compileCons`, the statement `setq`/`let`, the fused `if`/`while` test) mark
  where the innermost located form changes; a site's function is its method's `writtenIn` -- a
  lambda's method takes that of the method that built it (`LambdaInfo.writtenIn`), an async thunk
  none; a tail-spine item carries the site current where it was queued (`JvmBodyOutliner.Entry`
  -- the construct that queued it has returned by then), and a `_k$N` continuation inherits its
  method's. `_where` walks the exception's trace: the innermost frame of this class (or a
  `$PartN`) at a site gives the location and, from the same site, the function. The positions are
  `SourceProvenance`'s, the same forms the interpreter's reader locates. Code outside any site
  is numbered `JvmSourceSites.NO_SITE` (0xFFFF, above `MAX_SITES`), never 0: `java.lang.classfile`
  (the frame pass) drops a line-0 entry, and the code after it then reported the site before
  (`UncaughtReportParityTest#aComputedConditionTypeReportsTheInitargsTheCallPassed`). (A function's methods
  used to open on a BASE site naming it, for the dynamic rule's outward search; the lexical one
  needs none, 4 bytes a method.)
- **JVM async**: the `%async-run` thunk's last exception entry appends a made-up frame
  `rontolisp/async.crossed(HEAD)` to the escaping exception's trace (`_asyncCross`; `/` is in no
  binary name); `run()` keeps the trace as it then is in the error payload, a fourth element
  (`{EMARKER, t, cond, trace}`, only in a class that records boundaries), and each `_await` that
  rethrows it puts that trace back and appends its OWN frames after the boundary
  (`_asyncAwaited`). `_where` reads each such frame as a hop. An exception keeps its identity (`handler-case` classifies by class), and
  the report empties the trace, so none of it shows unless `RONTOLISP_DEBUG` asks for the trace --
  which then IS the async chain.
- **JVM optimizations keep the granularity**: a fused integer tree (`_fx$N`,
  [jvm-int-fusion.md](jvm-int-fusion.md)) whose operations all report the call's own site stays
  shared and line-free; one that spans lines, or holds an inlined defun's body, gets a method of
  its own whose fallback marks each operation (so `(sq a)` inlined into F still says `in SQ`, even
  when F itself was inlined into the top level). A typed loop marks its array accesses.
- **Nothing located, nothing emitted**: a class compiled with no position in a named file (`-e`,
  a direct `JvmLispCompiler.compile` with no recording scope) has no line numbers, no site table,
  no `_where` and its pool in the old order -- the bytes it always had
  (`UncaughtReportParityTest#aProgramWithNothingLocatedCompilesAsItAlwaysDid`). The line numbers
  survive the write: `JvmClassSplitter` plays each entry into the `CodeBuilder` at the
  instruction it starts at, so it rides that instruction when a relaxed branch or a narrowed
  `ldc` moves it, whichever class the method lands in (`LineNumberTableTest`).
- **Cost, measured 2026-09-26**: +1.5-2 KB per class (`_where` and its constants; ~1 KB gzip)
  plus ~7 bytes per located line (examples/console +1.46-1.79 KB; `llm.lisp` 1.04 MB +12.6 KB,
  1.2%); a fused tree that needs its own method ~200 B. Zero at run time until a condition
  escapes.
- **Harness decisions, per suite**: `ci-spec.yaml`'s `standalone:` compares expected stderr lines
  as CONTAINED, in order (wasmtime prints around ours), so the location lines need no change there
  and are pinned instead by `RontoLispCliStreamsTest`'s `anUncaught*` cases (the interpreter, then
  `java -cp` and `java -jar` of the compiled program) and `UncaughtReportParityTest`, where the file
  path is the test's own. `scheme-spec.yaml` compares the interpreter's MESSAGE and the compiled
  legs' stderr as CONTAINED, so it needs no change; the Scheme cases in `RontoLispCliStreamsTest` pin
  the location lines where the case is about the report and the report line alone where it is about
  the message. The JVM/wasm assertions that compile directly (`JvmLispCompilerTest`,
  `JvmSizedMainTest`, `WasmLispCompilerIntegrationTest`) record no positions -- their programs are
  read from no file -- so they pin the report line alone, a wasm module's even under the option.

- **Interpreter / compile failures** (`RontoLispCli.runReporting`): only `main` catches -- `run`
  still throws, so an embedded caller keeps the exception with its type and cause. A rontolisp
  diagnostic (read error, compile failure, bad command line) says `error:` instead and keeps the
  `file:line:column:` prefix (`locateCompileFailure`). **A RUNTIME condition's MESSAGE carries no
  position on any backend**: the location goes under the report, never into the text a program
  can read. `RONTOLISP_DEBUG` additionally prints the JVM trace.
- **JVM** (`JvmUncaughtHandler`): a last exception-table entry over the whole of `main` catching
  `RuntimeException`; prints the line (and `_where` the location lines), EMPTIES the stack trace
  and RETHROWS. **Not
  `System.exit(1)`**: a compiled class's `main` is invoked in-process by ~110 assertions here and by
  any embedder; the launcher supplies exit 1.
- **wasm-GC, EH MODE ONLY** (`WasmUncaughtReportCompiler`): the entry function wraps its body in
  `block $trap` + `block $cond (result (ref null eq))` +
  `try_table (catch $lisp-cond 0) (catch_all 1)`; the landing takes the payload as the inner block's
  result, splits it into `__uc_cond$N`/`__uc_msg$N` and compiles
  `(%warn (%string-concat "Unhandled condition: " (if cond (or (%condition-report-str cond) m) m)))`
  with `m` = `(if msg msg "NIL")`: a message is a string, a character vector or nil, and a nil one is
  the message's VALUE -- `(error 'simple-error :format-control nil)` prints `NIL` on all four
  backends (`standalone:` `uncaught-nil-message-report`). Until 2026-09-28 the whole text was
  guarded to `""` instead, because a narrowed-away message was a nil cdr too; the retry above
  removed that case. `%warn` is the existing fd-2 writer, exempt from the lazy-message narrowing. Then `unreachable`: the exit CLASS
  every host and test expects is the trap. The try_table's own `end` restores a reachable, empty
  stack while `block $cond` owes an `eqref`, so an `unreachable` sits between them. **Export wrappers
  keep the catch_all-only landing** -- a host call's failure is the host's to report.
- **It is a FIFTH producer of the reserved `*error-output*` handle**, one the compiler SYNTHESIZES in
  Pass 2 and invisible to any scan of user source, so `--component --optimize` pruned
  `wasi:cli/stderr` out from under it. `WasmUncaughtReportCompiler.emittedFor(ehMode)` is now the ONE
  predicate: `WasmLispCompiler` gates the pad on it and ORs the same value into
  `WasmComponentBuilder.Narrowing`'s `reachesStandardError`
  ([standard-output-redirect.md](standard-output-redirect.md)).
- **Outside EH mode nothing changes, byte for byte**, unless `--report-locations` turns the report
  on (`SignalMessages.ENTRY_REPORT`, below); reporting there by default means turning EH mode on for
  every program (121,572 -> 175,486 B on the two-line toy, 2026-08-14). `--no-gc` is exempt outright; a
  `--no-wasi` reactor compiles the pad but writes into the discarding `fd_write` sink, which is why
  `doc/{en,ja}/guides/wasm-gc-module.md` still says a load-time failure there is a bare
  `RuntimeError: unreachable`. Worst-case cost on zlib `--optimize=size`: 72,837 B ->
  `condMessagesObservable` 76,812 -> broad routing gate 85,391 (+17.2%), taken deliberately since
  the first step alone printed `Unhandled condition: ` and nothing else. Narrowing it to the
  classes that can ESCAPE buys nothing: without a catching form every constructible class
  escapes, which is what `conditionNarrowing` already keeps. What `ENTRY_REPORT` narrows instead
  is the printing operators (below). The function-control arm narrows the same way in EVERY
  mode (`.todo/988`, below): a declined renderer drops it whether or not `--report-locations`
  is the reason EH mode is on.
- **A class that reports nothing is reported by its SIGNAL SITE** (`(define-condition c (error) ())`,
  a bare `type-error`, `simple-error` with no control): `%condition-report-str` answers nil, and the
  text -- `Condition (C :INITARG v) was signalled.` for a typed signal, `Condition of type C was
  signalled.` for `(error c)` -- is the fallback half of the site's `conditionReportOr` message, which
  `compileCond` compiles into the payload cdr and the pad prints when the report is nil. **The text is
  built in ONE place, the expansion** (`legacySignalMessage`, `expandObjectSignal`'s `typeMessage`),
  and every backend prints what it evaluates to: a change to its shape changes all four together.
  Chosen over a fallback arm in the pad because the pad sees only the instance, and the typed text
  names the initargs AS WRITTEN (`:DATUM "abc" :EXPECTED-TYPE INTEGER`), which a built instance
  cannot reproduce. A COMPUTED type (`(error ty :code 42)`, the `%error-runtime` helpers) has no
  initargs as written -- its helper reads every initarg slot out of the call's plist -- so its text
  lists the plist itself (`(cons 'type plist)`), and a `:format-control` is the message only when
  the plist carries the key (`(eq (getf plist :format-control plist) plist)` says it does not): the
  text the interpreter prints, since it rebuilds the literal call. Until 2026-09-27 the helper took
  `(getf plist :format-control)` as the message whenever the class had the slot -- `NIL` on the
  JVM -- and listed unpassed slots (`:A #<%UNBOUND%>`) otherwise
  (`ComputedConditionTypeReportFixture`). A class that INHERITS a report builds no fallback at all
  (`inheritsConditionReport`): it always renders, and the fallback was dead code on every backend.
  Measured 2026-09-26 on e6e49385b (EH mode, bytes): zlib `--optimize=size` 89,623 -> 89,734 (+111),
  `--optimize` 117,008 -> 117,119, component 93,712 -> 93,819; `postgres-hello --component
  --optimize` 2,708,177 -> 2,700,709 (-7,468, the dead fallbacks), `postgres-crud` 2,783,155 ->
  2,776,452; a toy `(error 'c)` 22,547 -> 25,917 -- the `~s` of the initarg list brings the
  printer.
- The `(error c)` text named the class through `prin1` of its `%class-` tag, which escapes since
  symbols print with `|...|`: `Condition of type -C| was signalled.` on the interpreter and the JVM
  until 2026-09-26; it reads `symbol-name` now.
- Pinned cross-backend by `ci-spec.yaml`'s `standalone:` list -- a section `CiSpecE2eTest` runs one
  program at a time, per backend; the corpus cannot host these since running one ends the program.

## Location lines on wasm-GC (`--report-locations`)
**Invariant: `--report-locations=line` makes a wasm-GC module (Preview 1, `--component`,
`--native`) print the interpreter's report and location lines, byte for byte -- turning the report
on for a program outside EH mode too; `=function` prints the function and the line its definition
starts on. OFF BY DEFAULT, and a module with no file-read form (`-e`), no option, or no
report anywhere to print to (a `--no-wasi` reactor outside EH mode) is byte-identical to a build
that never knew about it.** `codegen/wasm/WasmUncaughtLocations`; pinned
by `cli/WasmReportLocationsTest` (each case against the interpreter's own output) and
`e2e/NativeOutputE2eTest`.

- **Opt-in because size decides it** (the user's call, 2026-09-26): the lines cost bytes in every
  function read from a file, and outside EH mode the report itself costs the EH machinery.
- **Outside EH mode the option turns EH mode on, as `SignalMessages.ENTRY_REPORT`**
  (`WasmLispCompiler`'s `entryReportOnly`: the option, no EH trigger, and a located form --
  `WasmUncaughtLocations.readsAnyFile` -- but never a `--no-wasi` reactor, whose standard error is
  a discarding sink). The entry pad is then the only reader of a condition, so
  one thing EH mode proper carries is dropped: the printing operators route a condition
  through its report only when program code can HOLD one (`ClosRegistry.printsConditionReports`,
  the `mayHoldConditions` answer) while the renderer itself stays on the `mayCreateConditions` one
  (`routesConditionReports`).
- **A declined renderer drops `%format-condition`'s FUNCTION-control arm in every mode, not only
  under `ENTRY_REPORT`** (`.todo/988`, 2026-09-26): the arm is a funcall of a runtime value, which
  in a program that can make a symbol at run time (`read`) keeps every built-in dispatchable by
  name, whatever narrowed EH mode is on for. The first version of this narrowing kept the arm in
  plain EH mode (`SignalMessages.RENDERED`) to hold `--report-locations` byte-identical to a build
  without the option; the user approved dropping that condition for this change and its size
  effect is recorded here instead. `(print (read))` plus `(ignore-errors nil)`,
  `--optimize=size`: 245,858 -> 47,570 B (`without the catching form`: 39,027 B, unaffected either
  way). Under `ENTRY_REPORT` (`--report-locations=line`, no catching form) nothing moves --
  42,326 B before and after -- since that arm was already dropped there. `zlib` (chipz, EH mode via
  `catch`/`throw`, no option): `--optimize=size` 89,499 -> 89,326 B (-173, -0.19%), `--optimize`
  116,920 -> 116,513 (-407), `--optimize=off` 462,894 -> 462,464 (-430), `--component --optimize=size`
  93,557 -> 93,382 (-175); gzip -9 `--optimize=size` 29,971 -> 29,924. `hello_world`/`pi_approx`
  `--optimize=size` (no catching form, not in EH mode) unchanged: 480 / 1,489 B. Measured on this
  worktree's HEAD (`ea5e69304` + the change), wasmtime 49.0.0, Linux.
- **A frame** = a defun, lambda, top-level chunk or `--component` resume whose OWN code (outside
  the lambdas it builds) has a form read from a file. It wraps its body in `block` +
  `try_table (catch $lisp-cond)`; the landing calls `_uncaught_note(payload, file-id, line, name,
  hop)`, which records and hands back the SAME payload, and rethrows it. Library source, macro
  output and the Preview 1 `(%async-run (lambda ...))` wrapper defun are not frames, exactly as the
  interpreter's frames without a located form note nothing -- except an ASYNC BODY with no located
  form (`rontolisp:then`'s async lambda, a library `async-defun`), a frame that notes its hop and no
  position (`Spec.hopOnly`): the interpreter prints that hop, with no await site when the await
  that crossed it is library code too.
- **The note is keyed by payload IDENTITY** (a global holding the payload it describes): a
  condition a `handler-case` caught cannot leak into a later report, and a rethrow (unmatched
  clause, `await` re-signalling a rejected future -- both rethrow the same payload) keeps the
  inner frames' note. Rules = `ConditionTrace`'s: the first frame with a line gives the location
  and, by its own name, the function (a lambda's frame is named after the function it is written
  in, `Ctx.ucWrittenIn`; a nested `defun`'s after itself); an async body (a hop text in the
  frame) appends a hop whose await site is the next frame with a line. So every rethrow keeps the
  payload: `%hb-guard` stores the instance it synthesized into the payload it caught, and turns its
  cdr into the `(nil . message)` saying the handlers ran, rather than consing a new one (under the
  option only, so the bytes without it stay) -- a fresh payload lost every line a `handler-bind`
  handler's own frame had noted.
- **A landing pad inside a frame notes first** (`notePad`): its code moves the line local, and its
  refresh ([wasm-landing-pad-refresh.md](wasm-landing-pad-refresh.md)) would put the local back to
  the region's entry -- so the frame's catch saw the `unwind-protect`'s, the special `let`'s or the
  unmatched `handler-case`'s line instead of the signalling form's. The position locals (line,
  file, owner: i31s, which the collector never moves) stay out of the pad's push
  (`positionSlot`); the pad notes what they held at the throw, then sets them to what the static
  track says (`resyncPad`) for the code after it. A caught condition's note is harmless (keyed by
  identity); a later await rewinds it (below).
- **An await rewinds the note** (`--component`, and Preview 1 over its failed futures): the poll of
  a rejected future (`_p1_future_await` of a failed one) calls `_uncaught_reawait(future, payload)`
  before re-signalling. The FIRST await of a future records a snapshot -- `(last-hop site file line
  . name)` -- in an association list keyed by the future (nothing but an await of it can have noted
  its payload since the rejection); each later one restores it. A fresh note clears the list.
- **Texts ride with their frame**: the name and hop text are unspelled string literals built in the
  frame's own landing (`compileUnspelledLiteral`: a spelled one would arm the dispatch gate), so
  the shaker drops them with the frame. The name is the program's spelling
  (`UncaughtReport.functionName`, the mapping every backend uses: a method body names its generic).
  A first version kept a quoted name TABLE: every defun's
  name stayed after its function was shaken -- zlib +4,489 B instead of +3,856. Files are the one
  table (quoted list, sealed when the entry's render compiles; the prescan of the program is what
  makes it complete, since every Pass 2 rewrite inherits an existing cons's position).
- **The line**: an i31 in an eqref local (mirrored by a resume's spill array, so it survives a
  suspension), set on entry to a located form when it holds another value and restored after it
  (static tracking in `Frame.curLine`; the function's tail form restores nothing). Starts NULL, not
  at the definition line: a frame that entered no located form leaves the location to its callers,
  as the interpreter does. Printed by `_uncaught_digits` (i31 -> string via `_str_fresh`): the
  general printer (`%princ-to-string`) pulled 20 KB into a module that never prints
  (`examples/net/http-handler.lisp` 2,171 -> 23,868 B; now 2,769).
- **Tail calls**: a `return_call` leaves the try_table. Inside a frame a direct one stays one only
  into another frame (`tailCallOp`); one through a function value tests the callee at run time
  (`emitValueCall` -> `_uc_frame_p`, a test over the frames' funcId ranges or a `br_table` when
  shorter, written once Pass 2c knows every frame): into a frame a `return_call`, into anything
  else (a built-in, library code) a plain `call`, so the frame notes the `funcall` the interpreter
  would. An async body's frame never tail-calls out: it must open its hop. Disabling tail calls in
  frames outright was the first version and broke the `.kb/wasm-tail-calls.md` invariant a Scheme
  loop depends on (named `let` overflowed); a plain `call` through every function value would too.
- **A fused integer tree** ([wasm-int-fusion.md](wasm-int-fusion.md)) is one form to the frame,
  but its fallback -- the one path where an operation signals -- marks each operation
  (`enterOperation`): its own line, and for one from an inlined defun's body that defun, through an
  owner local (an i31 index into the frame's list of inlined functions) the frame's catch
  dispatches on for file, name and, under `function`, definition line. The fast path bails
  rather than signalling, so it marks nothing.
- **Preview 1 async**: an EH-mode module settles a FAILED future when the body signals, and the
  await rethrows its payload ([async-await.md](async-await.md)), so the hop names the await's line
  as on every other backend.
- **Measured 2026-09-26** (macOS arm64, wasmtime 49): the 35 single-file programs of
  `UncaughtReportParityTest`, `RontoLispCliStreamsTest` and this suite, plus 39 aimed at the shapes
  above (multi-file and cross-file inlining among them), each with `(print (ignore-errors nil))`
  appended -- every location line matches the interpreter's on Preview 1 and `--component`. What
  still differs is not a location: a raw trap (a typed loop's out-of-range `aref`) prints no report
  at all, the three-point spectrum above; a `handler-bind` handler that fails in a built-in reports
  a different condition on each backend (`.todo/994`); and `~a` of a handler's condition prints its
  slots on the JVM and wasm-GC (`.todo/995`).

Cost in EH mode, measured 2026-09-26 on 2f611be61 plus `.todo/991`'s change (wasmtime 49.0.0,
node 24, macOS arm64); the change itself cost zlib `--optimize=size` +351 B `function` / +358 B
`line`, `--optimize` +351 / +666 (the fused fallbacks' marks), the 101 functions +116 (a `throw`
in each landing where `unreachable` was), the one-function programs +14-15:

| module | flags | off | `function` | `line` |
| --- | --- | --- | --- | --- |
| hello_world + `(ignore-errors nil)` | `--optimize=size` | 1,214 | 1,361 | 1,386 |
| pi_approx + `(ignore-errors nil)` | `--optimize=size` | 2,293 | 2,439 | 2,512 |
| zlib (chipz, 51 frames) | `--optimize` | 116,920 | 122,115 (+4.4%) | 128,129 (+9.6%) |
| zlib | `--optimize=size` | 89,499 | 94,685 (+5.8%) | 100,626 (+12.4%) |
| zlib | `--component --optimize=size` | 93,557 | 98,717 | 104,665 |
| zlib | `--optimize=off` | 462,894 | 468,828 | 476,221 |
| 1 three-line function | `--optimize=size` | 7,426 | 8,057 | 8,107 |
| 101 three-line functions | `--optimize=size` | 14,092 | 18,754 | 20,765 |

zlib's `line` column was 93,774 (+6.6%) when first measured the same day: the growth came in with
upstream commits before 2f611be61 (that commit alone gives 100,268), not from this table's change.

Outside EH mode (`ENTRY_REPORT`), measured 2026-09-26 on da01b1a51 (wasmtime 49.0.0).
"forced" is plain EH mode forced by the option (`RENDERED`), the first shape tried:

| module | flags | off | forced (`line`) | `function` | `line` |
| --- | --- | --- | --- | --- | --- |
| hello_world | `--optimize=size` | 480 | 660 | 649 | 660 |
| hello_world | `--component --optimize=size` | 1,635 | 2,000 | 1,989 | 2,000 |
| pi_approx | `--optimize=size` | 1,489 | 1,779 | 1,740 | 1,779 |
| pi_approx | `--optimize` | 2,410 | 2,697 | 2,658 | 2,697 |
| `(parse-integer (read-line))` in a defun | `--optimize=size` | 6,350 | 12,583 | 12,566 | 12,583 |
| a typed condition, inherited `:report` | `--optimize=size` | 2,898 | 26,829 | 20,216 | 20,232 |
| a typed condition, inherited `:report` | `--component --optimize=size` | 3,882 | 30,733 | 24,056 | 24,072 |
| `(print (read))` | `--optimize=size` | 34,981 | 245,561 | 42,396 | 42,404 |
| `(print (read))` | `--optimize` | 35,920 | 300,416 | 43,324 | 43,332 |

- The rest is what a report needs: the throw path and the pad (~170 B on hello_world, where
  nothing can throw), and each signal's message -- `parse-integer`'s names its string, so the
  string printer comes in (+6.2 KB). The typed toy's `off` is a folded trap.

- Fixed: ~140 B (the note and digit helpers, the render). Per frame: ~39 B raw / ~9.5 B gzip under
  `function` (the try_table wrapper is 12, the note call ~20, the name ~5 of data); `line` adds
  ~6.5 B per line change (3-line bodies: +20 B). gzip, zlib `--optimize=size`: 29,146 -> 30,416 ->
  31,221.
- Run time (bench-report programs + `(ignore-errors nil)`, best of 5 -- the EH mode the option now
  turns on by itself for these programs): **V8 within noise** (every
  program +-4%); **wasmtime: fib +220%**, matmul +13-15%, mandelbrot +8-13%, the rest 0-7%. The
  cost is the try_table around calls in Cranelift (`function`, which sets no line, pays the same),
  so a tiny recursive function pays the most. **Re-evaluate if** wasmtime's exception lowering
  stops spilling across calls inside a try_table -- or if the option is ever meant to stay on in
  production, in which case the per-call catch is what to replace.

## cerror, signal-operator function values, runtime type dispatch
- `cerror` has TWO lowerings on the restart-mode gate: outside it `expandCerror(cons, registry)`
  drops the control and emits `(error datum args...)` (behavior-identical, since nothing could invoke
  a `continue` restart); in restart mode `expandCerror(cons, registry, true)` emits the REAL
  `(restart-case (error ...) (continue () :report continue-format nil))`.
- `error`/`signal`/`warn`/`cerror` also have FUNCTION values. Interpreter: `registerEval` defines
  real functions that rebuild the literal call from evaluated arguments (`rebuildSignalForm`) and
  re-enter `eval`, `resolveFunction` checking the function namespace BEFORE the
  macro/special-operator guard; a NON-literal `error` datum evaluating to a SYMBOL re-dispatches as a
  condition-type designator (`expandError`'s `runtimeTypeDispatch`). Compiled:
  `BuiltinFunctionWrappers.SIGNAL_FUNCTIONS` wrappers injected ONLY under a literal `(function op)`
  reference (`referencesFunctionValue`), forwarding the datum only.
- **A NON-literal `(error TYPE args...)` datum WITH initargs dispatches on the COMPILED backends
  too** (jzon's `%raise`, cl-postgres' `(error (get-error-type code) :code ...)`). NOT inlined at the
  call site -- at 165 registered classes the per-class expansion reached 90 KB in one method, past
  the JVM's 64 KB limit. The site lowers to `(%error-runtime datum (list args...))` and
  `expandTopLevelDefinitions` injects one construction helper defun per registered CONDITION class
  (`%ERROR-RT-n`, the same `expandTypedSignal` a literal call gets, over `getf` reads with each
  slot's `:initform` as the default) plus the `%error-runtime` dispatch defun matching against both
  the qualified and (when unambiguous) plain spelling.
- **The dispatch is CHAINED** (`%error-runtime` -> `%ER-1` -> ..., the `chainedDispatchDefuns` shape,
  ~600 cons nodes per segment): one `cond` lowers to nested `if`s on the JVM, so past ~140 condition
  classes the outermost arm's else-branch overflowed the signed-16-bit branch encoding. One shared
  shape on all four backends.
- A NON-condition class name and any non-symbol fall to the `expandObjectSignal` arm; the
  interpreter's inline dispatch still constructs ANY class. A DATUM-ONLY non-literal call keeps the
  object-designator path (constructing a slot-less instance would run its `:report` over nil slots).

## Phase 4 -- handler-bind + the restart stack
**Invariant: the restart system is ONE shared Lisp-level lowering in `LispMacroExpander`, identical
on the interpreter, the JVM and both wasm-GC backends.** No backend has a per-form compiler class; a
divergence can only come from the primitives underneath (`catch`/`throw`, `unwind-protect`, globals,
closures), all pinned cross-backend. `--no-gc` keeps the lite lowering.
- **The six operators the lowering serves**: `restart-case`/`restart-bind`/`with-simple-restart`
  expand via `LispMacroExpander.expandRestartCase`/`expandRestartBind`/`expandWithSimpleRestart`;
  `find-restart`/`invoke-restart`/`compute-restarts` are runtime defuns assembled by
  `LispMacroExpander.restartRuntimeForms` (injected by `expandTopLevelDefinitions` at compile time,
  `ensureRestartRuntimeLoaded()` at interpret time).
- **Two dynamic stacks, both TOP-LEVEL GLOBALS** (`%HANDLER-CLUSTERS%`, `%RESTART-CLUSTERS%`,
  injected as `defvar`s), mutated with plain `setq`
  and restored through an `unwind-protect` cleanup over a LEXICALLY saved value. Plain `setq` +
  cleanup rather than special-`let` rebindings, chosen when the compile paths still skipped the
  special-binding restore on the error-throw, `catch`/`throw` and cross-lambda `return-from`
  channels. Those holes are closed -- a special `let` is now itself an unwind-protect region
  ([dynamic-special-variables.md](dynamic-special-variables.md)) -- so a `let` would work too; the
  `setq` shape stays because it is pinned cross-backend and a rewrite buys nothing.
- **The restart transfer rides `catch`/`throw`** with a FRESH cons as the tag (`(list '%restart)`),
  so tag identity is `eq` and cannot collide with a user tag -- which buys crossing function
  boundaries, running intervening cleanups, and passing through `handler-case` regions uncaught.
- **Clause bodies are compiled INLINE in the dispatch, never wrapped in a lambda** -- what makes
  postmodern's `transaction.lisp` shape work: the clause body `(go start)` targets a tagbody of the
  SAME function and stays a plain goto/br. A lambda wrapper would push every retry clause onto the
  cross-lambda `go` lowering.
- A restart record is the list `(%restart name invoker report interactive test)`; the invoker takes
  the argument LIST and is called with ONE fixed-arity `funcall` -- `apply` would drag the WASM eval
  runtime into every restart program.
- `handler-bind` pushes one cluster of `(type-test-closure . handler)` entries. **`%run-handlers`
  walks the clusters and, per CLHS, rebinds the global to the REMAINING clusters while a cluster
  runs**, so a handler that itself signals does not re-enter its own cluster; a handler that returns
  declines and the walk continues.
- **The signal hook.** `expandError`/`expandWarn`/`expandSignalMacro` take a `signalHook` boolean;
  when set they insert `(%run-handlers <instance>)` BEFORE the `%error-cond`/`%signal-cond`/`%warn`
  terminal, so handlers run at the signal point with the signaling frame's restarts still established
  (restart-mode `warn` is wrapped in a `muffle-warning` `restart-case`). **Every restart-mode signal
  terminal CARRIES the instance the hook just ran**: the string-designator error arm throws
  `%error-cond` instead of `%error`, and `expandObjectSignal`'s string/symbol arms bind their fresh
  instance (`__signal_inst`) and hand it to both `%run-handlers` and the terminal -- so a
  `handler-case` catches the instance the handlers saw. The terminal also says the handlers ran
  (a third operand `t`: "Whether the handlers ran rides the flight" below).

### Errors BUILT-INS raise run handler-bind handlers too
Rove's failure-recording model is `handler-bind` around USER code, so `(car 1)`, an out-of-range
`aref`, `(/ 1 0)` and an undefined function must reach the handlers.

- The `handler-bind` expansion wraps its body in the internal `(%hb-guard body)` landing pad,
  compiled per backend (`JvmHandlerCaseCompiler.compileGuard`,
  `WasmHandlerCaseCompiler.compileGuard`, `LispEvaluator.evalHbGuard`): a region that synthesizes the
  `simple-error` of a condition-less throw, runs `%run-handlers` -- the FULL cluster stack from the
  innermost, CLHS rebinding included, so ONE pad run covers every enclosing cluster -- and rethrows
  CARRYING the instance and saying the handlers ran, which every pad further out reads and passes
  on. The pad never touches the hc-depth channel,
  has no cleanup (no `UnwindScope`, no trampoline), and does not catch the block-exit tag.
- **A handler's own call runs in a pad too** (`runHandlersDefun`: `(%hb-guard (funcall handler
  c))`), while `%handler-clusters%` holds the REMAINING clusters. CLHS 9.1.4.1 runs a handler with
  its cluster disabled, so a built-in failing inside it is walked there, against the enclosing
  clusters only; the handler-bind's own pad then rethrows it untouched. Without it the
  failure escaped the walk's cleanup with the full stack restored and the handler-bind's pad RAN
  THE FAILING HANDLER AGAIN on the `type-error` (JVM and both wasm-GC, until 2026-09-26). Pinned by
  ci-spec `restart-system` (the output) and `failing-handler-bind-handler-report` (the report).
- **The interpreter ADDITIONALLY runs handlers at the SIGNAL POINT for built-ins**:
  `LispEvaluator.apply` wraps `builtIn.body().apply` and, on an escaping `LispEvalException` -- or a
  raw `IndexOutOfBounds`/`NegativeArraySize`/`Arithmetic`/`ClassCast` wrapped into one first
  (`builtinFailureMessage` names the built-in when the raw message is letterless) -- reuses or
  synthesizes the condition and runs the walk BEFORE unwinding, so restarts established below the
  handler-bind are still invocable. Zero cost until an exception escapes.
- **Deviations**: on the COMPILED backends a raw error's handlers run at the `handler-bind`
  boundary -- intervening cleanups have run and restarts below it are gone (CL runs handlers first);
  a SIGNALED condition keeps exact signal-point semantics everywhere. wasm-GC runs handlers only for
  `$lisp-cond` throws, so **a rove test whose body traps still ends a wasm run**.

### Whether the handlers ran rides the flight
**Invariant: a condition's `handler-bind` handlers run ONCE per signal, whatever else is signalled,
handled, declined or abandoned while it is on its way out -- because whether they ran is carried by
the condition's own throw, never by a global.** Identical on all four backends; pinned by
`HandlersRunOnceFixture` through `LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest#handlerBindHandlersRunOnceWhileACleanupSignals` (every row, SBCL's
output) and ci-spec `handlers-run-once-while-a-cleanup-signals` (one row per mechanism, the report's
aside, over standard condition classes: the shaker corpus class's constant pool stood at 51,957 of
its 52,000 tripwire with it, 51,891 without -- the eight rows over four `define-condition`s took
52,138).

- **Who says so**: the hook's terminal, `(%error-cond c msg t)` / `(%signal-cond c msg t)`
  (`LispMacroExpander.handlersRanTerminal`) -- reaching it proves the walk completed, since a walk
  that did not transferred control, so nothing after the hook (a report rendered into the message
  included) can take it back -- and a pad that walked, on its rethrow. Every other throw (a raw
  failure; the unhooked `%error-cond` of `%program-error` / `%file-error` / `%package-error`) is
  walked by the first pad or interpreter seam it crosses. A hooked site that forgot its operand
  fails SAFE: walked again, never skipped.
- **Where it rides.** Interpreter: `LispEvalException.handlersRan()`, read by every seam through
  `withHandlerBindHandlersRun` (`apply`, the funcall seam, `%async-run`, `evalHbGuard`). JVM: the
  record under the throwable -- `_condRan(t, c)` records `{_condTl, c}` (no Lisp value can hold the
  private `_condTl`), `_condOf` answers the condition either shape names; the pad passes such a
  record on as it came, and a restart-mode `handler-case` landing that declines puts back the record
  AS TAKEN (it rides the result slot, which nothing writes on the landing path before a clause
  answers); what only carries a record (future, join, `_jsig` / `_jfail`) carries it unchanged.
  wasm-GC: the payload `(instance . (nil . message))` -- a cdr that is a cons, which no message is (a
  string, a character vector, nil); the pad tests it with one `ref.test` and rethrows such a payload
  untouched, a declining `handler-case` rethrows the payload it caught, and the entry report takes
  the message out of it (restart mode only).
- **Before (until 2026-09-27)**: one global, `%handlers-ran%`, set by `%run-handlers` at the end of
  every completed walk and compared by `eq` at each pad. A condition signalled while another was on
  its way out replaced it -- handled in an `unwind-protect` cleanup, declined there (a `signal` no
  handler takes), abandoned by a `return-from` out of an inner cleanup, handled inside a `:report`
  while the message was built (interpreter and JVM; wasm-GC renders reports lazily), handled between
  the two pads -- and the outer condition's handlers ran twice on all four backends, the interpreter
  also for a raw failure handled in a cleanup (it walks a built-in's failure at the signal point).
  Weighed and dropped: saving the mark around a handling `handler-case` misses the declined and the
  abandoned cases; several marks need a bound and outlive a served request; a per-instance flag
  changes every condition layout, and a re-signalled instance is a new flight.
- **Cost, measured 2026-09-27 against develop at `20a17362c`** (wasmtime 49.0.0). Outside restart
  mode byte-identical (`(ignore-errors (f 1))`, `examples/console/error-handling.lisp`,
  `examples/console/calc.lisp`, `examples/net/httpbin.lisp`; JVM, Preview 1 default and
  `--optimize=size`, component). Restart mode, bytes before -> after:

  | program | JVM class | wasm P1 | P1 `--optimize=size` | component |
  |---|---|---|---|---|
  | `handler-bind` over `(ignore-errors (error "x"))` | 41,499 -> 41,634 | 16,420 -> 16,390 | 22,958 -> 22,872 | 17,899 -> 17,869 |
  | a `restart-case` a handler invokes | 41,440 -> 41,563 | 27,528 -> 27,441 | 24,902 -> 24,816 | 29,067 -> 28,980 |
  | the ci-spec pin as a program of its own | 104,177 -> 104,466 | 56,011 -> 55,836 | 49,765 -> 49,591 | 59,738 -> 59,563 |
  | `examples/net/hello-clack.lisp` | 953,749 -> 953,801 | 767,972 -> 768,890 | 602,635 -> 603,553 | 888,479 -> 889,458 |

  JVM: `_condRan` (21 B of code) and `_condOf` (40 B) once, ~10 B per restart-mode `handler-case`
  landing; a throw site keeps its size. wasm-GC: the global and the pad's `eq` go, a hooked throw
  site grows by the wrapper cons (~5 B; hello-clack has some 180). Two traps met on the way: the
  message rode the wrapper's CAR first, a string where the type-test fold
  ([wasm-ref-type-fold.md](wasm-ref-type-fold.md)) had proved no cons car holds one, and the string
  and sequence runtime it prunes came back (+1,066 B on the first row); the JVM landing first
  unwrapped before its inline synthesis, whose branch targets then all carried the record in their
  frames (+804 B of StackMapTable on the pin's program; +40 B unwrapped after it). **Re-evaluate
  if** the per-site wrapper matters: a hooked site whose message is nil could throw a shared
  `(nil . nil)` global instead.

### A `handler-case` joins the cluster stack, so it SHADOWS an enclosing `handler-bind`
**Invariant: CLHS 9.1.4.1 -- handlers run MOST RECENT FIRST and `handler-case` transfers control, so
a `handler-case` established inside a `handler-bind`'s extent handles the condition and the enclosing
handler-bind handler never runs.**

- `LispMacroExpander.handlerCaseProtectedForm` wraps the PROTECTED FORM (only it) in the same
  `let`-saved / `unwind-protect`-restored push `handler-bind` uses, pushing one cluster of
  `(type-test-closure . nil)` entries. **The nil cdr is the marker**: a nil handler is a handler-case
  clause, which HANDLES by transferring control, so `%run-handlers` stops its walk there
  (`__rh_stop`). The transfer is the ordinary throw the signal terminal performs immediately after,
  so no backend needed a new control path.
- **Wrapping only the protected form is what pops the cluster for the clause bodies**, so a clause
  body that signals is not caught by its own handler-case and reaches the enclosing handler-bind.
- **Three call sites = the three handler-case implementations** (`LispEvaluator.evalHandlerCase`,
  `JvmHandlerCaseCompiler.compile`, `WasmHandlerCaseCompiler.compile`), each passing its own
  restart-mode flag (`restartRuntimeLoaded` / `ctx.restartMode`). **Restart mode is the gate**: with
  no `handler-bind` anywhere there is no cluster stack to shadow, so every other program is
  byte-identical. `ignore-errors` inherits it.

### The restart-mode gate
**`LispMacroExpander.usesRestartSystem(program)`, computed on the SURFACE program** (the four macros
plus a call to / `#'` reference of a restart-runtime function). The scan matches those names in
OPERATOR POSITION of evaluated forms only: it recurses into sub-forms, never into spine cells, skips
`quote`d data, ignores keyword heads. **The old spine-walking scan read ANY occurrence as an
operator, so chipz's bzip2 decoder, whose `tagbody` has a tag named `CONTINUE`, put every program
that loads chipz into restart mode** (~7 KB on the zlib row). A binding pair or clause head spelling
a restart name still over-approximates to true (the safe direction).

It must be computed before `expandTopLevelDefinitions` (which re-runs it to inject the runtime defuns
and the two globals) and threaded into: the JVM `blockExitChannel` / WASM `blockExitTag` (the
expansions ride `catch`/`throw`, and on WASM that also implies EH mode), `mayUseInstances` (the hook
constructs `simple-*` instances), and `Ctx.restartMode`. **All of these pre-scans run before Pass 2,
where the expansions happen, so none of them can see the expansion products -- that is why the gate
is a separate surface scan.**

- **Trap (closed)**: the WASM chunked top level clones `Ctx` through `WasmAsyncEmit.freshCtx`, which
  enumerated flags EXPLICITLY. Without `restartMode` there, top-level chunks compiled the signal hook
  OFF while defun bodies had it ON, so a `handler-bind` at top level silently never ran its handlers.
  Since 2026-10-04 it inherits every `Ctx.Builder` field by construction
  ([wasm-function-body-size.md](wasm-function-body-size.md)).
- Interpreter: no injection pass, so `ensureRestartRuntimeLoaded()` evaluates the same generated AST
  on the first restart-system form or the first resolution of a restart-runtime name. The flag
  doubles as the signal-hook gate; the interpreter re-expands per evaluation so later signals pick
  the hook up.
- **`FreeVarAnalyzer` learned all four macros** (expand-before-walking); `handler-bind` uses
  `expandHandlerBindForAnalysis`, substituting `t` for the clause type tests so an unknown or
  compound type spec cannot reject the analysis.
- Lite deviations (on the doc pages): `&optional` clause parameters take nil rather than their
  default, no condition-restart association, restart records print as plain lists,
  `:report`/`:interactive` stored but never rendered/run (no debugger),
  `check-type`/`assert`/`ccase`/`ctypecase` offer no `store-value` restart.
- `use-value`/`store-value` share `LispMacroExpander.valueRestartDefun`: invoke the innermost restart
  of that name with ONE value, nil when none is active. In `RESTART_RUNTIME_FUNCTION_NAMES` and
  `PackageRegistry.CL_FUNCTIONS`.
- **ci-spec `restart-system` puts the whole concatenated program into restart mode**, so a hook
  regression shows up as an unrelated case failing.

### `signal` declines a handler-case no clause matches -- on every backend
**Invariant: CLHS 9.1.4.1 -- `signal` transfers control only to a handler that will handle the
condition. A `handler-case` whose clause types do not match is not applicable: the signal passes it
by, returns nil so the forms after it run, and the handler-case stays armed for a later matching
condition -- identically on all four backends.** The compiled backends used to approximate with the
handler-DEPTH counter alone and turned an unmatched decline into a top-level abort.

- The clause types ride the same cluster stack, now outside restart mode too:
  `handlerCaseProtectedForm` is called by both compiled emitters with
  `ctx.restartMode || ctx.signalClauseMatch`.
- **`%signal-cond` consults the stack**: `Jvm/WasmSignalCondCompiler` still require the depth channel
  to be positive FIRST (depth is per thread of control while the cluster stack is a shared global),
  then call the injected `%hc-match-p` defun -- an iterative walk over `%handler-clusters%` testing
  nil-cdr entries only, because a handler-bind entry never transfers at `%signal-cond` -- and throw
  only on a match. A handler-case with only a `:no-error` clause pushes nothing and is therefore
  declined, which is also CL.
- **The gate is `LispMacroExpander.needsSignalClauseMatch(program)`**: the program contains BOTH a
  `signal` (operator position, or `#'signal`) AND a `handler-case`/`ignore-errors` head -- a surface
  scan with the operator-position discipline, computed by each compiler before
  `expandTopLevelDefinitions` (which re-runs it to inject `%hc-match-p`, prepend the
  `%handler-clusters%` defvar outside restart mode, and disable the no-definitions fast path). A
  program missing either half keeps the historical emission byte for byte; the interpreter is
  untouched. `WasmAsyncEmit.freshCtx` copies the flag.
- Known blind corner: a `signal` or catching form reachable only through a channel the surface scan
  cannot see (runtime `eval`, a computed designator forged from quoted data) keeps depth-only
  behavior.

## A built-in error carries its CONDITION CLASS
`(handler-case (car 1) (type-error ...))` matches, and so does rove's `(ok (signals (car 1)
'type-error))`. **The class is decided where the failure is DETECTED, never by pattern-matching the
message at the catching end** -- except for the failures the backends report as bare text.

- **Interpreter**: `LispEvalException.ofClass(className, message)`;
  `LispEvaluator.synthesizeCondition` (the ONE synthesis point, shared by `handler-case` and
  `%hb-guard`) builds it through `ClosRegistry.newReportingCondition`, which nil-fills the layout and
  puts the message in `format-control`. A raw Java failure escaping a built-in is classified by
  `LispEvaluator.rawFailureConditionClass` at the `apply` seam.
- **JVM**: the landing pad (`JvmHandlerCaseCompiler.emitSynthesizeCondition`) classifies the caught
  `Throwable`: `ClassCastException`/`IndexOutOfBoundsException` -> `type-error`;
  `ArithmeticException` -> `division-by-zero` when its message contains
  `ClosRegistry.DIVISION_BY_ZERO_MESSAGE_TOKEN` else `arithmetic-error`; and -- the message
  exceptions -- text starting `The variable `/`The function ` and ending ` is unbound`/` is
  undefined` -> its cell-error class, because those sites are plain `RuntimeException`s emitted in
  bytecode with no channel to carry a class; a wrong-type operand's exception is recognized by
  identity instead (`_teSlot`, see "A non-number reaching arithmetic"). The arms are compiled Lisp forms built by `LispMacroExpander.reportingConditionForm`, so no
  slot index is baked here. **The synthesis is ONE method per class, `_hcSynth`
  (`(Throwable)Object`, `JvmHandlerCaseCompiler.conditionSynthesizer`, memo
  `ConditionChannel.conditionSynthesizer`)**: every landing and the `_hbGuard` pad call it when
  `_condTake` answers null; the clause dispatch stays inline. Measured 2026-09-27, default
  `--optimize`: `(defun g (x) (ignore-errors (f x)))` `G` 945 -> 513 B code, StackMapTable 211 ->
  155 B; `(defun h (x) (handler-case (f x) (type-error () :t)))` `H` 667 -> 235 B, 211 -> 155 B;
  `_hcSynth` 442 B, `_hbGuard` 518 -> 82 B. The ci-spec corpus class (`--optimize=off`) 7,820,580
  -> 7,608,664 B (code 4,640,417 -> 4,495,219, StackMapTable 1,940,580 -> 1,874,069, methods 6,143
  -> 6,139 -- fewer body-outliner continuations); `examples/net/hello-clack.lisp` 958,556 ->
  956,748 B. **The constant pool barely moves** (corpus 51,957 -> 51,945): a landing adds no pool
  entry of its own -- every constant it names is already interned class-wide. What fills the
  corpus pool: 10,057 `String`s, ~6,100 self `Methodref`s (each with its own `NameAndType` and
  name), and 2,755 `_qd$N` quoted-datum fields (three entries each).
- **WASM**: the pad is unchanged, and correctly so -- only `$lisp-cond` throws land in it. **An
  undefined-function call diverges by CLASS rather than catchability**: catchable, but as a
  `simple-error`. **The stub cannot construct the typed instance**: it is produced during BODY
  compilation, after `mayCreateInstances` fixed whether the artifact has an instance representation
  and after `usedLayoutTags` chose which layouts to bake (`%OBJ-NEW reached the compiler with no
  instance representation`). **Trigger: teach both gates about undefined calls** -- the precedent is
  the non-number family ("A non-number reaching arithmetic"), whose `type-error` layout
  `usedLayoutTags` bakes on the pad's presence alone.
- **Undefined functions keep the call-time stub contract**: a call to a name with no definition
  compiles to `The function X is undefined` at call time plus a compile-time warning, matching the
  interpreter's late binding. It stays a STRING signal for the gate reason above.
- **The message a raw host failure reports is rontolisp's, not the host's**:
  `ClosRegistry.TYPE_ERROR_MESSAGE` replaces a `ClassCastException`'s Java class names and
  `INDEX_OUT_OF_BOUNDS_MESSAGE` the JVM's `Index 10 out of bounds for length 3` (whose length counts
  the layout cell in slot 0) -- now only for what still reaches the host's check (a string's
  index, `.todo/186`): an array subscript reports its bound itself ("An out-of-range subscript").
  Per-site texts a built-in writes itself are kept and are NOT identical across backends. The
  numeric operators name themselves by per-operator emission at the call ("A non-number reaching
  arithmetic"), never by message parsing at the pad. The substitution does NOT reach the UNCAUGHT
  top-level line on the JVM -- deliberate.
- **Restart mode moves the undefined-function text out of the pad's reach**: the string-datum `error`
  arm builds its `simple-error` at the SIGNAL point and hands it over on the condition channel, so
  `(handler-case (nosuchfn) (undefined-function ...))` matches on the JVM in a plain program and not
  in a restart-mode one. **Trigger: restart mode is where both instance gates are already open, so
  the stub CAN carry its class there.**
- **`conditionNarrowing` marks the five classes constructible**
  (`LispMacroExpander.rawFailureConditionClasses`): no site names them, but a pad can build one, and
  without the mark a caught `(car 1)` would print as a bare `#<TYPE-ERROR>`. Unlike the simple-*
  three it is CONDITIONAL on the program establishing a pad at all (`LANDING_PAD_HEADS` ->
  `ConditionTagScan.hasLandingPad`); marking them unconditionally cost zlib 680 B.

## A non-number reaching arithmetic signals a catchable type-error
**Invariant: a wrong-type operand reaching a numeric operator signals a CATCHABLE error whose text
is `OP: The value <prin1> is not of type T` -- the operator and the type IT accepts, CL's
`type-error` shape -- byte-identical on all four backends, and the condition is a `type-error`
whose `type-error-datum`/`type-error-expected-type` answer the operand and `T`.** One table, `compiler/OperandTypes`: the named operators and their types (`NUMBER` for
`+ - * / = abs sqrt exp expt ...`, `REAL` for the orderings, `min`/`max`, the rounding family,
`mod`/`rem`, `float`, `random`, `complex`; `INTEGER` for the bitwise family, `gcd`/`lcm`/`isqrt`;
`RATIONAL` for `numerator`/`denominator`), narrowed to `REAL` where a `NUMBER` operator met a complex
that must be real (two-argument `atan`). The table's other operators are FUNNEL-TYPED ("A wrong-type
argument names its operator" below). A failure outside a named operator reports unnamed with the
funnel's own kind. Pinned by `ci-spec.yaml`'s `non-number-arithmetic-operands-are-catchable` and
`operand-type-errors-name-the-operator-wherever-it-compiles`; the wasm class by
`WasmLispCompilerIntegrationTest.ehANonNumberArithmeticOperandSignalsATypeError`.

**Detection stays at each backend's coercion FUNNEL; the operator is attached ONE LEVEL UP**, because
a funnel (and every shared helper above it -- `_cmpb` serves `< > <= >= = min max`) cannot know which
operator it serves:
- **Interpreter**: `Environment.asLong`/`asDouble`/`asBigInteger` and the real checks throw an UNNAMED
  `eval/OperandTypeException`; the built-in seam in `LispEvaluator.apply` names it after the built-in
  whose body raised it (`named`, no-op when already named or not a named operator), and
  `synthesizeCondition` fills `DATUM`/`EXPECTED-TYPE`. Built-ins that reach a funnel through
  `callGlobal`/`applyGlobalFunction` (bypassing the seam) name it themselves: the floor family's
  division (`floorFamilyQuotient`, `evalFloorFamilyDivision`), `requireRealOperand`,
  `roundToInteger`, `float`.
- **JVM** (`JvmOperandTypeRuntime`): every funnel throw is `_teRaw(x, kind)`. `JvmExprCompiler.compileCons`
  sets `Ctx.operator` to the form's head for the form's own emission; `Ctx.numOp` and
  `JvmComplexCompiler.complexOp` hand a wrappable helper (`JvmNumericRuntimeBuilder.wrappedDesc`)
  back as a per-(helper, operator) WRAPPER, built on first use: the same invocation under a catch-any
  entry whose handler throws `_opTypeErr(e, "OP", "TYPE")`, which renames an unnamed report and
  passes anything else through. Zero cost on the normal path (measured: no difference on a boxed
  generic-arithmetic loop). Under a landing pad the thread-local `_teTl` maps each such exception to
  its `{datum, type}`, read by `_teSlot`, which is how the pad's `type-error` arm fills the slots --
  a `RuntimeException` has nowhere to carry an object and a compiled program ships no exception
  class ("The JVM keeps what a throwable carries under the throwable"). Size: +0.8 KB on a
  four-defun class, +2.8 KB on a 116 KB one.
- **wasm-GC, EH mode** (`WasmOperandTypes`): a call to a helper that can reject an operand, compiled
  inside a named form (`WasmOperandTypes.emitCall`, converted at every call site of the arithmetic
  compilers and `castFloatGetF64`), stores the operator's id in the operator register (a
  `(mut i32)` global after the render guards) for the call's duration and clears it after; the
  `_type_err_*` landings read and clear it and look the id up in the operator table, a blob placed
  before any body compiles (**a string interned during emission lands after the data segment is
  fixed and reads back as blanks**), so it holds only the operators the program spells, their
  call-position rewrites, the `+ - * / = < > <= >=` family and the operators a lowering introduces
  (`WasmOperandTypes.LOWERED_TO`); an operator missing there reports unnamed. Boxing, the fused raw
  i64 helpers and fdlibm take no register (`mayReject`), and a call to a landing is not followed by
  the clear (a landing never returns and clears the register itself). Size: +2.3% on a 109 KB EH
  module; a non-EH module is byte-identical. Outside EH mode the landings stay a bare `unreachable`.
- **wasm-GC, one landing body**: `_type_err_int`/`_num`/`_real`/`_list` are 8-byte stubs handing
  their kind to the shared `_type_err(culprit, kind)` (`buildSharedLandingBody`, `TYPE_STR_TO_MEM`),
  which renders every report, so a module reaching several landings carries the rendering once; its
  type chain selects among only the codes a landing kind or a table row can produce
  (`Operators.rowCodes`), so a suffix no row reaches is never cited and drops with the string
  blob's dead ranges.
- **wasm-GC, the class**: the landings throw a `type-error` instance built in raw wasm
  (`WasmOperandTypes.TypeErrorShape`, `WasmRuntimeBuilder.emitConditionThrow` -- the
  `NotFunctionReport` precedent): `format-control` the report, `datum` the operand,
  `expected-type` the symbol the report names. The landing is a fixed helper built after the
  instance gates decided, so the GATE is the pad: EH mode behind a handler landing pad
  (`establishesLandingPad`) with instances on, and `usedLayoutTags` bakes `TYPE-ERROR` on the pad
  alone -- not on the program spelling `type-error`, because `type-of`, `class-of` and a
  `simple-error` clause observe the class without naming it. Without a pad nothing can observe the
  class and the landing throws the message-only `(nil . message)` payload, which the entry pad
  reports identically. **The type symbols are interned as their own names before any body compiles**
  (`Texts.typeNames`): a symbol's identity is its string-table entry, so `(eq (type-error-expected-type
  e) 'number)` holds only because the landing and the program's literal share it -- a view into the
  suffix text would print right and fail `eq`. Size: +106-116 B on zlib with a `handler-case`
  around its body (P1/component, default and `--optimize=size`, measured 2026-09-26); zlib as
  shipped has no pad and is byte-identical. A side effect: `NotFunctionReport`'s `type-error` for
  `(funcall 5)` is typed under any pad now, since it rides the same baked layout.
- **The operator is the innermost FORM being compiled**, so a lowering that re-emits an operator's
  calls away from its form sets it back: the fusion fallbacks per tree node
  (`JvmIntFusionCompiler.numOpFor`, the compare method's mask -> `compareOperator`,
  `WasmOperandTypes.withOperator`), and wasm's condition-position compare
  (`WasmComparisonCompiler.tryCompileConditionI32`, handed the test without `compileCons`).
- **A call-position rewrite reports under the operator it becomes** (`OperandTypes.REWRITTEN`: `1+`/`1-`
  -> `+`/`-`, `zerop`/`plusp`/`minusp`/`/=` -> `= > < =`, `evenp`/`oddp` -> `mod`, `logtest`/`logeqv`
  -> `logand`/`logxor`), because the compiled backends' function values (`#'1+`) ARE the rewrite
  (`BuiltinFunctionWrappers`), so the interpreter's built-in reports what a call does.
- `_int_val`'s limb-tier arm still TRAPS explicitly ([wasm-bignum.md](wasm-bignum.md)'s exact-or-trap
  boundary is about values that ARE integers). The `_as_f64` ladder is float-first
  ([wasm-shared-coercion.md](wasm-shared-coercion.md)). `--no-gc` unaffected, still traps.
- **What still traps on wasm-GC**: the limb-tier boundaries (a left `ash` past the allocation
  guard: `(ash 1 40000000)`) -- and everything outside EH mode. (A division by zero signals since
  2026-10-04: "A division by zero signals division-by-zero".) (The array argument of an access and of an array-shape accessor is named since
  2026-09-27, a vector without a fill pointer handed the fill-pointer surface since 2026-09-28: "A
  sequence, array or hash-table operand of the wrong kind".) (A list walk over a non-list is named since
  2026-09-26: "A wrong-type argument names its operator".)
- **The funnels' reach is wider than arithmetic**: a STORE into a packed float array goes through the
  same `_dbl`/`_as_f64`, and reports under `(SETF AREF)` since 972. Pinned by `JvmFloatArrayTest`'s
  `nonRealStoreIsATypeError`/`singleNonRealStoreIsATypeError` -- the reason to run the WHOLE suite
  after changing a shared runtime helper.
- Pre-existing edge unchanged: a condition thrown from INSIDE a wasm to-string capture leaves the
  capture flag set.

## A division by zero signals division-by-zero
**Invariant: a division by an exact zero -- `/`, the two-argument rounding family, `mod`/`rem`, at
every integer tier and over a ratio, `expt` of an exact zero to a negative integer power -- and the
rounding family and `mod`/`rem` by ANY zero (exact or float) over a finite dividend, signal a
CATCHABLE `division-by-zero` (an `arithmetic-error`) reporting `ClosRegistry.DIVISION_BY_ZERO_MESSAGE`
(`Division by zero`), byte-identical on all four backends (wasm-GC: in EH mode).** `/` and `expt`
with a float operand stay IEEE (`(/ 1.5 0)` is infinity, `(expt 0 -1/2)` too); see "Per operator"
below. Pinned by
`DivisionByZeroFixture` (`LispEvaluatorTest`/`JvmLispCompilerTest`/
`WasmLispCompilerIntegrationTest#divisionByZeroIsACatchableCondition`, P1 and component),
`ci-spec.yaml`'s `division-by-zero-is-a-catchable-condition` and `clojure-spec.yaml`'s
`catch-takes-a-division-by-zero-as-an-arithmetic-exception` (oracle-identical); the uncaught report
by `ehAnUncaughtDivisionByZeroReportsBeforeTrapping`.

- **Interpreter**: `mod`/`rem`'s integer arms check the divisor (`Environment.nonZeroDivisor`);
  they left it to Java's `%` / `BigInteger.remainder`, so the class was right (the pad's message
  token) but the text was the host's `/ by zero` / `BigInteger divide by zero`.
- **JVM**: `_mod`/`_rem` check the divisor on every arm (`JvmNumericRuntimeBuilder.DivZeroRefs`:
  the Long arm, the BigInteger arm, the ratio arm's common denominator) and throw `_rat`'s
  `ArithmeticException("Division by zero")`; a fused fixnum tree's `floorMod`/`lrem` throws into the
  bail, which recomputes through them. +148 B on a class keeping both (zlib 180,537 -> 180,685).
- **wasm-GC**: every zero divisor reaches one of four checks -- `_rat_new`'s zero denominator (both
  arms: `/`, `rational`, `expt`, complex division, a ratio's `floor`/`mod`), `_big_divrem`'s (its
  i64 fast path, where `rem_s`/`div_s` trapped natively, and its limb path: the rounding family's
  `_big_fdiv`, `_big_mod`) and `_fx_mod`/`_fx_rem`'s (fused trees) -- each of which calls
  `_div_zero` (`FUNC_DIV_ZERO`, `WasmRuntimeBuilder.buildDivZeroBody`). It throws the
  `division-by-zero` instance where `usedLayoutTags` baked the class (a handler landing pad and a
  `DIVISION_OPERATORS` name spelled), the `(nil . message)` payload elsewhere.
- **The gate is `divZeroLanding`**: EH mode AND an operator that can divide by zero spelled (or any
  name resolvable at run time). Without it the four helpers keep their old bytes and traps, so a
  non-EH module and an EH module that never divides are byte-identical. Measured 2026-10-04
  (P1 / component, default and `--optimize=size`): `size-report` hello/pi and the nine non-EH
  `bench-report` programs byte-identical; an EH module spelling a division it never reaches
  (`(handler-case (car 1) ...)`: the printer spells one) -2 B, the 16-byte address gap the cut
  message leaves; zlib (EH, no pad) +29 / +49 B, `--optimize=size` +26 / +46 B; `string.lisp`
  +58 / +51 B; a pad around `(/ 1 *z*)` (class baked) +88..+93 B on 23 KB; `unwind-protect` around a
  `floor` +49 / +51 B.
- **A float rounded by an exact zero** (decided 2026-10-04): a FINITE float dividend over an exact
  zero divisor in the two-argument rounding family (`(floor 7.5 0)`, `ceiling`/`truncate`/`round`,
  `ffloor` and twins) signals `division-by-zero`, like an integer or ratio dividend; it used to be
  `division-by-zero` on the JVM only and the non-finite-rounding `simple-error` (IEEE `7.5/0` =
  infinity, then no integer) on the interpreter and both wasm-GC backends. A NaN or infinite
  dividend keeps the non-finite-rounding `simple-error` even over a zero (SBCL: `simple-error` for
  `(floor infinity 0)` too, measured), so the dividend's check comes first on every backend.

### Per operator: where a zero signals and where IEEE answers (decided 2026-10-05)
Measured 2026-10-05 with SBCL 2.2.9 default (float traps on) and traps masked
(`sb-int:with-float-traps-masked`), ECL 21.2.1, ABCL 1.9.0, Clojure 1.12.6 and Gauche 0.9.15:

| Form | SBCL default | SBCL masked | ECL | ABCL | Clojure | Gauche | rontolisp now |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `(/ 1.5 0)`, `(/ 1.5 0.0)` | DBZ, DBZ | inf, inf | DBZ, DBZ | inf, inf | ##Inf, ##Inf | +inf.0 | inf, inf |
| `(expt 0 -1.5)`, `(expt 0 -1/2)` | DBZ | inf | DBZ | 0 (wrong) | `Math/pow` inf | +inf.0, error | inf |
| `(expt 0.0 -1)` | DBZ | inf | DBZ | 0.0 (wrong) | -- | +inf.0 | inf |
| `(mod 7.5 0)`, `(mod 7.5 0.0)` | DBZ | simple-error | DBZ | fp-overflow | ArithmeticException | error | DBZ |
| `(floor 7.5 0.0)` | DBZ | simple-error | DBZ | fp-overflow | ArithmeticException | error | DBZ |
| `(mod inf 2.0)`, `(mod inf 0)` | simple-error | simple-error | -- | fp-overflow | NumberFormatException | +nan.0 | simple-error |

The signal in SBCL's default column is its FLOAT TRAP, not an exact-zero rule: masked, SBCL
answers `(/ 1.5 0)` with infinity exactly as it answers `(/ 1.5 0.0)` (CLHS 12.1.4.1 contagion turns
the exact zero into `0.0` first). No implementation measured draws an exact-versus-float line for
`/`: each traps both (SBCL default, ECL) or neither (SBCL masked, ABCL, Clojure, Gauche). rontolisp's
float environment is the non-trapping one (`(/ 1.5 0.0)`, overflow to infinity), so:

- **`/` with a float operand: IEEE, kept.** Making only an exact zero signal would break contagion
  equivalence and put the Clojure and Scheme frontends (which lower to CL `/`) out of line with both
  oracles. `(/ 0.0 0)` is NaN. Typed double loops (`mandelbrot`'s `(/ (* 3.0d0 x) n)`) keep their
  bare `ddiv` / `f64.div`.
- **`expt` with a float result: IEEE, kept** -- `(expt 0 -1.5)`, `(expt 0 -1/2)` (a ratio power
  computes in float), `(expt 0.0 -1)`. `(expt 0 -1)` (exact result) signals as before.
- **The rounding family by a zero FLOAT: signals `division-by-zero`** over a finite dividend (any
  real: `(floor 7 0.0)`, `(floor 1/2 0.0)` too); it used to report the non-finite rounding. Only a
  failure changed class. Every implementation fails here.
- **`mod`/`rem` by any zero, and of a NaN or infinite operand: signal as `floor`/`truncate` do**
  (`division-by-zero` over a finite dividend; the non-finite-rounding `simple-error` for a NaN or
  infinite dividend or a NaN divisor). They answered NaN -- IEEE `fmod` -- on all four backends,
  which no implementation measured does (SBCL masked: `simple-error`; CLHS defines them as the
  remainder of `floor`/`truncate`, which have no quotient there). An INFINITE divisor keeps its
  value (`(mod -7.5 inf)` is inf: linalg-simd.md, "mod / rem"). `(mod 0.0 0)` is
  `division-by-zero` where SBCL reports `floating-point-invalid-operation`, a class rontolisp does
  not define (as `(floor 0.0 0)`). Clojure deviation (`doc/*/clojure/deviations.md`): `(mod ##Inf 2.0)`
  is an `ArithmeticException` here, `NumberFormatException` in the oracle.

Where: the interpreter's `Environment.integerQuotientZero` (`mod`/`rem`'s float arm, on the NaN
result) and `ExactRounding.quotient` (any zero over `isFiniteReal`); the JVM's `_frem` (on a NaN
result, behind one extra compare on the nonzero path; `_fmod` and the inline double emission both
reach it -- moving the throws out of line or testing `|r| > 0` instead cost more bytes and no
time) and `_fdiv` (a zero float divisor now takes the exact route, `_div` throwing, and the
ratio-dividend decline checks it); wasm-GC's `_rat_rem`/`_rat_mod` float arm
(`WasmFmodRuntimeBuilder.emitRemainder`'s undefined branch, `WasmRatioRuntimeBuilder
.emitUndefinedRemainder`: `_div_zero`, else the `(nil . message)` payload `(error "...")` throws)
and `_f64_fdiv` (any zero divisor; a ratio dividend over a zero float checked before its decline).
Outside EH mode the wasm remainder TRAPS (`unreachable`) where it answered NaN; `--no-gc` keeps NaN
(its own IEEE scalar tower, `(/ 1 0)` is infinity there). The non-finite message is interned LAST
and only where `mod`/`rem`/`evenp`/`oddp` is spelled (`REMAINDER_OPERATORS`), so a module whose
remainder helper is shaken keeps every other address.

Size, measured 2026-10-05 (before -> after): `size-report` (all 18 rows) and `bench-report` P1 /
`--optimize=size` / component byte-identical except `--optimize=off` hello/pi/dom -16 B and
`matmul --optimize=size` -8 B (non-EH `_rat_rem`/`_rat_mod`: a NaN constant became `unreachable`);
zlib `--optimize=off` +87 B, the others identical. An EH program taking a float `mod` +246 B P1 /
+252 B component on 10 KB (the message, the classification, `_div_zero` becoming reachable). JVM:
every class keeping `_fdiv` +32 B (fib 15,075 -> 15,107; jar +10..+16 B, matmul/sort +35/+37 B);
a program using float `mod` without other division (ConstantPool gains the two messages and
exception classes) +133 B jar. Performance: bench-report mandelbrot/matmul/sort/bignum/fib/sieve
unchanged within noise on JVM and P1; a loop of 200M float `mod`+`rem` on the JVM ~+7%
(1.60 -> 1.73 s; the one NaN compare per call), P1 unchanged.

### A zero base on the complex `expt` path (decided 2026-10-06)
`exp(w*log z)` multiplies `log 0 = -inf` into NaN parts, so until 2026-10-06 EVERY zero base on the
complex path -- the exact `0`, a float zero, a complex with both parts zero -- answered `#C(NaN NaN)`
on all four backends, `(expt #c(0.0 0.0) 0)` and `(expt #c(0.0 0.0) 2)` included. SBCL 2.2.9,
measured that day (default / traps masked):

| Form | SBCL default | SBCL masked | rontolisp now |
| --- | --- | --- | --- |
| `(expt 0 #c(1 1))`, `(expt 0 #c(1/2 1))` | 0 | 0 | 0 |
| `(expt 0 #c(1.0 1.0))`, `(expt 0.0 #c(1 1))`, `(expt #c(0.0 0.0) 2.5)`, `... 2`, `... 1/2` | `#C(0.0 0.0)` | same | same |
| `(expt -0.0 #c(1 1))` / `(expt #c(0.0 -0.0) 2.5)` | `#C(-0.0 -0.0)` / `#C(0.0 -0.0)` | same | `#C(0.0 0.0)` |
| `(expt #c(0.0 0.0) 0)`, `(expt 0 #c(0.0 0.0))` | `#C(1.0 0.0)` | same | same |
| `(expt #c(0.0 0.0) 0.0)` | arguments-out-of-domain | same | `#C(1.0 0.0)` |
| `(expt 0 #c(-1 1))`, `(expt 0 #c(0 1))`, `(expt 0.0 #c(0 1))` | DBZ | `#C(NaN NaN)` | `#C(NaN NaN)` |
| `(expt #c(0.0 0.0) -2)`, `... -2.5` | DBZ | `#C(inf NaN)` | `#C(NaN NaN)` |

SBCL's rule is `(if (and (zerop base) (plusp (realpart power))) (* base power) (exp (* power (log
base))))` behind `(zerop power) -> (1+ (* base power))`. rontolisp's (`Environment.zeroBasePow`,
`JvmComplexRuntimeBuilder.emitZeroBasePow`, `WasmComplexCompiler.emitZeroBasePow`, run only where
the formula would): a zero power answers `#C(1.0 0.0)`; a positive real part answers the exact `0`
when both operands are exact, else `#C(0.0 0.0)` -- positive zeros for every zero's sign, as IEEE
`pow(±0, y)` for a non-integer `y > 0`, rather than `*`'s signs (which already differ from SBCL's and
between the interpreter and the compiled backends on signed zeros); anything else keeps the
formula's NaN parts. **A non-positive real part does NOT signal, an exact zero base included**: no
exact value exists, so the result is a float and this section's `expt` rule applies -- the
`(expt 0 -1/2)` row, not the `(expt 0 -1)` one. A zero is decided on the VALUE, not its double: a
ratio below the least subnormal converts to `0.0` but is neither base nor power zero and keeps the
formula. The float zero power answering one is pow's `x^0.0 = 1.0`, where CLHS leaves a zero base
undefined.

Pinned by `ZeroBaseComplexPowerFixture` (`LispEvaluatorTest`/`JvmLispCompilerTest`/
`WasmLispCompilerIntegrationTest#zeroBaseToAComplexPower`, P1 and component) and `ci-spec.yaml`'s
`a-zero-base-to-a-complex-power`. Size, measured 2026-10-06: the four `size-report` programs and the
ten `bench-report` ones byte-identical as P1 `--optimize=off` / default / `--optimize=size`,
component, JVM class and jar. The wasm arm is inline at each complex `expt` site: +159 B per site (a site was
~380 B; a constant zero is `i32.const`+`f64.convert_i32_s`, 3 B where `f64.const` is 9). JVM: a class
keeping the complex group +144 B, jar +73 B (the arm, less a duplicated `atan2` call `_cpow` used to
make). A 2M-iteration nonzero-base complex `expt` loop unchanged within noise on the interpreter,
JVM and P1.

## A wrong-type argument names its operator
**Invariant: outside arithmetic too, a wrong-type argument reports `OP: The value <prin1> is not of
type T` with the type the operator requires, as a catchable `type-error` answering the datum and
`T`, byte-identical on all four backends (wasm-GC: in EH mode).** Covered since 2026-09-26:

| Form | Report |
| --- | --- |
| `(car 5)`, `(first 5)` / `(cdr "s")`, `(rest 5)` | `CAR: ... 5 ... LIST` / `CDR: ...` |
| `(nth nil l)`, `(nthcdr 1.5 l)` | `NTHCDR: ... INTEGER` |
| `(aref v nil)`, `(svref v nil)` | `AREF: ... INTEGER` |
| `(setf (aref v nil) x)`, `(setf (aref #d(1.0) 0) "x")` | `(SETF AREF): ... INTEGER` / `... REAL` |
| `(random nil)`, `(complex #c(1 2) 3)` | `RANDOM:` / `COMPLEX: ... REAL` |
| `(numerator nil)`, `(denominator 1.5)` | `NUMERATOR:` / `DENOMINATOR: ... RATIONAL` |
| `(lcm nil)`, `(gcd 1.5)` (one argument) | `LCM:` / `GCD: ... INTEGER` |
| `(random 1/2)`, `(random -1)`, `(random 0.0)` | `RANDOM: ... REAL` |
| `(nthcdr 1 5)`, `(nth 1 5)`, `(second 5)`, `(nthcdr 2 '(1 . 2))` | `NTHCDR: ... LIST` |
| `(endp 5)`, `(dolist (x 5))`, `(dolist (x '(1 2 . 3)))` | `ENDP: ... LIST` |
| `(char "ab" nil)`, `(schar "ab" 1.5)` | `CHAR:` / `SCHAR: ... INTEGER` |
| `(length 5)`, `(length 'foo)`, `(length (make-hash-table))` | `LENGTH: ... SEQUENCE` |
| `(last 5)`, `(mapcar #'1+ 5)` and `mapc`/`mapcan`/`maplist`/`mapl`/`mapcon` | `LAST:` / `MAPCAR: ... LIST` |
| `(rplaca 5 0)`, `(rplacd nil 0)`, `(setf (car 5) 0)` | `RPLACA:` / `RPLACD: ... CONS` |
| `(loop for x in 5 ...)`, `(loop for x in '(1 2 . 3) ...)` | `ENDP: ... LIST` |
| `(reverse 5)`, `(nreverse 5)`, `(reverse '(1 . 2))`, `(length '(1 . 2))` | `REVERSE:` / `NREVERSE:` / `LENGTH: ... SEQUENCE` |
| `(append 5 nil)`, `(append '(1 . 2) nil)`, `(list-length 5)` | `APPEND:` / `LIST-LENGTH: ... LIST` |
| `(member 1 5)`, `(member 9 '(1 . 2))`, `member-if`/`assoc`/`assoc-if`/`rassoc`/`rassoc-if` | `MEMBER: ... LIST` |
| `(mapcar #'1+ '(1 . 2))` and the other five, `(mapcan (lambda (x) 5) '(1))` | `MAPCAR:` / `MAPCAN: ... LIST` |
| `(char 5 0)`, `(schar 'foo 0)`, `(char (vector #\a) 0)` | `CHAR:` / `SCHAR: ... STRING` |
| `(setf (char s nil) c)`, `(setf (schar 5 0) c)` | `(SETF CHAR): ... INTEGER` / `(SETF SCHAR): ... STRING` |
| `(setf (char s 0) 5)`, `(setf (aref s 0) 5)` (`s` a string) | `(SETF CHAR):` / `(SETF AREF): ... CHARACTER` |
| `(row-major-aref v nil)`, `(setf (row-major-aref v nil) 0)` | `ROW-MAJOR-AREF:` / `(SETF ROW-MAJOR-AREF): ... INTEGER` |
| `(point-x 42)`, `(setf (point-x 42) 0)`, `(copy-point 42)` (a `defstruct`'s) | `POINT-X:` / `(SETF POINT-X):` / `COPY-POINT: ... POINT` -- generated code, not this table: [defstruct.md](defstruct.md) |
| `(copy-list 5)` | `COPY-LIST: ... LIST` |

- **`copy-list` of a non-list** (2026-09-26): used to signal a bare `type-error` whose
  `datum`/`expected-type` answered nothing on the interpreter (a raw
  `LispEvalException.ofClass`) and a `simple-error` on the compiled backends (a
  message-only `(error "The value ~s is not of type LIST" x)` inside
  `%copy-list-runtime`, kept instance-free by never naming a condition class). Now
  `COPY-LIST` is FUNNEL-TYPED like `last`/`append`/the rest of the list consumers: the
  interpreter's built-in goes through `Environment.requireListArgument`, and
  `%copy-list-runtime`'s non-list branch is `(%check-list x 'copy-list)` -- the same
  shared, instance-free funnel, so the fix costs nothing beyond one more table row.
- **FUNNEL-TYPED operators** (`OperandTypes.FUNNEL_TYPE`: `CAR`, `CDR`, `NTHCDR`, `ENDP`, `AREF`,
  `(SETF AREF)`, `CHAR`, `SCHAR`, `(SETF CHAR)`, `(SETF SCHAR)`, `ROW-MAJOR-AREF`,
  `(SETF ROW-MAJOR-AREF)`, `COPY-LIST`): each of their funnels checks ONE argument's type, so the funnel's kind IS the type
  (new kinds `LIST`, `RATIONAL`, `STRING`, `CHARACTER`) -- except that a to-double funnel (`NUMBER`) there is a packed float
  store, which takes any real: `REAL`. A numeric operator keeps its one fixed type. `%aset` reports
  as `(SETF AREF)`, `%row-major-aset` as `(SETF ROW-MAJOR-AREF)`, `nth` as `NTHCDR`, `svref` as `AREF`, `first`/`rest` as `CAR`/`CDR`
  (`OperandTypes.REWRITTEN`, the call-position-rewrite rule above). A one-argument `gcd`/`lcm`
  lowers to `(gcd x 0)`/`(lcm x 1)` (`LispMacroExpander.expandReduction`) -- it was `abs`, which
  accepted a float and named itself.
- **List walks** (2026-09-26): `nthcdr`'s walk signals on a non-list met before the count runs out
  (`(nthcdr 0 5)` is `5`, `(nthcdr 1 '(1 . 5))` is `5`); `nth` and `second`..`tenth` are
  `(car (nthcdr ...))`, so their report is `NTHCDR`'s or, on the last read, `CAR`'s. `endp` is
  checked (it was `(null x)`), and `dolist` checks ONCE, after its loop: the expansion is
  `(while (consp c) ...) (endp c) result` -- equivalent to CL's per-iteration `endp` (the body has
  seen 1 and 2 when `(1 2 . 3)` signals) with the loop unchanged. Interpreter: `endp` is a
  built-in function; `#'nth`/`#'second` name the failing step explicitly
  (`OperandTypeException.of(datum, kind, operator)`). JVM: `_nthcdr` throws `NTHCDR`'s report, and
  `endp` is `_endp` (nil or a cons answers itself, else `ENDP`'s report) plus the null test. wasm:
  `endp` is `WasmEmitHelper.emitListCheck` -- `ref.test $cons or ref.is_null`, else
  `_type_err_list` under the operator's id in EH mode (`emitListTypeError`) and a trap outside it
  (it traps there rather than answering nil); the `nthcdr` walk's step is the checked cons read
  itself, `br_on_cast_fail` with `emitListTypeError` as its miss, one type test per step
  ([cons-access-runtime.md](cons-access-runtime.md); 1M steps x100, wasmtime 49: 141 ms with a
  `ref.test` in front of the cast, 129 now, 131 unchecked), and keeps its trapping cast outside EH
  mode. The operator table adds `ENDP`
  for a program that spells `dolist`, `NTHCDR` and `CAR` for one that spells `nth`/`second`..
  (`WasmOperandTypes.LOWERED_TO`), and is placed in key order (it iterated `Map.of`s, whose
  order varies between JVMs). `char`/`schar` check the subscript as `aref` does (`_ckIdx`,
  `_idx_chk`).
- **List consumers** (2026-09-26; `length` answered 0, `last` NIL or its argument): `LENGTH`,
  `RPLACA`, `RPLACD` are FIXED-typed (`SEQUENCE`, `CONS`, two kinds no funnel produces -- the row
  names the type, so wasm reuses `_type_err_list`); `LAST` and the six `map*` are funnel-typed.
  A lowering checks through `(%check-list x 'op)` (`LispMacroExpander.checkListOf`): `last`'s
  binding, the `maplist`/`mapl`/`mapcon` guard (moved to the walk's end, next bullet), and
  `loop`'s `for-in` cursor under `ENDP` -- whose end test is `(if (consp c) nil (endp c))`, so `endp` runs only on the way out, as `dolist`'s
  does. Interpreter: `Environment.requireListArgument`. JVM: `_ckList` (nil or a cons) and
  `_ckCons` (returns the `Object[]`, replacing the site's `checkcast`) through the operator's
  wrapper; `%check-list` of `ENDP` calls `_endp`; `_length` throws `LENGTH`'s report for a symbol
  (a String without the quote) and any non-list. wasm: `emitListCheck` for the `map*` guards and
  `%check-list` (both modes; a trap outside EH), for `rplaca`/`rplacd` in EH mode only (outside
  it the cast still traps); `_seq_len` lands under `LENGTH`'s row, baked in like `_car`'s. The
  table gains `ENDP` for `LOOP`, `RPLACA`/`RPLACD` for `SETF INCF DECF PUSH POP PUSHNEW` (a
  `car` place's store). `#'rplaca`/`#'rplacd` became first-class on the compiled backends.
- **The rest of the list consumers** (2026-09-26; `reverse`/`member`/`assoc` answered NIL over a
  non-list, `append` a message-only error or the pad's generic text or a trap, `list-length` an
  instance-printing report, `length`/`mapcar` of a dotted list a count or a list): `REVERSE`,
  `NREVERSE` are fixed-typed `SEQUENCE`; `APPEND`, `LIST-LENGTH`, `MEMBER`, `MEMBER-IF`, `ASSOC`,
  `ASSOC-IF`, `RASSOC`, `RASSOC-IF` funnel-typed. **A walk is checked where it ENDS, not where it
  starts**: the atom it stops at must be nil, so one check covers a non-list argument (the walk
  ends at once) and a dotted tail (the datum is the tail: `LENGTH: The value 3 ...`), and no loop
  gains a test. The six scans end `((atom cur) (%check-list cur 'op))`
  (`LispMacroExpander.listScanEnd`; a literal proper list keeps its bare nil, so the internal
  `(member x '(...))` of `case`/`typecase` lowerings pays nothing); `reverse` is a consing `do`
  (it was `reduce` with a lambda) ending in the check; the user `nreverse` checks its cursor after
  the relink loop (`nreverseListForm(list, op)`; the internal reversals pass none); `list-length`'s
  prelude signals through `%check-list` over the atom it met. The `map*` walks check every cursor
  after the loop -- `maplist`/`mapl`/`mapcon`'s `do` result forms, the inline
  `Jvm/WasmMapcarCompiler`/`MapcCompiler`/`MapcanCompiler` after their exit (the JVM loop test is now `instanceof`, not
  `ifnull`), the interpreter's `mapFamilyValues` -- which replaced the up-front argument check; a
  `mapcan`/`mapcon` PIECE is checked as it arrives (the message-only `MAPCON: argument is not a
  list` is gone; a non-list LAST piece, which CL's `nconc` would answer, signals too). The
  multi-list value path (`BuiltinFunctionWrappers.mapFamilyWrapper`) checks each cursor as it is
  taken, so `(funcall #'mapcar f l 5)` reports `MAPCAR`, not `CAR`. Runtime helpers name
  themselves: JVM `_append` throws `APPEND`'s report (as `_length` does `LENGTH`'s) and `_length`
  tests the walk's end; wasm `_append` (EH mode only; a non-EH body is byte-identical) and
  `_seq_len` land under their rows. Interpreter: `Environment.requireListEnd`. Not covered:
  `member-if-not` & co. report under the `-if` operator their prelude defun calls; `nconc` is
  unchanged (the sequence functions: "A sequence, array or hash-table operand of the wrong kind").
- **String accesses** (2026-09-26; a non-string was a message-only error, the pad's generic
  text or a trap, a symbol read as its NAME on the JVM): `char`/`schar` check the subscript,
  then the string -- that order on every backend, because the compiled sites check the
  subscript ahead of the read. A `setf` place names its STORE: `%schar-set`'s optional fourth
  operand is the quoted place head (`LispMacroExpander.scharSetOf`), reported as
  `(SETF CHAR)`/`(SETF SCHAR)`, as `(SETF AREF)` for an `aref`/`svref`/`elt` place's
  string arm (the array arm's name) and as `(SETF ROW-MAJOR-AREF)` for a `row-major-aref`
  place's. Interpreter: `charRef`, `scharSet(args, rebind, operator)`. Compiled:
  `expandScharSetFunctional` wraps the runtime defun's arguments in `(%check-string var 'op)`
  (a `char`/`schar` place only; the others run under `stringp`) and `(%check-index i 'op)`
  (`op` nil = unnamed) -- compile-path-only forms, since `%schar-set-runtime` cannot know the
  store's name. JVM: `_charRef` throws the unnamed `STRING` report for anything but a
  quote-framed String or a character vector, named by the operator's wrapper
  (`wrapForOperator`); `%check-string` is `_pStringp` plus a site throw
  (`_opTypeErr(_teRaw(x, "STRING"), op, FUNNEL_TYPE)`), `%check-index` is `_ckIdx` under `op`.
  wasm, EH mode only (a non-EH module is byte-identical): `_str_char_ref` lands a non-string
  through `_type_err(x, STRING)` directly (`WasmOperandTypes.emitLanding`; `STRING` has no
  `_type_err_*` stub) under the register its site sets (`emitCall`), `%check-string` is
  `emitStringpI32` plus `emitTypeError`, `%check-index` is `emitIndexCheck`. The landing
  selects `STRING` only when the table has a string-checking row (`STRING_CHECKED` adds its
  code to `rowCodes`); `LOWERED_TO` adds `(SETF CHAR)`/`(SETF SCHAR)` for `CHAR`/`SCHAR` and
  `(SETF AREF)` for `ELT`.
- **String stores and `row-major-aref`** (2026-09-26; a non-character store was
  `%SCHAR-SET`'s message-only error, a silent JVM store that later escaped as a
  `ClassCastException`, a wasm cast trap; a `row-major-aref` subscript unnamed, a
  `NullPointerException` or a trap): a `%schar-set` checks string, subscript, VALUE, then
  bounds -- the value is `CHARACTER`, a new kind. Compiled: the runtime defun's third
  argument is `(%check-character c 'op)`, every place (`op` nil = unnamed). JVM:
  `instanceof int[]` plus the site throw `%check-string` uses (`emitSiteTypeError`). wasm, EH
  mode only: `ref.test $char` plus `emitTypeError`; the landing selects `CHARACTER` only when
  the module carries `%schar-set-runtime` (`WasmOperandTypes.CHARACTER_CHECKED`, the one
  function every string store calls). The interpreter's `%aset`/`%row-major-aset` string arm
  (`storeStringChar`) throws the unnamed `CHARACTER` report the seam names. `row-major-aref`
  and `%row-major-aset` check their subscript as `aref`/`%aset` do (`compileSubscript`: JVM
  `_ckIdx`, wasm `_idx_chk` on every arm), and the JVM store goes through the wrapper, so a
  packed float store's non-real reports `(SETF ROW-MAJOR-AREF): ... REAL` as the
  interpreter's seam now names it. `LOWERED_TO` adds `(SETF ROW-MAJOR-AREF)` for
  `ROW-MAJOR-AREF`.
- **Interpreter**: the built-ins throw `OperandTypeException` with the kind (`car`/`cdr`/`first`/
  `rest` and `nthValue` `LIST`, `numerator`/`denominator` `RATIONAL`, `random` `NUMBER`/`REAL`), the
  seam names them. `#'first`/`#'rest` of nil and `#'second` past the end answer nil now, as the
  calls do (they signalled "expects a cons cell").
- **JVM**: `car`/`cdr` compile to `_car`/`_cdr` (`JvmOperandTypeRuntime`), whole readers that name
  themselves (`_opTypeErr(_teRaw(x, "LIST"), "CAR", FUNNEL_TYPE)`) -- one call per site where the
  inline null test and cast were; `zlib` -4.5 KB, a one-defun class +227 B (the two methods). The
  subscripts of `aref`/`%aset`/`nthcdr` go through `_ckIdx` (a `Long` or `BigInteger`), `numerator`/
  `denominator` through `_ckRat`, both via the operator's wrapper; `%aset`'s store helpers and the
  `complex` constructor are invoked under the wrapper too. `_opTypeErr`'s funnel-typed arm reads the
  kind back off the raw report.
- **What is a cons on the JVM** (`JvmOperandTypeRuntime.ConsShape`): `_car`/`_cdr`/`_endp`/
  `_ckList`/`_ckCons`, `_nthcdr`'s walk and the inline `mapcar`/`mapc`/`mapcan` walks (`_isCons`)
  exclude the other `Object[]`s exactly as `consp` does: a ratio, a function reference (`Integer`
  head), an instance (`String[]` head; tested only when `mayUseInstances`) and an async value
  (`Object[3]` + marker; only with the async runtime). Until 2026-09-26 they tested `instanceof
  Object[]` alone: `(car an-instance)` answered its layout, `rplacd` overwrote its first slot, and
  `(car c)` in a `handler-bind` handler returned. Cost, measured 2026-09-26 (JDK 25, 10 interleaved
  runs pinned to 4 cores on a loaded host, best/median ms): 1M-element `car`+`cdr` walk x40
  156/189 -> 161/190; `endp`+`cdr` walk 182/242 -> 150/187; `dolist` 159/191 -> 161/188; `mapcar`
  over 10k x2000 131/150 -> 132/150; bench-report `list` 463/513 -> 463/484. A `mapcar` class
  +165 B. Pins: `JvmLispCompilerTest.consAccessorsRejectEveryObjectArrayThatIsNoCons`, ci-spec
  `cons-accessors-reject-object-arrays-that-are-no-cons` and `failing-handler-bind-handler-report`.
- **wasm-GC, EH mode only** (`WasmEmitHelper.checksConsFields`; outside it every cast still traps and
  a non-EH module is byte-identical): an inline `car`/`cdr` site is ONE type test over the operand
  on the stack, `block block br_on_cast_fail 0 eqref (ref $cons); struct.get; br 1 end; call _car
  end` ([cons-access-runtime.md](cons-access-runtime.md)), and `_car`/`_cdr` are CHECKED there -- nil answers nil, a non-list sets the register to `CAR`'s/`CDR`'s
  row and lands in `_type_err_list`; a `--optimize=size` site was already that call and pays nothing.
  The body names itself whichever form reached it (an unnamed report when the program spells neither
  name). A subscript goes through `i32.const id; ref.i31; call _idx_chk` (+6 B): a fixnum answers
  itself, anything else `_int_val` under the id. `compileAset` names itself (a statement-position
  store and a pinned-kind `setf` reach it without `compileCons`). `random`'s integer arm first runs
  the limit through `_as_f64` (a ratio passes and meets `_int_val`'s unnamed, true, `INTEGER`);
  `denominator`'s `_rat_den` lands a non-rational in `_type_err_int` itself (since 2026-10-03; it
  answered 1 for anything before, and the site checked through `_int_val`).
- **Cost, measured 2026-09-26** (wasmtime 47): P1 `zlib` 114,383 -> 115,984 (+1.4%), size level
  87,936 -> 88,735 (+0.9%); a tight 1M-element `car`/`cdr` loop in an EH module 129 -> 166 ms
  (+28%), while the site tested the type twice (`ref.test`, `ref.cast`). With the one
  `br_on_cast_fail` (same day, wasmtime 49) that loop is 113 -> 92 ms against 84 unchecked, and
  most list walks are at or under the unchecked time (the table:
  [cons-access-runtime.md](cons-access-runtime.md)). The `aref` loop and the JVM are unchanged.
- **Cost of the list walks, measured 2026-09-26**: `zlib` P1 115,984 -> 116,300 (+0.27%), size level
  88,735 -> 89,051, JVM classes 163,399 -> 163,693; `hello_world`, `pi_approx`, `dom_reactor`
  unchanged. No loop gains a test.
- **Cost of the list consumers, measured 2026-09-26**: `zlib` P1 116,527 -> 116,892 (+0.31%), size
  level 89,272 -> 89,623, JVM class 164,202 -> 164,583; `hello_world`, `pi_approx`, `dom_reactor`
  unchanged. A 1M-element `loop`/`dolist`+`rplacd`/`length` mix in an EH module 0.34 -> 0.36 s
  (the `rplacd` site's `ref.test` in front of its cast: not the one-test `br_on_cast_fail` of a
  `car`/`cdr` read, whose block would need a cast-typed signature); the JVM unchanged.
- **Cost of the string accesses, measured 2026-09-26** (wasmtime 49): `zlib` code +201 B and
  strings +52 B; `hello_world`, `pi_approx`, `dom_reactor` unchanged, a non-EH module
  byte-identical, JVM class 164,583 -> 164,736. Its P1 total read 117,008 -> 118,253 only because
  the 52-byte shift put the fdlibm table's then-probed base word on chipz's literal `2048`, pinning
  ~990 dead bytes; with the table decided by its readers it is 117,260
  (`.kb/optimize-dead-code-elimination.md`). A 21M-read `char` loop: JVM unchanged (noise), EH wasm 560 ->
  580 ms (the register write around `_str_char_ref` and its quote-frame test).
- **Cost of the rest, measured 2026-09-26** (on the tree before the string accesses): `zlib` P1 117,008 -> 116,675, size level 89,623 ->
  89,254, JVM class 164,583 -> 161,636 (`reverse`'s `do` replaces a `reduce` over a lambda);
  `hello_world`, `pi_approx` unchanged. The price is paid by a TINY EH module: one whose only
  checked site is a `length` or an `append` (`(handler-case (length *x*) ...)`, 1,573 -> 6,127 B)
  now keeps the landing and its tables, because the dotted-tail test at the walk's end is one the
  whole-module type facts cannot prove away; `examples/console/error-handling.lisp` 35,901 -> 35,641.
- **Cost of the string stores and `row-major-aref`, measured 2026-09-26** (wasmtime 49, on the
  tree after `.todo/985` and `990`): `zlib` P1 116,920 -> 117,028 (code +67 B, the landing's
  `CHARACTER` arm +16; data +41 B, the `CHARACTER` suffix and the new row); size level 89,499 ->
  89,051 (code -488 B: `WasmRefTypeFolder` proves `%schar-set-runtime`'s value a character, so its
  packed-integer arm folds to a trap and one body folds away); JVM class 172,368 -> 173,351
  (+983 B: the `_ckIdx`/store wrappers and chipz's `row-major-aref`/`fill`/`replace` sites).
  `hello_world`, `pi_approx`, `dom_reactor` byte-identical; a non-EH module too. A 20M-iteration
  `row-major-aref` read+store loop: EH wasm 0.91 -> 1.08 s, what the same `aref` loop already
  paid (0.99 s); JVM unchanged (1.67 -> 1.65 s at 300M).
- Pinned by `ci-spec.yaml`'s `argument-type-errors-name-the-operator-beyond-arithmetic` and
  `list-walks-and-string-indices-name-the-operator`, `list-consumers-name-the-operator`,
  `list-consumers-beyond-the-first-set-name-the-operator`, `string-accesses-name-the-operator` and
  `string-stores-and-row-major-subscripts-name-the-operator`, and the
  `argumentTypeErrorsNameTheOperatorBeyondArithmetic` /
  `listWalksAndStringIndicesNameTheOperator` / `listConsumersNameTheOperator` /
  `listConsumersBeyondTheFirstSetNameTheOperator` / `stringAccessesNameTheOperator` /
  `stringStoresAndRowMajorSubscriptsNameTheOperator` triples
  (`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`).

### `random`'s domain (closed 2026-09-26, `.todo/981`)
CLHS's domain is a COMPOUND type, `(OR (INTEGER 1) (FLOAT (0.0)))`: a ratio limit is real but
neither integer nor float, and an integer or float limit `<= 0` is out of range either way. No
single existing `OperandTypes.Kind` names that domain truthfully, and `WasmOperandTypes.Texts`
interns one SYMBOL per `Kind`, not a compound cons structure, so building the CLHS-precise type
would mean teaching the shared operand-type table (used by many operators) a new representation
for this one operator alone. Resolution: report it under RANDOM's own ALREADY-registered type,
`REAL` -- the same text `(random nil)` already got before this fix -- rather than adding
compound-type machinery only `random` would ever use. A known simplification (like "no
random-state objects exist" above), not a claim that a ratio or a negative number is not real.

- **Interpreter** (`Environment.java`'s `RANDOM` builtin): the three hand-written
  `LispEvalException` throws (ratio, non-positive int/float/bignum) became
  `OperandTypeException.of(limit, Kind.REAL).named(RANDOM)`, the exact call the pre-existing
  non-real case already made -- catchable now, where they were plain uncatchable-by-class
  `simple-error`s before.
- **JVM, the general path** (`JvmNumericRuntimeBuilder.buildRandom`, `_random`): a ratio limit
  (`BigInteger[]`) and a `<= 0` limit (checked via `_dbl`, so one comparison catches Long, Double
  and BigInteger alike) both throw the unnamed `_teRaw(limit, REAL)` `_dbl` already throws for a
  non-real limit; the per-(helper, operator) wrapper around every `_random` call site (already
  established for the non-real case) renames it the same way.
- **JVM, the two paths that bypass `_random` for performance** -- `.kb/random.md`'s "Four JVM
  sites must agree on the FORMULA" already tracked this exposure:
  - `JvmRandomCompiler`'s double-literal fast path (`unboxDouble` + inline multiply, no dispatch)
    now checks the limit's sign first: positive re-runs `unboxDouble` (a pure coercion, cheap to
    call twice) and inlines as before; non-positive calls the wrapped `_random` on the STILL-BOXED
    value instead of drawing, which throws before any draw happens (no double-draw).
  - `JvmIntFusionCompiler`: a foldable constant limit `<= 0` no longer builds a `RandomLeaf` at
    all (`randomLeaf` returns null, the same bail a ratio/bignum limit already took), forcing the
    call through the checked unfused path. A runtime Long limit `<= 0` now joins the "not a Long"
    trampoline in `emitRandomDraw` (which already calls the wrapped `_random`) instead of drawing.
    That trampoline's `ctx.numOp(RANDOM)` call needed an explicit `ctx.operator = RANDOM` around
    it: the fusion planner reaches a `(random ...)` argument STRUCTURALLY, never through
    `JvmExprCompiler.compileCons`'s per-form dispatch that normally sets `ctx.operator`, so the
    wrapper was resolving unnamed until this was added.
- **wasm-GC, EH mode only** (gated behind `WasmEmitHelper.checksConsFields`, like the non-real
  check above -- a non-EH module is unchanged, byte for byte): the float branch (both the
  literal-argument fast path and the runtime `ref.test TYPE_FLOAT` one) checks the limit's `f64`
  value against `0.0` before scaling; the integer branch's `_int_val` call -- which already
  rejected a ratio, since a ratio is neither an i31 nor a boxed integer, but UNNAMED (a raw `call`,
  not `WasmOperandTypes.emitCall`) -- now goes through `emitCall` like `_as_f64` beside it, so it
  reports RANDOM's own `REAL` under the operator register instead of an unnamed `INTEGER`; the
  extracted `i64` limit is then checked `<= 0` before the unsigned remainder (which previously
  read a negative limit's two's-complement bit pattern as a huge unsigned value). `_int_val` is
  pure, so calling it a second time for the actual remainder draws nothing extra.
- Pinned by `ci-spec.yaml`'s `random-limit-domain-violations-signal-a-type-error` and the
  `randomLimitDomainViolationsSignalATypeError` / `ehRandomLimitDomainViolationsSignalATypeError`
  triple (`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`).

## One argument is still checked
**Invariant: a one-argument call compares or folds nothing, but its argument is checked like any
other: `(+ x)`, `(* x)`, `(logand x)`/`logior`/`logxor`/`logeqv`, `(< x)` and the other orderings,
`(= x)`, `(/= x)`, `(min x)`/`(max x)` and every character comparison signal the operator's
catchable `type-error` for a wrong-type argument, byte-identical on all four backends (wasm-GC: EH
mode).** Before (measured 2026-09-26): the arithmetic and bitwise ones answered `x` on the compiled
backends, the orderings/`=`/`min`/`max` answered on all four, and `(char= 1)` answered `T` on the
interpreter, the generic `ClassCastException` text on the JVM and trapped on wasm -- at EVERY arity.

- **Types**: `+ * =` `NUMBER`; the orderings, `min`, `max` `REAL`; the bitwise family `INTEGER`
  (`logeqv` reports as `LOGXOR`, `/=` as `=`, the call-position-rewrite rule). The twelve character
  comparisons (`char=` .. `char-not-lessp`) are FIXED-typed `CHARACTER` rows, last in
  `OperandTypes`' order.
- **Interpreter**: `compareChain`'s and `min`/`max`'s one-argument arms (`requireNumericOperand`);
  `charOperandCodes` checks EVERY argument before any pair is compared (it stopped at the first
  failing pair); `%check-character` is a built-in, for the prelude.
- **Compiled numerics**: `LispMacroExpander.checkedOneArgument` -- `(let ((__one x)) (if (realp
  __one) t (%operand-type-error __one '< 'real)))`, nothing for a literal of the type -- from
  `expandComparison` and `expandReduction`'s one-argument arms, `expandLogEqv`,
  `expandNumericNotEqual` (`(/= x)` is `(= x)`) and `ArithmeticIdentities.oneArgument` (`+`, `*`).
  `#'min`/`#'max`'s lone arm calls `(min n)`. JVM: `%operand-type-error` under a `NUMBER`-typed
  operator hands `_opTypeErr` the operator's type -- the funnel-typed rename reads a `NUMBER` kind as
  `REAL`. `--no-gc` keeps `(< x)` as `(progn x t)`.
- **Compiled characters**: every argument is evaluated and checked before any pair is compared (the
  JVM chain branched out at the first failing pair and never evaluated the rest). `char>`,
  `char>=`, `char/=` and `char-equal` compile as chains of their own (the `let*` reversal and the
  `char-downcase` expansion are gone), so each reports its own name. JVM: `_ckChr` under the
  comparison's wrapper before the unboxing; a literal is its `int` constant. wasm-GC, EH mode:
  `i32.const id; call _chr_code` (`FUNC_CHR_CODE`, `br_on_cast_fail` fast path, the `CHARACTER`
  landing) per non-literal operand -- the size of the `ref.cast; struct.get` it replaces; outside EH
  the cast still traps, byte-identical. The case-insensitive prelude defuns check with
  `(%check-character c 'char-lessp)`.
- Measured 2026-09-27: hello-clack Worker (`--no-wasi --optimize=size`) 676,784 -> 676,722;
  `zlib` P1 127,239 -> 127,234, size 97,055 -> 97,049, component 100,972 -> 101,035; JVM `zlib`
  class 188,039 -> 187,699; `hello_world`, `pi_approx` and a non-EH module comparing characters
  byte-identical. 40M comparisons with one non-literal operand in EH mode: wasmtime 1.49 -> 1.55 s,
  JVM 0.31 -> 0.30 s. An inline `ref.test` check instead cost +4,428 B on the Worker (228 sites).
- The other character built-ins check the same way: "A character built-in checks its argument".
- Pinned by `ci-spec.yaml`'s `one-argument-calls-check-their-argument` and the
  `oneArgumentCallsCheckTheirArgument` triple (`LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest`).

## A character built-in checks its argument
**Invariant: a non-character reaching `char-code`, `char-int`, `char-upcase`, `char-downcase`,
`alpha-char-p`, `digit-char-p`, `upper-case-p`, `lower-case-p`, `both-case-p`, `alphanumericp`,
`char-name`, `graphic-char-p` or `standard-char-p` -- directly or through `#'` -- reports
`OP: The value 1 is not of type CHARACTER` as a catchable `type-error`, byte-identical on all four
backends (wasm-GC: EH mode); a `digit-char-p` radix that is no integer in 2..36 is the type-error of
`(INTEGER 2 36)`, named `DIGIT-CHAR-P` (below).**
Before (measured 2026-09-27): a simple-error `CHAR-CODE expects a character, got: 1` interpreted
(named after the helper a prelude defun or lowering called: `upper-case-p` said `CHAR-DOWNCASE`),
the datum-less `ClassCastException` report on the JVM (`CHAR=` for the case predicates) and a trap
on wasm.

- **Table**: twelve fixed-typed `CHARACTER` rows after the array-shape accessors, then
  `DIGIT-CHAR-P` funnel-typed -- a `CHARACTER` row would name its radix's `INTEGER` failure
  `CHARACTER` through the interpreter's seam. `OperandTypes.characterOperators()` is what gives a
  wasm table naming `DIGIT-CHAR-P` its `CHARACTER` text.
- **Interpreter**: `Environment.requireChar` throws `OperandTypeException` under its caller's name
  (also `make-array`'s, `fill`'s and `vector-push`'s character checks). `digit-char-p` checks the
  character before the radix, as the compiled order does. `lower-case-p`/`upper-case-p` run the
  built-in; the shared `(not (char= c (char-upcase c)))` lowering is gone.
- **The radix** (`digit-char-p`, and `parse-integer`'s `:radix`): CLHS and SBCL take `(integer 2 36)`
  and refuse anything else -- a non-integer, a bignum, 0, 1, 37 -- with datum the radix and expected
  type `(INTEGER 2 36)`, after the character's own check. Before (measured 2026-10-06, SBCL 2.2.9 and
  the four backends): 37 answered `NIL` interpreted and on the JVM and weight 2 on both wasm legs
  (the digit walk reads letters past `z`), 1 answered `NIL` everywhere, `parse-integer "12" :radix 37`
  was a `simple-error` (interpreter, JVM) or 39 (wasm), a bignum radix a `NIL` datum on the JVM and a
  cast trap on wasm. Now: interpreter `Environment.requireRadix` (`OperandTypes.RADIX_TYPE`, named
  through `.named`, so the first-class `#'parse-integer` says `DIGIT-CHAR-P` as the compiled
  expansion does); JVM `_ckRadix` (`JvmOperandTypeRuntime.CK_RADIX`, an `int`-returning helper that
  throws `_teOf(x, (INTEGER 2 36))`, wrapped per operator like `_ckIdx`); wasm
  `WasmCharCompiler.emitRadixCheck` (EH mode: `_type_err_of` under the operator register; outside it
  `unreachable`). A literal radix inside 2..36 emits no check on any backend. `parse-integer`'s
  expansion checks a spelled `:radix` that is not such a literal ONCE, before the bounds and the scan,
  by the probe `(digit-char-p #\0 radix)` -- SBCL refuses it even over an empty string or with
  `:junk-allowed` -- so the class (and the report's operator) is `digit-char-p`'s; the scan's own
  per-character `digit-char-p` repeats the integer test, which the call-per-character shape cannot
  skip without a second unchecked primitive per backend. Pinned by `RadixRangeFixture` in the three
  backend suites and ci-spec `digit-char-p-and-parse-integer-refuse-a-radix-outside-2-to-36`; the old
  `DIGIT-CHAR-P ... INTEGER` row of `characterBuiltInsCheckTheirArgument` now says `(INTEGER 2 36)`.
  `digit-char` has the same hole (a radix of 37 answers a character, 1 answers `NIL`).
- **Compiled**: `char-code`, the folds, `alpha-char-p`, `digit-char-p` and the two case predicates
  push the code point through the comparisons' `pushCheckedCode` (JVM `_ckChr` under the
  operator's wrapper; wasm `_chr_code` in EH mode, the cast outside it). The case predicates
  compile natively: code point vs. its fold. A non-literal radix goes through `_ckRadix` /
  `emitRadixCheck`. The prelude defuns (`alphanumericp`, `both-case-p`, `char-name`,
  `graphic-char-p`, `standard-char-p`) check first with `%check-character`.
- Measured 2026-09-28: hello-clack Worker (`--no-wasi --optimize=size`) 680,279 -> 680,498 (code
  +139: string addresses and the operator ids after the three new rows crossing a LEB boundary,
  less 1-2 bytes per character site; data +80, the rows); `zlib` P1 129,718 -> 130,172 (code +4; data +450, of which
  the rows are ~80 and the rest strings the tree-shaker now keeps because an unrelated `i32.const`
  lands in their shifted range), size 99,533 -> 99,987, component 133,727 -> 133,809; JVM `zlib`
  class 188,098 unchanged, `examples/net/hello-clack.lisp` class 948,278 -> 949,038 (the `_ckChr`
  wrappers); `hello_world`, `pi_approx` byte-identical. 40M `char-code`/`upper-case-p`/
  `char-downcase` in EH mode: wasmtime 4.23 -> 4.20 s, JVM 0.33 -> 0.34 s.
- `char-int` (measured 2026-09-28: undefined on every backend) is now the same code point
  `char-code` answers on all four, joining this table's twelve rows -- CL leaves it no
  implementation-defined attribute beyond the code point to differ on.
- Pinned by `ci-spec.yaml`'s `character-built-ins-check-their-argument` and the
  `characterBuiltInsCheckTheirArgument` triple (`LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest`).

## A sequence, array or hash-table operand of the wrong kind names its operator
**Invariant: a sequence operator handed a value that is no sequence, an array accessor one that is
no array and a hash-table accessor one that is no hash table report `OP: The value <prin1> is not of
type SEQUENCE|ARRAY|HASH-TABLE` as a catchable `type-error` answering the datum and that type,
byte-identical on all four backends (wasm-GC: in EH mode).** Closed 2026-09-27 (`.todo/a48`). Until
then `every`/`some` answered `T`/`NIL` interpreted; `find`, `position`, `remove`, `substitute`,
`remove-duplicates`, `sort`, `stable-sort`, `subseq` (wasm) and the `-if`/`delete`/`n-` variants
answered nil (or the value) everywhere; `(coerce 5 'vector)` answered 5; `fill`/`replace`/
`concatenate`/`subseq` were interpreted simple-errors against `LENGTH:` or a datum-less
`ClassCastException` compiled; `aref`/`gethash` and their kin a simple-error interpreted, a
datum-less cast failure on the JVM and a trap on wasm.

| Operators | Reported as |
| --- | --- |
| `every` `some` (`notany` `notevery` under them, `OperandTypes.REWRITTEN`), `sort` `stable-sort` (`sort :key` is `stable-sort`), `find` `position` `count` `remove` `delete` `substitute` `nsubstitute` and their `-if`/`-if-not`, `remove-duplicates` `delete-duplicates`, `reduce`, `map` `map-into`, `mismatch` `search`, `fill` `replace` `concatenate`, `subseq` (`copy-seq` under it), `coerce` | their own name, `SEQUENCE`; a rank-2 array is no sequence |
| `aref` `svref` `elt` `#'aref`, `(setf aref)`, `row-major-aref` `(setf row-major-aref)`, `array-dimensions` (`array-rank` `array-dimension` `array-total-size` under it) | own name, `ARRAY` |
| `fill-pointer` `(setf fill-pointer)` (`%set-fill-pointer`) `vector-push` `vector-push-extend` `vector-pop` `array-element-type` `adjustable-array-p` `array-has-fill-pointer-p` `array-displacement` (`%array-disp-target`) `adjust-array`, and `#'` of each | own name, `ARRAY` (since 2026-09-27, `.todo/a57`) |
| `gethash` `(setf gethash)` (`%puthash`) `remhash` `clrhash` `maphash` `hash-table-count` `hash-table-size` `hash-table-test` `hash-table-rehash-size` `hash-table-rehash-threshold` | own name, `HASH-TABLE` |

- **Table** (`OperandTypes`): the three families are FUNNEL-typed (`SEQUENCE_OPERATORS`,
  `ARRAY_OPERATORS`, `HASH_TABLE_OPERATORS`, the last two appended after the sequence operators so a
  module spelling none numbers every older operator as before): a `:start` that is no integer stays
  `INTEGER`, a dotted tail `LIST`. `Kind` gains `ARRAY` and `HASH_TABLE` (`typeName()` spells
  `HASH-TABLE`; use it, not `name()`, wherever a kind becomes text).
- **Two internal forms**, both built by the shared expander and placed where the lowering's own type
  dispatch runs out of arms: `(%operand-type-error x 'op 'kind)` only signals (never answers: JVM
  `athrow`, wasm the landing plus `unreachable`), and `(%check-sequence x 'op)` answers `x` when it is
  a list or a vector. The expander cannot resolve a name (`macro` may not import `compiler`), so a
  backend does: `OperandTypes.reportedOperator`, nil = unnamed; on wasm a name missing from the
  operator table reports unnamed, which is why the sites name what the program SPELLS and
  `WasmOperandTypes.LOWERED_TO` covers the rewrites (`sort` -> `stable-sort`, `gethash` ->
  `(setf gethash)`, the shape readers -> `array-dimensions`).
- **`%check-sequence` is a call, not a test at the site**: its test is a whole `vectorp`, 225
  instructions of wasm, and zlib held 13 of them (+4.9 KB). A compiled site calls the injected
  `%check-sequence-runtime (x token)` (`LispMacroExpander.checkSequenceRuntimeWrapper`, gated by
  `programUsesSequenceCheck`, spelled inline by `checkSequenceInline` when absent) with the
  operator as the backend's own token: the reported name as an UNSPELLED string on the JVM
  (`JvmCharCompiler.compileCheckSequence`; a quoted symbol there kept the named function's `#'`
  wrapper alive -- +16 KB on a sequence-heavy class), the table row as an i31 on wasm; the helper's
  `%operand-type-error x op` reads it at run time. A literal sequence argument is not wrapped
  (`isLiteralSequence`): wrapping `#(...)` hid it from the folds that read it (+52 B a site).
- **Where the checks sit**: `seqResultDispatchForm`'s non-string non-vector arm (listp, else the
  signal), `buildPositionScan`'s length arm, `deleteOrSubstituteDispatch`, `seqAsListForm` (a list
  passes inline, anything else `(coerce (%check-sequence ...) 'list)`), the conversion trio's
  non-list arms (`COERCE`), `%subseq-runtime`'s list arm plus an inline JVM check on the array-free
  `subseq` lane, `fill`/`replace`/`map`/`map-into`/`reduce :start` before their first `length`,
  `%seq-string` (`CONCATENATE`), `ConcatenateForms`' list family and packed `coerce`, the `#'map`
  `#'every` `#'concatenate` `#'map-into` wrappers, the `mismatch`/`search`/`count-if-not` prelude
  defuns. `#'aref`'s wrapper tests `arrayp` before its fold reads the dimensions (it named
  `ARRAY-DIMENSIONS`).
- **Interpreter**: `Environment.seqAsList` throws the unnamed `SEQUENCE` report for anything but a
  list or a vector, and the built-in seam names it; `requireArray`/`requireHashTable`/`maphash` go
  through `Environment.accessorTypeError` (named when the accessor is a named operator); `subseq`,
  `sequenceLength` (`fill`/`replace`) throw their own. `#'copy-seq` is `subseq`'s body now (it
  refused a general vector), and `fill`/`replace` take a packed float array (they refused one).
- **JVM**: `_arrayCheckRank` (every `aref`/`%aset`, now through the operator's wrapper),
  `_aref1`/`_aset1` (`row-major-aref` and the rank-1 reads) and `_arrayDims` test `ArrayList` before
  their cast and throw `_teRaw(x, "ARRAY")` (`JvmArrayRuntimeBuilder.emitArrayCheck`); every
  hash-table site runs the table through `_ckTab` under the accessor's wrapper
  (`JvmHashTableCompiler.emitTableCheck`, in front of the `java:` guard), `hash-table-test` and the
  rehash accessors included. The `java:` guards `_jckarr`/`_jcktab` throw the same type-error
  ([java-interop.md](java-interop.md)).
- **wasm-GC, EH mode only** (a non-EH module keeps the trap and, for the accessors, its bytes):
  `_arr_check_rank(arr, given)` (every `aref`/`%aset`) carries the site's operator id above the rank
  byte (`given | id << 8`, `WasmArrayRuntimeBuilder.buildArrCheckRankBody(int)`) and lands a value
  that is no string, packed array or cell-with-dims `ARRAY` after setting the register from it -- a
  site pays no register write, only a wider constant (+1 B). `ANY_RANK` (0xFF) makes the same call
  the array check of `row-major-aref`, `%row-major-aset`, `array-dimensions` and the fused integer
  tree's `aref` fallback (`WasmArrayCompiler.emitArrayCheck`/`emitRank1Check`; the fast packed arm
  is untouched). A hash-table site tests `hash-table-p` inline before its cell cast
  (`WasmHashTableCompiler.emitTableCheck`: `headerSlot`, `clrhash`, the count, the constant
  answerers). The landing selects `SEQUENCE`/`ARRAY`/`HASH-TABLE` only when the table names an
  operator of that family.
- **Cost, measured 2026-09-27** (wasmtime 49, JDK 25): zlib P1 126,472 -> 127,760 (+1.0%), size
  level 95,900 -> 96,965, component 130,546 -> 131,742, JVM class 187,046 -> 187,948 -- the shared
  check helper 451 B, the EH `_arr_check_rank` +71 B, +1 B per `aref` site; `hello_world`,
  `pi_approx`, `dom_reactor` byte-identical. A program using thirteen sequence operators without a
  handler: wasm 26,857 -> 28,723, JVM 51,246 -> 53,043. The shaker corpus class's constant pool
  stood at 51,893 of its 52,000 tripwire after this item's ci-spec rows
  (`JvmDeadMethodEliminationCorpusTest`), which is why the ci-spec case holds one row per mechanism.
- **The array-shape accessors** (2026-09-27, `.todo/a57`; they reported unnamed interpreted, a
  datum-less cast failure on the JVM -- `array-element-type`, `adjustable-array-p` and
  `array-has-fill-pointer-p` answered `T`/`NIL` there -- and trapped on wasm): the rows are
  `OperandTypes.ARRAY_SHAPE_OPERATORS`, appended after the character comparisons, and `REWRITTEN`
  maps `%set-fill-pointer` to `(SETF FILL-POINTER)` and `%array-disp-target` to
  `ARRAY-DISPLACEMENT` (their one user each; `WasmOperandTypes.LOWERED_TO` adds
  `(SETF FILL-POINTER)` for `FILL-POINTER`). Interpreter: the rows name
  `requireArray`/`requireGeneralArray`'s report; `adjust-array` checks its array first (its
  `:displaced-to` half built a fresh view of anything). JVM: one shared `_ckArr`
  (`JvmArrayRuntimeBuilder.CK_ARRAY`: any representation passes, a quote-framed `String` being
  the string) under the operator's wrapper at each site, after the `java:` guard
  (`JvmArrayCompiler.emitArrayOperandCheck`) -- a check at the site rather than in each helper
  because the predicates must answer nil for a packed array and a string, which a helper testing
  the general shape cannot tell from a non-array. The lite `array-element-type` expansion (no
  typed or packed array; now also a `java:` program without the array runtime) is `(if (stringp
  v) 'character (if (%arrayp v) t (%operand-type-error v 'array-element-type 'array)))`. wasm, EH
  mode only: `_arr_check_rank(x, ANY_RANK | id << 8)` at each predicate's site over its slot; the
  fill-pointer surface's five sites call `_fp_hdr` instead (next bullet). `adjust-array` is checked by the shared expansion (`LispMacroExpander.checkedArrayOf`,
  an `arrayp` test ahead of every internal reader; `#'adjust-array`'s wrapper rebinds its array
  through it before its fill-pointer default reads it).
- **A symbol is no array** (2026-09-27): a symbol shares the string representation on both
  compiled backends (a bare `String`; wasm's string struct without the quote frame), and the
  array checks tested the representation alone, so `(aref 'foo 0)` answered `#\O` and
  `(array-dimensions 'foo)` `(1)` on the JVM and wasm (EH mode). `_aref1`, `_arrayDims`,
  `_arrayCheckRank` and `_aset1`'s check test the frame as `stringp` does
  (`JvmArrayRuntimeBuilder.emitStringTest`); wasm's EH `_arr_check_rank` lands `ARRAY` for an
  unframed string struct (a non-EH module keeps its bytes).
- **A vector without a fill pointer** (2026-09-28, `.todo/a65`; it was a simple-error
  interpreted -- `FILL-POINTER: string has no fill pointer`, `vector-pop: vector has no fill
  pointer`, `... not applicable to a packed float array` -- lowercase texts or a datum-less cast
  failure on the JVM, a trap on wasm): `fill-pointer`, `(setf fill-pointer)`, `vector-push`,
  `vector-push-extend` and `vector-pop` handed an ARRAY that has none (a string literal, a simple,
  packed, adjustable or rank-2 array) report `OP: The value X is not of type (AND VECTOR
  (SATISFIES ARRAY-HAS-FILL-POINTER-P))`, the expected type the LIST
  (`OperandTypes.FILL_POINTER_VECTOR_TYPE`) -- SBCL's run-time check's type; its compile-time
  derivation says `(AND VECTOR (NOT SIMPLE-ARRAY))`, which an adjustable vector without a fill
  pointer satisfies. A value that is no array at all keeps `ARRAY`, checked first, as every
  array-shape accessor (and the `java:` host guard) reports it; SBCL reports the compound type
  there too -- both are CL type-errors, and the split keeps one check per layer. The neighbours:
  a stored fill pointer that is no integer in `[0, dimension]` -- a wrong-type one included -- is
  `(SETF FILL-POINTER): The value V is not of type (INTEGER 0 dim)` (`OperandTypes.fillPointerType`,
  inclusive: SBCL's), checked after the vector; an empty pop the simple-error
  `OperandTypes.VECTOR_POP_EMPTY` (CLHS: "an error of type error"); `vector-push-extend` checks
  its vector before its extension. Interpreter: `Environment.fillPointerVector` ahead of each
  built-in, `OperandTypeException.notOfType` carrying the type as a Lisp list (it generalizes the
  out-of-range subscript's `(INTEGER 0 (d))`, now built the same way). JVM: a named `_ckFp` at
  each site (`JvmArrayRuntimeBuilder.CK_FILL_POINTER`, replacing `_ckArr` and the packed guards
  there): an `ArrayList` whose header slot 1 is set answers, anything else goes through `_ckArr`
  (the `ARRAY` report) and then throws `_teOf(x, type)` (`JvmOperandTypeRuntime.TE_OF`: the type
  printed by `_lispToString` and recorded as the object, kept verbatim by `_opTypeErr`'s compound
  arm). The helpers behind it read the fill pointer unchecked; `_setFillPointer` is invoked
  through the wrapper and throws `_teOf(v, (INTEGER 0 cap))`. wasm, EH mode only (a non-EH module
  keeps its cast, its trap and its bytes): `_fp_hdr(x, id)` (`FUNC_FP_HDR`,
  `WasmOperandTypes.buildFillPointerCheckBody`) answers the header of a cell whose header car is
  the dims array (a hash table shares the cell box) and whose meta car is an i31 -- that test
  FIRST, so the hot path makes one call; `_arr_check_rank` only on the refusal path (as the first
  version called it up front, an EH push/pop loop cost +15%) -- and lands through
  `_type_err_of(culprit, type)` (`FUNC_TYPE_ERR_OF`, `buildCompoundLandingBody`), a landing for any
  compound type the caller builds: `OP: ` from the register's row, then the value and the type
  printed by `_prin1_to_str`, the type object in `expected-type`. Its symbols are interned with
  the other operand texts (`Texts.compoundNames`); the second reader of the operator table blob.
  `%set-fill-pointer` builds `(INTEGER 0 cap)` at its site; the empty pop throws the text as a
  message payload (`WasmErrorCompiler.emitThrowPayload`). Cost, measured 2026-09-28 (wasmtime 49,
  JDK 25): `hello_world`, `pi_approx`, `dom_reactor` byte-identical on wasm and the JVM, zlib P1
  130,172 -> 130,105, size level 99,987 -> 99,920, component 133,809 -> 133,815 (data-segment
  layout: zlib reaches no fill-pointer site), JVM class byte-identical; a push/pop program 14,487
  -> 14,867 wasm, 24,385 -> 24,660 class. A `vector-push-extend`/`vector-pop` loop (200k x40, 4
  pinned cores, 5-6 interleaved runs): wasm EH 843-990 ms before, 829-865 after; JVM 716-781 before,
  728-777 after -- noise. Pinned by `FillPointerVectorFixture` through
  `fillPointerSurfaceRefusesAVectorWithoutOne` (`LispEvaluatorTest`,
  `WasmLispCompilerIntegrationTest`) and `compileAndRunFillPointerSurfaceRefusesAVectorWithoutOne`
  (`JvmLispCompilerTest`), and `ci-spec.yaml`'s `fill-pointer-surface-refuses-a-vector-without-one`.
- **Cost, measured 2026-09-27** (wasmtime 49, JDK 25; zlib spells none of the operators, so this is
  the internal uses -- the `class-of`/`typep` expansions' `array-element-type`, the library's
  `vector-push-extend` -- and the string-frame tests): zlib P1 129,608 -> 129,704, unoptimized
  540,298 -> 541,334, size level 99,423 -> 99,519, component 103,403 -> 103,528, JVM class 187,753
  -> 188,109; `hello_world`, `pi_approx`, `dom_reactor` byte-identical on wasm, and `hello_world`,
  `pi_approx` on the JVM. A `vector-push-extend`/`vector-pop` loop (200k x20, JDK 25, 8 interleaved
  runs on 4 pinned cores): 391-467 ms before, 355-425 after -- noise; its class +524 B (`_ckArr`
  and the wrappers). Corpus class constant pool 43,845 of 52,000 (`JvmDeadMethodEliminationCorpusTest`).
- Pinned by `WrongTypeArgumentFixture` through `sequenceAndAccessorOperatorsNameTheirWrongTypeArgument`
  (`LispEvaluatorTest`, `WasmLispCompilerIntegrationTest`) and
  `compileAndRunSequenceAndAccessorOperatorsNameTheirWrongTypeArgument` (`JvmLispCompilerTest`), and
  `ci-spec.yaml`'s `sequence-and-accessor-operators-name-their-wrong-type-argument`.

## An out-of-range subscript is a type-error naming its bound
**Invariant: a subscript outside its dimension reports `OP: The value S is not of type (INTEGER 0
(D))` -- CL's `type-error` for an array index, SBCL's `invalid-array-index-error` -- as a catchable
`type-error` whose `type-error-datum` is the subscript and whose `type-error-expected-type` is the
LIST `(INTEGER 0 (D))`, byte-identical on all four backends (wasm-GC: in EH mode).** Closed
2026-09-26 (`.todo/a00`); it was `aref: index out of bounds` interpreted, the host's `Index 5 out of
bounds for length 5` (the length counting header slots) uncaught on the JVM and an uncatchable trap
on wasm-GC even in EH mode.

- **Per axis.** Each subscript is checked against ITS OWN dimension, the first failing axis
  reporting (`D` is that axis's dimension): until then only the row-major total was checked, so
  `(aref m 0 2)` on a 2x2 array answered `m[1][0]` on all four. A row-major access
  (`row-major-aref`, rank 0) is bounded by the total size. A bignum subscript is out of range, not a
  wrong-type one.
- **Names**: `AREF` (also `svref`, and `elt` of a vector, which expands to `aref`), `(SETF AREF)`,
  `ROW-MAJOR-AREF`, `(SETF ROW-MAJOR-AREF)` -- the operator the wrong-type subscript already
  reported under ("A wrong-type argument names its operator"). `OperandTypes.indexType` holds the
  text.
- **Order**: every subscript's type, then a store's value (its evaluation and a packed store's
  coercion), then the bounds -- what the interpreter's `subscriptValues` -> `asDouble` -> `inBounds`
  does and every compiled store now does: `(setf (aref dv 5) "x")` on a 3-element `double-float`
  vector reports `REAL`, and a value form's side effects run before an out-of-range store fails.
- **Interpreter**: `Environment.subscriptValue` / `inBounds` / `boundedSubscripts` ahead of each
  representation's accessor; `OperandTypeException.outOfRange` is named by the built-in seam like a
  wrong-type operand and `expectedType()` builds the list. A string's `aref`, `char`, `schar` and
  their stores report the same text (`charRef`, `scharSet`, `storeStringChar`) -- the decision for
  `.todo/186`, whose compiled string paths are still unchecked.
- **JVM** (`JvmOperandTypeRuntime`): `_oob(datum, dim)` builds the unnamed report and records the
  list under a pad; `_ckBound(Object, int)` checks a boxed subscript, `_ckBoundJ(long, int)` a raw
  one through `Objects.checkIndex`, the JIT's range-check intrinsic. Every accessor bounds its
  subscripts through them -- `_aref1`/`_aset1` against the total (`emitFlatBound`: a packed array's
  `long[]` length, a boxed one's `ArrayList` size, a displaced view's own dims product),
  `_aref2`/`_arefN` and the stores per axis, the `_fv*` widths against `d.length - off` and the
  header dims, `_ivAref1`/`_ivAset1`, the quantized `_qmAref*` -- and reads now go through the
  operator's wrapper as stores did. `_opTypeErr` finds the type after `TYPE_INFIX`, not after the
  last space, and keeps a compound type (and the record's list) verbatim under any operator. The
  typed loops (`.kb/jvm-typed-loops.md`) check each subscript with `_ckBoundJ` under the access's
  wrapper; the int-fusion aref leaf bails on a long index past the int range and its fallback runs
  under `AREF`'s wrapper.
- **wasm-GC, EH mode** where the operator table names an access (`Operators.indexed`, which also
  interns the two texts and gives `_type_err` its index arm: kind `-1 - bound`, the type built as
  the list): a read's subscript goes through `_idx_ref(array, subscript, op)` INSTEAD of `_idx_chk`
  -- the integer check and the flat bound by representation in one call; a store checks after its
  value through `_idx_ref`, or, at the speed levels, an inline compare against the arm's own bound
  (a packed integer vector's length, dimension 0) that calls it only on a miss; a pinned-kind
  read at the speed levels does the same after `_idx_chk`. A packed integer store's raw value waits
  in an i64 temp. `_idx_chk` answers a limb-tier integer rather than trapping in `_int_val`.
- **wasm-GC, every mode**: a rank-2+ access checks each axis (`_idx_in`, a trap outside EH mode --
  the wrap-around above was a wrong answer there); a `--simd` block's zero padding past its count is
  checked (`_idx_bound`), where no engine check reaches. **A rank-1 access outside EH mode keeps the
  engine's own trap and its module byte for byte**, except that a displaced view's out-of-range
  read still reaches through to its target there.
- **Cost, measured 2026-09-26** (wasmtime 49, JDK 25): zlib P1 124,519 -> 127,370 B (+2.3%), size
  level 95,803 -> 96,729, component 128,550 -> 131,399 -- EH mode (chipz's `unwind-protect`) and
  every access checked; `hello_world`, `pi_approx`, `dom_reactor` and a non-EH rank-1 array program
  byte-identical. JVM class: zlib 185,749 -> 186,421, pi_approx 14,495 -> 14,576 (the
  compound arm of `_opTypeErr` every wrapped program carries). Run time: chipz inflating 20 KB x300 in an EH
  module 782 -> 829 ms (+6%); EH micro loops over 1000 elements, 400M accesses: `u8` read 425 -> 503
  ms, `u8` write 985 -> 1172, a declared `double-float` read+write loop 5.1 -> 6.6 s, a general
  vector 962 -> 1137, a rank-2 packed read 293 -> 368. JVM (4G accesses): typed `double-float` loop
  2624 -> 2654 ms, packed `u8` 2704 -> 2730, general vector read 1148 -> 1131, write 1111 -> 1215,
  rank-2 packed 1142 -> 1127. **A hand-written compare in the typed loop cost it 27%**, and
  `Objects.checkIndex` there 1.7%; the same intrinsic in the BOXED `_ckBound` made a general-vector
  store 4x slower (the extra inline depth), so the boxed check stays a compare.
- Pinned by `ci-spec.yaml`'s `out-of-range-subscripts-are-type-errors-naming-their-bound` and the
  `standalone:` `uncaught-out-of-range-subscript-report`, and the
  `outOfRangeSubscriptsAreTypeErrorsNamingTheirBound` triple (`LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`).
- **`#'aref` / `#'array-row-major-index` as a FUNCTION VALUE** (2026-09-27, `.todo/a58`) shared one
  Horner fold (`BuiltinFunctionWrappers.rowMajorFoldBody`) that checked only the row-major TOTAL,
  never the subscript count or each axis's own bound -- `(apply #'aref m '(0 2))` on a 2x2 array
  silently answered `m`'s row-major element 3 instead of naming the out-of-range column, and
  `(apply #'aref m '(1))` silently answered the row-major element at index 1 instead of rejecting
  the short subscript list. The fold now rejects a subscript COUNT that does not match the rank
  with a plain (non-`type-error`) `error` reading `aref: expected N subscripts, got M`, and checks
  each subscript against its own dimension inside the loop, signalling the same `type-error` the
  call-position path does -- byte-identical text, catchable the same way, on the interpreter, JVM
  and both wasm backends (`#'aref`'s call-position bound check is a separate, already-correct
  backend intrinsic and is unaffected). `#'aref` reached the interpreter's native `AREF`
  `LispFunction` already and so was never wrong there; `#'array-row-major-index` had no such native
  registration and fell through to the unchecked fold on every backend, interpreter included.
  Pinned by `LispEvaluatorTest#functionValueArefAndArrayRowMajorIndexCheckRankAndBounds`,
  `JvmLispCompilerTest#compileAndRunFunctionValueArefChecksRankAndBounds` and
  `WasmLispCompilerIntegrationTest#compileFunctionValueArefChecksRankAndBounds`.
- **`elt` of a LIST outside it** (closed 2026-09-28, `.todo/a59`): `(elt '(1 2) 5)` answered
  `NIL` and `(elt '(1 2) -1)` `1` on all four backends -- the list arm was `(nth idx seq)` -- and
  `(setf (elt l -1) v)` stored into the first cell. The list arm is now `(car (%elt-cell seq idx))`,
  the `setf` place's `(rplaca (%elt-cell seq idx) v)`: ONE walk counting the cells it passes, so an
  index it never meets (past the end, negative, a bignum) reaches nil having counted the length and
  reports `ELT: The value I is not of type (INTEGER 0 (LEN))`, datum the index, expected type the
  list -- the same report an `aref` subscript gives, through the same raw machinery: interpreter
  `Environment`'s `%ELT-CELL` -> `OperandTypeException.outOfRange`; JVM `_eltCell`
  (`JvmEltCellRuntimeBuilder`, a method for the `_nthcdr` OSR reason) -> `_opTypeErr(_oob(i,
  len), "ELT", ...)`; wasm-GC `WasmEltCellCompiler`'s inline walk -> `_idx_in(i, len)` under the
  operator register, which is the EH-mode landing when the module's table is `indexed()` and a trap
  otherwise, exactly as a rank-1 `aref` outside EH mode. A non-list met on the walk is `ELT`'s
  `LIST` type-error, a non-integer index its `INTEGER` one. `ELT` is a named operator (last in
  `OperandTypes.operators()`, `%ELT-CELL` rewritten to it); a vector's `elt` still reports `AREF`,
  a string's `CHAR`.
  - **Why not a Lisp-level expansion** (measured 2026-09-27): `LispMacroExpander.expandElt`
    expanding the list arm into a `do` that eagerly signals `(error 'type-error ...)` read correctly
    in isolation but made even `(print 42)` fail on wasm (`%OBJ-NEW reached the compiler with no
    instance type emitted`): `elt` is read by bodies the compiler injects unconditionally
    (`BuiltinFunctionWrappers`' `findFamily` wrapper, spliced into every program) where
    `mayCreateInstances`/`conditionNarrowing`/`WasmLispCompiler.usedLayoutTags` -- which scan the
    source program -- cannot see the site. A raw host failure classified lazily needs no layout at
    the site: the landing builds the instance-less payload when no class is baked.
  - **The operator name follows the SPELLED program on wasm.** An `elt` only a backend lowering
    introduces -- `make-array :initial-contents`'s rank >= 2 fill, whose rows are read with `elt` --
    reports unnamed (`The value 2 is not of type (INTEGER 0 (2))`) in a module that never spells
    `elt`, and traps where no table names an element access.
  - **What moved with it**: the prelude `search`/`mismatch` fall back to `(elt seq i)` once their
    list cursor runs out (`.kb/seq-coerce-runtime.md`), so an invalid list bound that reaches that
    read now signals (`(search '(1 2 3) '(1 2 3) :start2 -1)` answered 0); the compiled `make-array`
    rank >= 2 fill signalled on a short row where it padded with `NIL` -- until the fill checked
    its shape (next entry).
  - **The walk counts DOWN** from the target, as `_nthcdr` does (cells passed = target -
    remaining; a negative or wide index is target -1, which only moves away from 0), and checks
    the found cell's consness once after the loop. Counting UP with an i31 counter compared
    against the target cost a 100M-iteration `(elt list (mod i 10))` loop +22% on the JVM and
    +23% on wasm; counting down, +10% (757 -> 834 ms) and +7% (2167 -> 2312 ms) over the old
    `(car (nthcdr ...))` (2026-09-28, JDK 25, wasmtime 49, pinned core). Size: `(print 42)`
    byte-identical on both; one `elt` site +81 B wasm, +63 B class; with a `handler-case`
    (the compound `_oob` arm travels) +145 B wasm, +377 B class.
  - Pinned by `eltOfAListOutsideItIsATypeErrorNamingItsLength` (`LispEvaluatorTest`,
    `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`) and two lines of `ci-spec.yaml`'s
    `out-of-range-subscripts-are-type-errors-naming-their-bound`.

- **`make-array :initial-contents` whose shape does not match the dimensions** (closed 2026-09-28,
  `.todo/a68`): the compiled fill (`LispMacroExpander.lowerInitialContentsMakeArray`) ran a rank-1
  array to the contents' length -- a short list padded with `NIL`, a long one overran into the
  store's bound check -- and a rank >= 2 row to its `elt` fallback; the character lowering returned
  a string of the contents' length whatever the dimension. Each level now checks its length and
  signals the interpreter's `Environment.fillInitialContents` text, `MAKE-ARRAY :initial-contents
  dimension D has N elements, expected M`, as a plain `error` with a format control -- a
  `simple-error` on all four backends. A literal rank-0 dims (`'()`/`nil`) now stores the contents
  as the one element (`#0A5`) where it took the contents' `length`.
  - **Why a plain `error` is safe here**: the lowering runs during code generation, after the
    scans that decide which condition layouts a module carries; a `simple-error` needs none of
    them. `(print 42)` is byte-identical and a make-array program with no handler compiles on
    every backend.
  - **One walk of a list, one report per level.** An outer level compares `length` up front (the
    interpreter's order: contents wrong at two levels report the outer one). The leaf level --
    the whole of a rank-1 fill -- streams its check on the cons cursor
    (`buildInitialContentsLeafFill`): a non-list's length up front, a list that runs out leaves the
    cursor `nil` (the remaining slots store back their own value, valid for any element type), one
    with cells left over leaves it a cons; all three reach ONE `error` after the loop. The rank-1
    fill thereby lost its `length` pre-walk. The store is spelled once over a three-way read: a
    store per branch cost a 1000x1000 list fill 921 ms against 611 on wasm.
  - **A run-time `:element-type` fills ONCE** (`lowerRuntimeElementTypeMakeArray` ->
    `initialContentsFill`): the dispatch arms allocate and one fill follows, where every arm used
    to carry its own copy. The check took `#'adjust-array`'s wrapper -- that shape, eight arms --
    past HotSpot's 8000-bytecode limit (`JvmLibraryMethodSizeTest`: `ADJUST-ARRAY`=8491 over
    ironclad); hoisted, a lone `#'adjust-array` program's method went 5,205 -> 3,223 bytecodes,
    its class 50,860 -> 46,567 B and its P1 module 35,832 -> 30,953. The character arm keeps its
    own string-copy spelling where `lowerCharacterInitialContentsMakeArray` serves it (rank 1, no
    other keyword). The literal dims form, not the arm's variable, picks the fill's shape, so a
    run-time designator over a literal `'(2 2)` now fills (every compiled backend failed it; the
    JVM's degraded float representation there is `.todo/a70`).
  - **Cost, measured 2026-09-28** (JDK 25, wasmtime 49; 20 fills of a 1000x1000 list and of a
    1M-element list, inside a `defun`): wasm rank-1 684 -> 554 ms, rank-2 647 -> 655; JVM rank-1
    ~215 -> ~155, rank-2 flat within noise. Size of one `defun` holding one site: rank-1 class
    21,558 -> 24,674 B (of it ~1.4 KB the `_lispToDisplayString` trio the `~D` arguments pull in,
    shared with any formatted `error`), P1 7,252 -> 6,421; rank-2 class 22,752 -> 26,830, P1
    7,395 -> 7,711; character rank-1 +397 B class, +43 B P1. The same loops written as ONE
    top-level form, every site inlined into it, measured +40% on the JVM; inside a `defun` the
    difference vanished -- measure a JVM fill inside a function.
  - Pinned by `compileAndRunMakeArrayInitialContentsChecksItsShape` (`JvmLispCompilerTest`) and
    `makeArrayInitialContentsChecksItsShape` (`WasmLispCompilerIntegrationTest`, P1 and component).
  - **A dims form whose RANK is only known at run time** (closed 2026-09-28, `.todo/a69`) took the
    rank-1 fill and failed at its first store on every compiled backend (JVM `aref: expected 2
    subscripts, got 1`, wasm a trap) -- `adjust-array` of a rank >= 2 array with `:initial-contents`
    included, since its dims are a run-time list. `isRankOneDimensionSpec` now picks the fill: a
    literal integer / one-element list, or a call to a number-answering standard operator
    (`length`, `+`, `array-dimension`, ... -- `NUMBER_VALUED_OPERATORS`), keeps the rank-1 fill
    byte for byte; anything else (a variable, a user call) takes
    `buildRunTimeRankInitialContentsFill`. That walks the dims value depth first over an explicit
    stack of `(sequence . dims-suffix)` entries -- no recursive helper, the lowering runs during
    code generation -- running the ONE streaming level fill per popped sequence: the last axis
    stores at a running row-major index, an outer axis collects its rows and pushes them only after
    its length checked out, so the report order is the interpreter's recursive one (one `error`
    site; the axis number is a `~D` argument, `(- (length dl) (length ds))`, computed only by the
    report). An empty dims value stores the contents as the one element. The character lowering
    branches on the run-time rank the same way: rank 1 copies into a string, any other allocates
    the character array and takes that fill (a literal rank-0 dims now declines it too).
  - **Cost, measured 2026-09-28** (same setup as above, second run in-process): a variable-dims
    rank-1 fill of a 1M list, JVM 242 -> 212 ms, wasm 690 -> 630; a variable-dims 1000x1000 fill
    (failed before) JVM 154, wasm 562, against 190 / 506 for the literal `'(1000 1000)`. Size of a
    `defun` holding one variable-dims site: class 25,198 -> 28,046 B (method 640 -> 1,156
    bytecodes, plus the shared `_sub` helper), P1 7,381 -> 8,856; `(funcall #'adjust-array ...)`
    class 45,464 -> 48,298, P1 30,397 -> 31,634. `(print 42)`, a literal-dims site and a
    `(length s)` dims site are byte-identical.
  - Pinned by `compileAndRunMakeArrayInitialContentsFillsARunTimeRank` (`JvmLispCompilerTest`)
    and `makeArrayInitialContentsFillsARunTimeRank` (`WasmLispCompilerIntegrationTest`, P1 and
    component).
- **`subseq` outside its sequence** (2026-10-05) is a `type-error` too, but keeps its own text,
  `SUBSEQ: invalid bounds S, E for KIND of length N`, rather than this section's `OP: The value
  ...` shape: datum the first bound outside its range, expected type `(INTEGER low N)`. It does
  not go through `_oob` / `_idx_in` (they word the report): interpreter
  `OperandTypeException.reported`, JVM `_subseqBad` over the `_teTl` record, wasm-GC the
  `_subseq_bad` landing building the instance where `_type_err` would
  ([subseq-runtime.md](subseq-runtime.md), "Bounds check"). A bound that is no integer takes
  the same refusal, not an `INTEGER` operand check: it is outside its range like any other.

## Argument-shape errors signal a catchable program-error
**Invariant: a keyword the operator does not accept, an odd keyword tail and a non-keyword in
keyword position signal a CATCHABLE `program-error` carrying one text -- `REMOVE expects keyword
arguments :TEST/:TEST-NOT/:KEY/:START/:END/:COUNT/:FROM-END, got: :BOGUS` -- on all four backends,
from a call form and from a
first-class call alike, and `:allow-other-keys` suppresses the check per CLHS 3.4.1.4.1.1.** They
used to leave the expander as `IllegalArgumentException`s no `handler-case` could see (the ANSI
report's two top rows: 370 + 299 lost forms) and to fail the COMPILE on the compiled backends.

- **One validator, `LispMacroExpander.keywordTailProblem(name, parts, start, allowed)`**: the
  complaint or null, over argument FORMS (the expansion-time check behind `keywordTailError` /
  `testKeyKeywordTailError`, 27 operators) and over argument VALUES (the interpreter's first-class
  validators, `LispEvaluator.requireKeywordTail`, and `Environment`'s `make-string-output-stream`),
  so the two cannot disagree on the rule or the text. The LEFTMOST `:allow-other-keys` pair decides;
  a value that is not a literal nil counts as true (a computed one suppresses statically -- the
  lenient direction); the key itself is always accepted; an odd tail is malformed whatever the
  suppression says. Every message spells the allowed set upcased and `/`-joined.
- **The expander RETURNS the signal instead of throwing.** `programErrorForm(call, text)` is
  `(%program-error "text")`, `SourceProvenance.inherit`ing the call's position, and the rejected
  call EXPANDS to it (the keyword checks and the eleven `X expects N arguments, got M` arity checks;
  `LambdaLists.unknownKeyCheck`'s unknown-`&key` signal is the same primitive over a runtime-built
  message). Interpreter: `evalConsRareOperator` throws
  `LispEvalException.ofClass(PROGRAM_ERROR_CLASS_NAME, text)` -- class-named, the instance synthesized
  at the catch like a bad `car`'s. Compiled: `lowerProgramError` emits
  `(%error-cond (%obj-new '%class-PROGRAM-ERROR ... text) text)` behind a handler landing pad
  (`Ctx.hasLandingPad` = `establishesLandingPad(program)`, an operator-position scan for the
  `LANDING_PAD_HEADS`, which implies the instance gate) and a plain `(%error text)` otherwise --
  nothing can observe the class without a pad and the top-level line is identical either way. Both
  compilers first call `CompileWarnings.warnStaticProgramError`: `warning: <text>; compiled as a
  call-time program-error` at the call's position (the undefined-function precedent), for a LITERAL
  message only. `PROGRAM-ERROR` is seeded with `format-control`/`format-arguments` (the fifth
  reporting class) so the instance reports its text through the ordinary `simple-condition` report.
- **Three gates stand in for the construction, which happens during BODY compilation where no scan
  sees it**: `conditionNarrowing` marks `%class-PROGRAM-ERROR` constructible behind a pad, beside the
  raw-failure classes; `WasmLispCompiler.usedLayoutTags` bakes the layout on the same predicate; and
  `WasmErrorCompiler.compileCond` compiles the message operand when the program has NO report
  renderer (`routesConditionReports` off -- every source-visible `%error-cond` producer flips routing
  on, so only this lowering can reach it), because the entry landing pad's report of the instance can
  then come only from the payload cdr. Without the third, `(print (handler-case (error "warm") (error
  (e) :ok))) (remove 1 '(1 2) :bogus 4)` printed `Unhandled condition: ` and nothing else.
- **The interpreter's evaluation seam**, the catch clauses of `LispEvaluator.evalCons`'s loop frame
  (`.kb/interpreter-tail-calls.md`): an `IllegalArgumentException` / `IndexOutOfBoundsException` escaping a form is a
  `program-error` (that is how the expander and the special forms report a malformed form), a cast /
  arithmetic / negative-size failure takes `rawFailureConditionClass`'s rule, and an
  `UnsupportedOperationException` -- a rontolisp LIMITATION (`setf does not support place`, `map
  supports only the 'list ...`) -- stays raw, since catching a limitation would let a program run on
  past what this implementation cannot do. So the long tail of per-site rejections that are NOT
  lowered (`setf: odd number of arguments`, `make-sequence: unsupported result type`) is catchable
  interpreted and a compile-time `error:` compiled -- the CL-conformant asymmetry: a compiler may
  reject at compile time what the evaluator signals at run time. `handler-bind` handlers for these
  run at the pad, not at the signal point (the compiled-backend semantics).
- **Arity**: a surplus argument past an `&optional` tail is `Function expects at most N ...`, a
  check the lambda-list desugaring emits on every backend ([lambda-lists.md](lambda-lists.md)).
  The interpreter's `Function expects N argument(s), got M` (lambda application), `Macro X
  expects ...`, `Environment.requireArgCount*` and every inline `X expects N arguments, got M` built-in
  check are `program-error`s (the ANSI suite's next six rows). `requireArgCount` /
  `requireMinArgCount` spell theirs through `ClosRegistry.arityMessage(name, ...)` -- so `1
  argument`, not the `1 arguments` they wrote until 2026-09-26. The compiled backends signal the same
  through a function VALUE ("A wrong argument COUNT" below), `apply` included since 2026-09-12.
  `-`/`/` (no identity, unlike `+`/`*`: `compiler/ArithmeticIdentities`) inline-check their own empty
  argument list in `Environment.registerArithmetic` rather than fall through to the generic
  `IndexOutOfBoundsException` conversion above, so `(-)`/`(/)` signal the SAME text interpreted as
  `ArithmeticIdentities.of` rejects at compile time, instead of the interpreter's former raw `Index 0
  out of bounds for length 0`. Since 2026-09-26 that text is the count report, `- expects at least 1
  argument, got 0` (it was `- requires at least one argument`), which is also what `(funcall #'-)`
  says compiled: the `-`/`/`/`min`/`max` wrappers take their first argument as a required `n`
  (`(n &rest r)`), where a bare `&rest` folded over nil into a type-error or answered nil.
  The first-class twins are pinned on
  `#'member` / `#'find` / `#'position` and, since the family took the bounding keywords, on
  `#'remove` too -- its wrapper now forwards a keyword tail instead of taking a fixed two arguments
  ([sequence-bounding-keywords.md](sequence-bounding-keywords.md)).
- ANSI `sequences` chapter, interpreter, 2026-09-08 (`ansi-test/measure.sh sequences`): 1,850 /
  2,454 pass with 849 forms lost before; 2,191 / 3,274 pass with 29 lost after. The
  `X expects keyword arguments ...` rows that remained (350 + 228) were counted test ERRORS naming
  real gaps -- `:count`/`:start`/`:end`/`:from-end` on the substitute / remove family, `:from-end`
  on `count` -- rather than forms the driver could not evaluate. Those gaps are closed
  ([sequence-bounding-keywords.md](sequence-bounding-keywords.md), 2026-09-11): 2,861 / 3,287 pass,
  166 fail, 265 error -- +657 tests, no test that passed before failing after.

## A wrong argument COUNT through a function value
**Invariant: calling a function VALUE with a count its lambda list cannot take signals a catchable
`program-error` on every backend, spelled by the ONE `ClosRegistry.arityMessage`** -- `Function
expects [at least ]N argument(s), got M`, with the OPERATOR in place of `Function` when the callee
is a built-in's (`CONS expects 2 arguments, got 1`; "Naming the operator" below). A DIRECT call
is judged where the backend compiles it ("A DIRECT call of a built-in" and "A DIRECT call of a
program's own function" below); everything else (`funcall`, `mapcar`, `sort`, a bare `(f x)` whose
head is an expression) arrives at an `_invoke_N` dispatcher, whose no-match arm used to answer nil
on the JVM and `unreachable` on wasm-GC. A silent nil is the worst of the three: an ANSI
`signals-error ... program-error` row passed interpreted and returned a WRONG VALUE compiled.
Pinned by ci-spec `wrong-arity-funcall-signals-program-error` and `JvmLispCompilerTest`
`compileAndRunWrongArityThroughAFunctionValueSignalsProgramError` /
`...ThroughABuiltinDesignatorNamesTheOperator` (and its wasm and interpreter twins).

- **JVM** (`JvmRuntimeBuilder.ArityReporting`): the arm calls `_arityErr(funcId, got)`, which reads
  the callee's SHAPE (required count doubled, plus one for a `&rest` tail) out of a STRING indexed
  by funcId and throws `new WrongMethodTypeException(_arityMsg(shape, got))`. A search tree over the
  dispatchable ids -- the shape the dispatchers themselves use -- is the wrong structure here: ~15
  bytes per callable in ONE method, and the cl-postgres corpus overflowed the signed 16-bit branch
  offset on it. The table is one byte per funcId and three instructions, whatever the program's
  size. `JvmHandlerCaseCompiler.emitRawFailureTest` recovers `program-error` from the exception's
  CLASS (`JvmRuntimeBuilder.ARITY_EXCEPTION_CLASS`, the sixth entry in
  `LispMacroExpander.rawFailureConditionClasses()`). It used to recover it from the `Function
  expects ` prefix of the TEXT (the unbound-variable precedent), which a named report does not
  start with -- and which a user's `(error "Function expects ...")`, a plain `RuntimeException`,
  matched too: that user error was a `program-error` on the JVM alone until 2026-09-26. The one
  visible cost: an UNCAUGHT report's JVM stderr line names `java.lang.invoke.WrongMethodTypeException`
  where it said `java.lang.RuntimeException` (the `Unhandled condition:` line is unchanged).
- **wasm-GC** (`WasmRuntimeBuilder.ArityReport`): the `br_table` already has a label per funcId, so
  the ids this arity cannot serve point at one ARM PER SHAPE instead of at the default; the arm puts
  the shape in the (now dead) funcId local and branches to one assembly block per dispatcher, which
  builds the message from five interned pieces -- the two counts rendered by `_prin1_to_str` over an
  `i31`, as the interpreter prints them -- constructs the `program-error` instance the way
  `%obj-new` does and throws the `(instance . message)` payload on `$lisp-cond`. EH mode behind a
  handler landing pad only; outside it the arm is the `unreachable` it was, byte for byte. The
  pieces are interned on FIRST USE: eagerly interning them costs a module whose dispatchers all turn
  out to be dead an extra data-segment header.
- **`apply` is covered by a COUNT GUARD, not by a dispatch miss** (2026-09-12). Neither of its two
  shapes can miss: a literal `(apply #'f ... list)` compiles to a physical direct call that reaches
  no dispatcher (`Jvm/WasmApplyCompiler`), and a computed designator reaches the SPREAD dispatcher,
  which carries a case for every callable and reads the parameters out of the list with car/cdr --
  both answer nil past its end, so a short list BINDS nil and a long one drops its tail. Both sites
  therefore carry one shared helper that measures the LIST against the callee shape baked at the
  site: `_arityChk(argList, shape)` on the JVM (`JvmRuntimeBuilder.buildArityChkBody`, ~6 B per
  site) and `_arity_chk(argList, shape) -> i32` on wasm-GC
  (`WasmRuntimeBuilder.buildArityChkBody`, 7 B per site including the `drop`). The wasm helper is a
  CONDITIONAL function index (`WasmLispCompiler.arityChkFuncIndex`, right after the extra per-arity
  dispatchers) decided in the pre-pass, because it shifts `userFuncBase()`; a module without it is
  byte-identical. Inlining the throw instead would have cost ~100 B at every one of those sites,
  over a spread dispatcher that is one case per callable.
  - The walk **stops at the first non-cons**, and a list that ends in anything but nil signals the
    interpreter's `simple-error` `APPLY: last argument must be a list`
    (`ClosRegistry.APPLY_IMPROPER_LIST_MESSAGE`) BEFORE the count is judged (2026-09-26): only an
    `apply` whose last argument is no proper list builds one. JVM: a plain `RuntimeException`, as a
    compiled `(error "...")` is; wasm: the `(nil . message)` payload of a plain `%error`. Before, a
    computed designator was a `type-error` on the JVM and a cast-failure trap on wasm -- a dead
    length walk in `_apply`, left over from the per-arity ladder, cast every cell ahead of the
    dispatcher and is gone -- and a literal target answered `(f 1)`.
  - The ALIGNED literal `apply` (a variadic target whose required parameters the leading arguments
    cover) reaches no count check; it passes its rest tail through the same helper with the shape
    `(0, variadic)`, for the properness alone, unless the last argument is proper by its shape
    (`LispMacroExpander.applyListProvablyProper`: `nil`, a quoted proper list, `(list ...)`). So
    does an eval-built closure whose lambda list has a `&` marker (no count to check).
  - The guard is why this half waited: what it found FIRST was not a user bug but a compile-path
    leak, a failing cl-ppcre scan leaving `*reg-starts*` bound past the special `let` that shadowed
    it, the phantom register arriving as a second argument to a one-parameter `:simple-calls`
    replacement. That leak is closed (`.todo/192`, the every-exit restore in
    [dynamic-special-variables.md](dynamic-special-variables.md)) and the guard is green over the
    real cl-ppcre corpus on both compiled backends.
  - `WasmAsyncEmit.freshCtx` must carry `arityChkFuncIndex` (it inherits every `Ctx.Builder` field
    since 2026-10-04): it builds the SYNCHRONOUS top level too, so dropping it left a top-level
    literal `apply` unguarded while the same form inside a defun reports.
- **Naming the operator** (2026-09-26). The interpreter's Java built-ins always named themselves
  (`requireArgCount(name, ...)`); a function value that is a `BuiltinFunctionWrappers` lambda said
  `Function` -- on the compiled backends for every built-in, in the interpreter for the ones it
  resolves through `lambdaFor`. ONE rule now decides on all four:
  `BuiltinFunctionWrappers.arityOperator(name)` -- the callee's name when it is a catalog name, else
  `Function`. By NAME, because the interpreter's catalog lambda and the compilers' injected wrapper
  defun are the same function under it (a user `defun` of a catalog name is named too, on every
  backend alike). A program's own functions keep `Function`: naming them would also have to name
  the `&optional` surplus and destructuring checks the lambda-list desugaring emits without a name
  ([lambda-lists.md](lambda-lists.md)), or one function would report under two names.
  - **Interpreter**: `resolveFunction` gives the catalog lambda its name (so `#'elt` prints
    `#<function ELT>`, as it always did compiled), and `checkArity` asks `arityOperator` of it.
  - **JVM** (`JvmArityOperators`): the shape carries the operator's 1-based index from bit 16 up.
    Every site that bakes a shape registers through it -- a literal `apply`'s guard, a spread case,
    the `_arityErr` table (whose cell keeps `shape + 1` in the low seven bits and the index in the
    nine above, so an unnamed cell stays one byte) -- and `_arityMsg` reads the name back out of one
    newline-joined string constant (`split` on a one-char non-regex pattern). `buildArityMethods`
    registers every dispatchable callee before freezing the registry for `_arityMsg`, which covers
    the spread cases built after it; a late registration throws. A named spread case's shape is past
    `sipush` range and is `ldc`'d. `_arityMsg` and `_arityChk` mask the operator bits off before
    reading the required count -- `_arityChk` without the mask rejected every RIGHT-count `apply` of
    a built-in (`(apply #'cons '(1 2))`), which the wrong-count tests alone could not see.
  - **wasm-GC**: the shape carries `funcId + 1` from bit 16 up (`WasmRuntimeBuilder.arityShape`),
    and one shared function, `_arity_opening(shape, _) -> string` (TYPE_RAT_NEW, a conditional
    index right after `_arity_chk`, reserved once the defuns are final:
    `WasmLispCompiler.emitsArityOpening`), selects the operator by funcId with the dispatchers' own
    `emitCaseSelector` and concatenates one shared `" expects "`. A dispatcher whose misses include
    a named callee writes `(funcId + 1) << 16 | shape` from its arm (the page bias folded into the
    constant); `_arity_chk` and the spread cases read it off the shape they are handed. The named
    set is the catalog defuns that are DISPATCHABLE or the callee of a guarded literal `apply`
    (`Ctx.arityNamedCallees`, forwarded by `WasmAsyncEmit.freshCtx` with `namesArityOperators`):
    wasm injects every wrapper and shakes most, so naming them all kept every operator's piece in
    every module. The first cut inlined the selection into each dispatcher and cost the eval
    module below +36 KB -- one copy per dispatcher arity.
  - The EXPECTATION half followed on 2026-09-27: a built-in's function value reports by its call
    shape too ("A built-in's function VALUE with a wrong count", below).
- **A DIRECT call of a built-in** (2026-09-26). **Invariant: `(op args...)` in call position,
  `op` a `BuiltinFunctionWrappers` name, with a count the operator's call shape rules out,
  evaluates its arguments and then signals `program-error` with ONE text on all four backends**
  (`CAR expects 1 argument, got 2`, `FLOOR expects at most 2 arguments, got 3`, `GETHASH expects
  at least 2 arguments, got 1`), and each compiled backend warns at compile time
  (`warning: ...; compiled as a call-time program-error`), which `--warnings-as-errors` makes a
  failed compile ([compile-warnings.md](compile-warnings.md)). Pinned by ci-spec
  `direct-builtin-call-wrong-count-signals-program-error`, `BuiltinCallArityTest`,
  `LispEvaluatorTest.everyWrappedBuiltinReportsAWrongDirectCountWithItsCallShape` (every catalog
  name, counts 0..4) and `JvmLispCompilerTest.compileAndRunADirectBuiltinCallWithAWrongCountSignalsAtCallTime`
  with its wasm twin.
  - Before: every call-position lowering indexed the argument list by the count it expected, so
    compiled `(car x 2)` answered the car and `(nth x)` failed the COMPILE with `Index 2 out of
    bounds`; the interpreter answered `(minusp 1 2)` => NIL and `(array-rank v 2)` => 1, and said
    `Index 1 out of bounds for length 1` for `(1+)`. Measured over all 329 catalog names x counts
    0..6 on the interpreter: most reported, many said the raw index text, some answered.
  - The count is judged ONCE, where each backend decides a form is a built-in call, never per
    lowering: `compiler/BuiltinCallArity.wrongCountSignal` lowers the call to `(progn args...
    (%program-error "msg"))` -- the existing static-rejection signal, which is what gives the
    warning. JVM / wasm: first thing in `Jvm/WasmExprCompiler.compileConsLocated` for a symbol
    head, before the `rontolisp:` chain and the operator slices. Interpreter: `LispEvaluator.
    wrongCountCall`, in `builtinMacroExpansion` (memoized per site), the `error` / `cerror` /
    `warn` / `signal` / `make-instance` arms, and once in front of the rare-operator tables on the
    fall-through path. A HashMap probe per fall-through call; A/B over a call-heavy interpreted
    benchmark (fib 27 + a list walk, 8 interleaved runs each on a loaded host) put the medians
    within noise of each other (3,208 / 3,095 / 3,080 ms: before / with / without the
    fall-through probe).
  - NOT the front end (`CompileFrontend.expand`): built-in macros are still unexpanded there, so
    a walker would have to know that `(let ((nth 5)) ...)`'s binding or a `case` key list is no
    call. The backends' own dispatch already knows. A syntactic scan of the repo's Lisp sources
    (2026-09-26) found ~240 such "wrong-count" conses; the ones inspected were binding lists,
    lambda lists and clauses, and the test suite's compiles warned on none of them.
  - The SHAPE is the catalog wrapper's lambda list, which is the operator's standard one
    (keywords count as unbounded; the keyword-tail check stays the operator's). Until every
    wrapper took its standard lambda list, `BuiltinCallArity.STANDARD_WIDER` widened the narrower
    ones; its last row, `read-from-string`, went with `%read-from-string-full`
    ([read-load-streams.md](read-load-streams.md)), and the table with it.
  - A name the program defines itself (a `defun` of a cl name, a spliced library defun such as
    wait.lisp's `sleep`) keeps its own call path: `ctx.userDefunNames` on the compiled backends, a
    `LispLambda` global binding in the interpreter. The one exception is a NATIVE built-in's
    library defun ("A DIRECT call of a native built-in outside the catalog" below).
- **A DIRECT call of a program's own function** (2026-09-26). **Invariant: `(f args...)` with `f`
  a compiled defun, or `((lambda ...) args...)`, with a count the lambda list rules out evaluates
  its arguments and then signals the interpreter's `program-error` at run time on all four
  backends** (`Function expects 2 arguments, got 1`), each compiled backend warning at compile
  time (`--warnings-as-errors` fails the compile instead, [compile-warnings.md](compile-warnings.md)).
  Pinned by ci-spec `direct-defun-call-wrong-count-signals-program-error`,
  `DefinedCallArityTest` and `JvmLispCompilerTest`
  `compileAndRunADirectCallOfAProgramFunctionWithAWrongCountSignalsAtCallTime` with its wasm twin.
  - Before: both compiled backends failed the COMPILE (`UD expects 2 arguments, got 1`, `lambda
    expects 1 argument, got 2`), so a wrong call in a branch never taken, or under a
    `program-error` handler (the ANSI suite's `signals-error` rows), kept the program from
    compiling at all.
  - `compiler/DefinedCallArity.wrongCountSignal` builds the same `(progn args... (%program-error
    "msg"))` as the built-in case, from the compiled function's shape (required count, rest or
    not -- an `&optional`/`&key` tail is a rest list by then and judges its own surplus inside the
    callee). Called from `Jvm/WasmFunctionCallCompiler.compileDirectCall` and
    `Jvm/WasmLambdaCompiler.compileCall`.
  - The operator the report names is `BuiltinFunctionWrappers.arityOperator`'s, the rule the
    function-value path already used: `Function` for a program name, the built-in's name for a
    defun of a catalog name. It also maps the compile-path dispatcher `ShadowedBuiltins` renames
    a `defmethod`-shadowed built-in to (`%LENGTH--dispatch`) back to the built-in, because the
    interpreter's dispatcher keeps the name: `(length x 2)` and `(funcall #'length x 2)` both say
    `LENGTH expects 1 argument, got 2` everywhere (the function-value path said `Function` on
    both compiled backends, the direct call failed the compile). `ShadowedBuiltins` now keeps the
    rewritten call's source position, so the warning has one.
- **A built-in's function VALUE takes the operator's standard lambda list** (2026-09-26).
  `(funcall #'string-upcase s :start 1)`, `(apply #'gethash k h '(d))`, `(funcall #'typep x 'y
  env)` answer what the call position answers, on all four backends: the wrapper forwards its
  optional and keyword arguments to the call-position lowering. Pinned by ci-spec
  `builtin-function-values-take-the-standard-lambda-list`, `BuiltinCallArityTest`,
  `LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`
  `...BuiltinFunctionValuesTakeTheStandardLambdaList`. `STANDARD_WIDER` had 43 rows; 24 went
  with this, the 16 comparison and bitwise ones with the physical optionals (below), 2 with the
  helper wrappers (below), the last (`read-from-string`) with its prelude defun.
  What it took:
  - An absent keyword gets the value the lowering would have used (`make-string`'s space,
    `adjust-array`'s old fill pointer and `%array-default-element`); where presence itself picks
    the expansion, the wrapper decides at run time. `adjust-array` picks displaced vs not at run
    time, and contents vs element INSIDE one expansion through the internal keyword
    `LispMacroExpander.ADJUST_CONTENTS_P_KEYWORD`, so the element-copy loop is not carried twice.
  - Two DIRECT calls were wrong too, and are fixed with them: `(string-upcase s :start a :end b)`
    dropped the keywords unevaluated on JVM/wasm (answered `"ABC"` for `"abc" :start 1`) and was
    a count error in the interpreter -- now `LispMacroExpander.expandBoundedCaseConversion` (the
    bounded substring converted, the rest kept) and `Environment.boundedCaseConversion`; and
    `typep` / `upgraded-complex-part-type` with an environment failed the JVM/wasm COMPILE --
    now `withEnvironmentEvaluated` evaluates it and drops it.
  - `#'file-position` with a computed position reaches `_fileLength` (`:end`), so
    `filePositionMayNeedLength` counts a `(function file-position)`; without it the JVM
    self-call check refused the class.
  - `gethash` / `intern` had a second, full-width `VALUE_SHAPES` wrapper for a program that names
    them as designators; the catalog wrapper is that shape now and the map is gone (the
    second-value publishing stays designator-gated).
  - **The comparisons and the folds take their full lambda list without consing**
    (2026-09-26). The comparisons (`= < > <= >= /=`, the seven `char` ones) take `(a &optional
    b &rest r)`; `+ * gcd lcm logeqv logand logior logxor` take `(&optional (a identity) (b
    identity) &rest r)` -- a left fold, `(funcall #'+)` the identity, one argument `(op a
    identity)` so a non-number is the interpreter's type error; `min max - /` take `(n
    &optional b &rest r)`, `append nconc` `(&optional a b &rest r)`. An optional argument
    travels as a parameter ([lambda-lists.md](lambda-lists.md), "Optional arguments travel as
    parameters"), so the two-argument call -- a sort predicate's, a `reduce`'s -- conses no
    rest list, and `(funcall #'< 1)` / `(funcall #'logand)` answer `T` / `-1` as the
    interpreter does; their `STANDARD_WIDER` rows are gone. Before, the comparisons and the
    bitwise trio kept `(a b &rest r)` (the one-argument call a wrong count) and the folds
    `(&rest r)`, which consed per call: on wasmtime (49.0) that cost grows with the live heap
    -- sorting 200,000 fixnums ten times through a `(a &rest r)` predicate took 21.7 s against
    1.5 s for `(a b &rest r)`. Measured again with this change (ABBA medians, wasmtime 49):
    `(reduce #'+ data)` over a million fixnums x10 14.15 -> 0.24 s, sort through `#'<` 1.39 ->
    1.37 s, through a user `(a &optional b)` 3.99 -> 1.49 s. Each fold inlines its operator
    twice; the first cut (supplied-p arms and a `reduce` over the rest list, three copies) put
    +10.9 KB on the eval-carrying `(print (eval '(+ 1 2)))` class, this shape +2.2 KB
    (355,090 -> 357,283; Preview 1 262,266 -> 262,201).
  - **A wrapper whose body calls a prelude helper** (2026-09-27): `#'make-broadcast-stream`
    takes its components (`(&rest c)` -> `%make-broadcast-stream`, the Gray class) and
    `#'write-to-string` its keywords (`(a &rest kw)` -> `%write-to-string-keyed`, which binds the
    printer variables and rejects a bad tail with the call position's text). What each helper
    needs -- the CLOS instance gates and the Gray rewrite; the printer `defvar`s and the
    renderer -- is decided from the program's spelling before any wrapper exists, so the
    prelude splices the helper from that spelling (the designator counts) and the compile paths
    inject the full wrapper exactly where the helper is in the program, the old narrow one
    elsewhere (`BuiltinFunctionWrappers.HELPER_WRAPPERS`): the two cannot disagree. Pinned by
    ci-spec `helper-wrapped-function-values` and the `HelperWrapperFixture` trio
    (`...HelperWrappedFunctionValues`). Sizes: [gray-streams.md](gray-streams.md),
    [pretty-printer.md](pretty-printer.md).
  - `#'read-from-string` is a third helper wrapper (`(s &rest r)` -> `%read-from-string-full`),
    selected the same way ([read-load-streams.md](read-load-streams.md)).
  - Size (JVM `.class` / wasm Preview 1 bytes): `(print (eval '(+ 1 2)))` 329,075 -> 355,089 /
    255,334 -> 262,266, under `handler-case` 462,614 -> 493,358 / 387,082 -> 410,908 -- the eval
    registry carries every wrapper; of it `adjust-array` ~10 KB (its `:initial-contents` fill,
    a runtime element type, is ~8 KB -- one fill instead of eight since 2026-09-28, "`make-array
    :initial-contents` whose shape does not match"), the case conversions ~3.5 KB, the fourteen comparisons
    ~5 KB. `(print (sort (list 3 1 2) #'<))` 33,039 -> 33,256 / 23,156 -> 23,158. A program that
    takes none of them as a value is unchanged (`(print (+ 1 2))` 6,024 / 348).
- **A DIRECT call of a native built-in outside the catalog** (2026-09-26). **Invariant: a built-in
  the interpreter implements natively that has no `BuiltinFunctionWrappers` entry (`boundp`,
  `export`, `get-universal-time`, `rontolisp:tcp-connect`, `rontolisp:tls-connect`, ...) is judged
  by the same `BuiltinCallArity` check, its shape from `compiler/NativeCallShapes`, and reports
  under the interpreter's name for it** (`TCP-CONNECT expects 2 arguments, got 1`, `EXPORT expects
  at most 2 arguments, got 3`, `TLS-CONNECT expects 2 or 4 arguments, got 3`). Pinned by ci-spec
  `direct-native-builtin-call-wrong-count-signals-program-error`, `NativeCallArityCompileTest`
  (every row x every wrong count, compiled through the CLI for the JVM, wasm and the component:
  the warning's literal IS the run-time message),
  `LispEvaluatorTest.everyNativeBuiltinReportsAWrongDirectCountWithItsCallShape`,
  `JvmLispCompilerTest.compileAndRunADirectNativeBuiltinCallWithAWrongCountSignalsAtCallTime` and
  its wasm twin.
  - Measured before (2026-09-26, all 389 non-catalog cl / `rontolisp:` / native names x counts
    0..5, compiled through the CLI and run): of 83 public native names 81 diverged. Most failed the
    COMPILE (`ARRAYP expects 1 argument: (ARRAYP)`, `close expects 1 argument, got 2`, `fetch
    expects 1 or 2 arguments`); `export`/`import`/`unexport`/`use-package`/`unuse-package` with 3
    arguments answered `T`, `make-random-state` with 2 `NIL`, `rontolisp:version` with 1 the
    version plist; a prelude or library defun of one (`char-name`, `symbol-package`,
    `find-class`, `macroexpand`, the component's sockets.lisp `tcp-*`) said `Function expects ...`,
    the `&optional` surplus from inside the callee. Only `make-string-input-stream` and
    `make-synonym-stream` agreed.
  - A row is the counts the INTERPRETER's implementation takes, not the standard lambda list: a
    shape wider than the implementation would hand a lowering a count it cannot take. Keywords
    count as unbounded (`load`, `make-package`); a PAIRED row (`tls-connect`, `tls-upgrade`: 2 or
    4, the surplus one option pair; `close`: 1 or 3, `:abort v`) is the one shape a lambda list
    cannot spell. `close` was listed as 1 on 2026-09-26 on the belief that the interpreter took
    no `:abort`; it did, and so did every lowering (a LITERAL `:abort` is stripped), so
    `(close s :abort t)` was a wrong count on all four until 2026-09-27. Its keyword is the one
    a shape cannot check: `wrongCountSignal` rejects a 3-argument `close` whose second argument
    is not the literal `:abort` (`CLOSE expects 1 argument, got 3`, the implementation's own
    report); a COMPUTED keyword is rejected too, which the interpreter would take when it
    evaluates to `:abort`. Pinned by `MethodedBuiltinTailFixture.CLOSE_PROGRAM` on three
    backends. The interpreter's direct call now reports the shape's text, so a range says
    `at least` / `at most` where the implementation said `1 or 2` / `1 to 3`; so does its
    function value since 2026-09-27 (below).
  - A library defun that implements a native built-in (`BuiltinCallArity.builtinShapedDefuns`:
    a top-level defun of a native name whose lambda list takes exactly the row's counts, read
    before `LambdaLists.desugarProgram`) does not keep its own call path: `Ctx.builtinShapedDefuns`
    lets the check through `ctx.userDefunNames`, so the surplus past its `&optional` is the
    built-in's report at the call site. `BuiltinFunctionWrappers.arityOperator` names native
    rows too, so its own checks (a missing argument, the function-value path) say the built-in's
    name, not `Function`. http.lisp's and HostFetchLibrary's `rontolisp:fetch` took `(url &rest
    options)`; they take `(url &optional options)` now, the built-in's own shape.
  - Residual: a PROGRAM's own redefinition of a native built-in with the built-in's exact shape
    reports under the built-in's name on the compiled backends, where the interpreter's
    `LispLambda` says `Function` for the `&optional` surplus (the compile path cannot tell a
    library splice from the program's defun). Undefined consequences for a cl name (CLHS
    11.1.2.1.2); `rontolisp:` names are the implementation's.
  - Not listed: internal `%` operators (forms the expansions emit with a fixed shape; a program
    spelling one is outside the contract) and `rontolisp:http-handler`, a compile-time directive
    whose literal handler name the compile path requires, like `wit-export`.
  - Front-end passes that lower a native call by its shape leave a wrong count alone for the
    backend's check: `JsonLibrary`'s json-parse/json-stringify rewrite and `TlsPemInliner`
    threw at compile time. `WasmAwaitAnalysis` counts an `await` with a wrong count as no suspend
    point -- it compiles to the signal, and counting it failed the component's async state-count
    check.
  - Found on the way, not fixed here: a program whose only package operation is `delete-package`,
    `shadow`, `shadowing-import` or `unintern` fails to compile (a library splice short of
    `string<` / `%baked-packages%`), right count or wrong.
- **A built-in's function VALUE with a wrong count** (2026-09-27). **Invariant: a wrapped or
  native built-in reached through its function value -- `funcall` / `apply` of `#'op` or `'op`, a
  mapping function, a methoded built-in's dispatcher, a compiled `eval` -- with a count its
  `BuiltinCallArity` shape rules out signals `program-error` with the text its DIRECT call
  reports, on all four backends** (`(apply #'floor 7 '(2 3))`: `FLOOR expects at most 2
  arguments, got 3`). A program's own function keeps `Function`. Pinned by ci-spec
  `builtin-function-value-wrong-count-reports-the-call-shape`, the `BuiltinFunctionValueCountFixture`
  trio (`...BuiltinFunctionValueReportsAWrongCountWithItsCallShape`), the methoded lines of
  `MethodedBuiltinTailFixture` and `LispEvaluatorTest.everyBuiltinFunctionValueReportsAWrongCountWithItsCallShape`
  (every catalog and native name, every rejected count up to one past the shape).
  - Measured before on the interpreter (all 798 rejected (name, count) pairs, `(funcall 'op
    nil...)`): 187 said something else -- the Java body's own range (`1 to 2`, `2 or 3`), a
    description (`ADJUST-ARRAY expects an array and new dimensions`), `Index 0 out of bounds`
    (`(funcall 'mapcar)`), a `type-error`, a condition that was no `program-error`
    (`rontolisp:quantize`) -- and three ANSWERED: `(funcall 'constantp nil nil nil)` and
    `(funcall 'subtypep nil nil nil nil)` => `T`, `(funcall 'file-position)` => `NIL`. Compiled,
    the missing half already reported the shape (the dispatchers name the operator, above); the
    surplus past a wrapper's `&optional` said `Function expects at most N`.
  - **Interpreter**: `LispEvaluator.apply` turns whatever a `LispFunction`'s body raises into
    `BuiltinCallArity.wrongCountMessage(name, count)`'s report when the count is one the shape
    rules out (`wrongCountOr`) -- on the way OUT only. Judging before the body ran (one `HashMap`
    probe per built-in application) cost +2.6% on a built-in-heavy interpreted loop (4 ABAB pairs:
    medians 4,479 -> 4,594 ms); on the way out it is within noise (4,533 / 4,506). What that
    cannot see is a body that ANSWERS a ruled-out count: the three that did (`constantp`,
    `subtypep`, the Gray `file-position` layer) check it first (`Environment.requireCallShape`),
    and the sweep test fails on the next one.
  - **Compiled**: the wrapper's `&optional` surplus check names the operator
    ([lambda-lists.md](lambda-lists.md), "The operator is the function's name"). Sizes (JVM
    `.class` / wasm Preview 1): `(print (eval '(+ 1 2)))` 359,740 -> 359,886 / 263,492 unchanged,
    under `handler-case` 493,004 -> 493,150 / 412,519 -> 413,351 (the operator concatenated in
    front of the shared ` expects at most N arguments, got ` piece; a whole opening per operator
    was +2,046), a one-`&optional`-defun program 6,868 -> 6,919 / 1,191 unchanged,
    `(print (+ 1 2))` unchanged.
  - Not covered: `rontolisp:await` has no function value in the interpreter.
- **Every count a shape ADMITS reaches the operator** (2026-09-28). **Invariant: a direct call
  or a function-value call with a count its `BuiltinCallArity` shape accepts is never refused
  for its count -- not by the interpreter's Java body, not by a compile path's lowering, rewrite
  or scan.** Pinned by `LispEvaluatorTest.everyCountABuiltinCallShapeAdmitsReachesItsBody` (every
  catalog and native name, every admitted count up to the maximum or three past an unbounded
  minimum, nil arguments, direct and `funcall`) and `cli/BuiltinCallArityCompileTest` (the same
  calls through the CLI's front end on jvm / wasm / component, compile only; a lowering refusing
  a nil that must be a literal -- `open`'s direction, a keyword -- is dropped and the rest
  recompiled; only a COUNT refusal fails it; `tls-listen-pem` is out, it reads its certificate
  files at compile time).
  - Measured before: the interpreter refused `(peek-char nil s nil :eof nil)`; every backend
    refused `(find-symbol n p x)`, which the `&rest` wrapper's shape admitted; the compile paths
    failed the compile for `(read-char s nil :eof nil)`, `(read-line s nil :eof nil)`,
    `(subtypep a b env)` and `(list*)`, and compiled `peek-char`'s and `read-char-no-hang`'s last
    argument to a count report.
  - An argument the operator accepts and IGNORES (`recursive-p` of `read-char`,
    `read-char-no-hang`, `read-line`, `peek-char`; `environment` of `subtypep`) goes through
    `macro/IgnoredArgument`: `drop` is the call without it, the argument still evaluated in its
    place (dropped when inert, in front of the call when every other argument is, else in a
    `prog1` behind the argument before it); `withoutArgument` is the call without it for a scan
    that evaluates nothing. Its consumers are every place that judges one of these calls by its
    count: the two backends' call dispatch (after the wrong-count check), `GrayStreamsLibrary`
    and `UnreadCharLibrary`'s call-site rewrites, `WasmSocketsRewrite`, the `mayCreateInstances`
    scan, the runtime-`subtypep` scan and the `subtypep` multiple-value producer (which binds
    the environment after both specifiers). A new ignored argument is one `POSITIONS` row. Pinned
    by the `IgnoredArgumentFixture` trio (plain, `unread-char` pushback, Gray instance), which
    also pins the evaluation order.
  - A shape wider than the operator's lambda list is narrowed at its wrapper instead:
    `#'find-symbol` is `(n &optional (p nil pp))` (was `&rest`), `#'list*` is `(a &rest r)`
    (CL's `object+`).
  - Fixed on the way: `read-char-no-hang` was missing from `END_OF_FILE_SITES` and from the
    `#'` list of `constructsInstance`, so a signalling `(read-char-no-hang s)` or any
    `#'read-char-no-hang` failed the wasm compile (`no layout was baked for instance type
    %class-END-OF-FILE`) and printed `#<END-OF-FILE :STREAM NIL>` for the report on the JVM. A
    computed eof-value was not evaluated by a signalling read (`(read-char s t (f))`) nor, outside
    end of file, by `(read-line s nil (f))`; both evaluate it now.
- **Inside a compiled `eval`** (2026-09-26) the same reports hold: the runtime evaluates every
  argument form of a registered function and the spread case judges the count, `apply` is a
  catalog wrapper, an eval-built closure without a `&` marker is checked, and the operators
  `_eval` evaluates inline report too ([eval-runtime.md](eval-runtime.md), "Argument counts").
  `eval` itself has no wrapper, so its report names an operator no callee carries: the JVM
  registers it by name (`JvmArityOperators.namedShape`), and wasm gives it an id one past the
  largest named funcId that only `_arity_opening` reads (`ArityReport.unbackedOperators`;
  `names(id)` stays false for it, so no dispatcher can name a real callee by that id).
- **Sizes** (2026-09-12, minimal programs, JVM `.class` / wasm Preview 1 bytes):

  | program | JVM before | after | wasm before | after |
  |---|---|---|---|---|
  | `(print (+ 1 2))` | 3,949 | 3,949 | 489 | 489 |
  | `(print (handler-case (car 1) (error (c) :e)))` | 10,508 | 10,681 | 13,709 | 13,715 |
  | `(print (mapcar (lambda (x) (* x x)) '(1 2 3)))` | 6,935 | 7,613 | 16,446 | 16,446 |
  | a `defun` + a wrong-arity `funcall` under `handler-case` | 19,964 | 20,879 | 19,650 | 19,840 |

  A program with no indirect call is byte-identical on both backends. The JVM pays +173 B on ANY
  handler-case (the sixth classification arm) and +678 B on any program with a dispatcher
  (`_arityMsg` + `_arityErr` + the funcId table); wasm pays nothing without a landing pad, +6 B when
  its dispatchers are all shaken away, and +190 B when the arms are live.

  The `apply` half on top of that (2026-09-12, same method, the four rows above unchanged by it):

  | program | JVM before | after | wasm before | after |
  |---|---|---|---|---|
  | a `defun` + a wrong-arity LITERAL `apply` under `handler-case` | 47,513 | 48,075 | 13,528 | 13,780 |
  | the same through a function VALUE (`(let ((h #'f)) (apply h '(1 2)))`) | 47,624 | 48,185 | 32,120 | 32,851 |
  | a `defun` + a RIGHT-arity literal `apply`, no `handler-case` | 7,093 | 7,522 | 11,206 | 11,206 |
  | the real cl-ppcre exercise (`asdf:load-system`) | 767,584 | 769,410 | 713,188 | 713,188 |
  | the same exercise under `handler-case` | 774,886 | 776,712 | 719,698 | 722,198 |

  The JVM pays +429 B for `_arityMsg` + `_arityChk` on any program with a guarded site, then ~6 B
  per site; wasm pays nothing at all without a landing pad -- the cl-ppcre module is byte-identical
  there, `establishesLandingPad` being false for it -- and ~2.5 KB (+0.35%) on the same corpus with
  one. The plan this landed from feared +28 KB on wasm from 14 B in every spread case pushing a 2,000-callable
  program past `DISPATCH_PAGE_BUDGET_BYTES` ([wasm-function-body-size.md](wasm-function-body-size.md));
  the shared function made it 7 B and the measurement is an order of magnitude under that.

  Naming the operator on top of that (2026-09-26, same method, programs compiled with
  `--class-name P`; the programs catch with `(program-error (c) (princ-to-string c))`):

  | program | JVM before | after | wasm before | after |
  |---|---|---|---|---|
  | `(print (+ 1 2))` | 6,024 | 6,024 | 348 | 348 |
  | `(print (handler-case (car 1) (error (c) :e)))` | 14,502 | 14,513 | 5,203 | 5,203 |
  | `(print (mapcar (lambda (x) (* x x)) '(1 2 3)))` | 11,623 | 11,775 | 6,175 | 6,175 |
  | a `defun` + a wrong-arity `funcall` of it under `handler-case` | 38,875 | 39,014 | 22,623 | 22,823 |
  | a wrong-arity `funcall` of `#'cons` under `handler-case` | 32,813 | 32,958 | 21,310 | 21,510 |
  | the same as a LITERAL `(apply #'cons '(1))` | 31,983 | 32,125 | 19,404 | 19,552 |
  | the same through a VALUE (`(let ((h #'cons)) (apply h '(1 2 3)))`) | 43,768 | 43,938 | 29,758 | 29,975 |
  | `(eval '(funcall ...))` of it -- eval makes EVERY wrapper dispatchable | 443,796 | 448,858 | 366,576 | 375,084 |

  Even a program that names no built-in itself has some dispatchable (the runtime's own
  `#'identity` / `#'eql` defaults), so a module with a report pays ~150 B on the JVM (the exception
  class constant, the names and the decode) and ~200 B on wasm (`_arity_opening` plus a handful of
  names). The eval row is the price of naming ~250 operators: their names (JVM one joined string,
  wasm one piece each) and one selector case apiece, +1.1% / +2.3%.

## Applying a value that names no function
**Invariant: applying a non-designator (`(funcall 3 1)`, a Scheme `(h 1)` over a number) signals a
catchable `type-error` reporting `Not a function: <prin1>`
(`ClosRegistry.NOT_A_FUNCTION_MESSAGE_PREFIX`), and a SYMBOL no function answers -- NIL included,
which the interpreter used to call `Not a function: NIL` -- an `undefined-function` reporting
`The function NAME is undefined`, on all four backends, through `funcall`, `apply`, `mapcar` and
every other dispatcher route.** Before (2026-09-17): the JVM leaked a `ClassCastException` (an NPE
for nil) caught only as the generic type-error text, uncaught as a Java class-cast line; wasm-GC
trapped on the closure cast straight past `handler-case`; both compiled `_apply`s answered NIL for
anything they could not call. Pinned by ci-spec `applying-a-non-function-signals-its-condition` +
standalone `uncaught-non-function-report`, `LispEvaluatorTest`
`applyingANonFunctionSignalsATypeErrorAndNilAnUndefinedFunction`, `JvmLispCompilerTest`
`compileAndRunApplyingANonFunctionSignalsTheInterpretersCondition`, `WasmLispCompilerIntegrationTest`
`ehApplyingANonFunctionSignalsTheInterpretersCondition`.

- **Detected where each backend already dispatches on the callee's representation, never in front
  of a call.** Interpreter: `LispEvaluator.apply`'s fall-through. JVM: segment 0 of every
  `_invoke_N` / `_invoke_v` dispatcher replaces `checkcast Object[]` + `checkcast Integer` with the
  `instanceof` twins (the JIT folds them into the casts that follow) and sends a miss, a
  string-designator lookup miss and `_apply`'s three silent arms to one helper,
  `_notFn(Object) -> RuntimeException` (`JvmRuntimeBuilder.buildNotFnBody`: null -> NIL, an unframed
  `String` -> symbol, else `_lispToString`). The pad recovers `type-error` from the prefix (the
  unbound-variable precedent). wasm-GC: `emitDispatchPrologue` in EH mode tests the CLOSURE first
  and `br_if`s straight to the cast, so a function value pays the same two type checks and one
  branch the symbol-first order did; the failure arms sit AFTER the dispatch (`emitDispatchEpilogue`),
  not between prologue and `br_table`. `_apply` hands every value it cannot call to the spread
  dispatcher, whose prologue reports it. A quote-framed string shares `TYPE_STRING` with a symbol, so
  the undefined arm re-tests the first byte and falls through to the not-a-function arm; a keyword
  keeps its colon (prin1), any other symbol prints as princ -- the interpreter's `symbol.name()`.
- **Class on wasm rides on a layout the module already has**: a program whose source names
  `type-error` / `undefined-function` has it baked (`usedLayoutTags`) and gets the typed instance
  (`WasmRuntimeBuilder.NotFunctionReport`, `conditionInstance`); one that does not cannot tell the
  instance from the message-only `(nil . message)` payload, so nothing new is baked. This is why the
  designator path's undefined-function is TYPED on wasm while a direct call's stub is not.
- **Outside EH mode wasm is byte-identical** and still traps (unreachable / cast failure) -- the
  uncaught-report rule above. `--no-gc` unaffected.
- **One text in every mode**: the Scheme REPL used to reword the failure (`#f is not a procedure;
  operands: (2 3)`, carried by a LispApplyException, removed) while file mode and the compiled backends
  could not. The REPL prints the condition's text now (`.kb/scheme-frontend.md`).
- **Scheme never reaches this path for its own values** (`.todo/851`, 2026-09-18): a
  lowered Scheme combination whose operator is not a known procedure goes through
  `rontolisp::%scheme-ensure-procedure` (a `functionp` check reporting
  `The object is not applicable: <scheme-print>` through `%scheme-error-message`,
  the same text `%scheme-eval-apply` reports), so `#f`, the unspecified object, a
  number and any symbol report as Scheme values before any backend dispatches on
  them. The backends carry the message; none learns a Scheme name. A Scheme
  `(h 1)` over a number therefore reports `The object is not applicable: 3`, not
  the `Not a function: 3` a CL `(funcall 3 1)` reports -- same backends, different
  front ends, each in its own terms.
- Cost (2026-09-17, a 20M-iteration `funcall` loop, 12-16 alternating runs, load 6-16): JVM
  monomorphic 23-38 -> 0-1 ms (the loop now folds entirely), polymorphic 109-114 vs 99-131 ms; wasm
  P1 EH mode mono 259 vs 262 ms, poly 766 vs 783; component poly 753 vs 804 against an A/A run of
  the SAME binary at 810 vs 850 -- inside the noise. A first cut that tested the closure AFTER the
  symbol test (three checks) measured +13% mono and was dropped. Sizes (JVM `.class` / wasm P1):

  | program | JVM before | after | wasm before | after |
  |---|---|---|---|---|
  | `(print (+ 1 2))` | 3,948 | 3,948 | 348 | 348 |
  | `(print (handler-case (car 1) (error (c) :e)))` | 11,444 | 11,483 | 952 | 952 |
  | `(print (mapcar (lambda (x) (* x x)) '(1 2 3)))` | 8,292 | 8,601 | 6,022 | 6,022 |
  | a `defun` + a `funcall` through a value under `handler-case` | 52,954 | 53,270 | 10,916 | 11,077 |
  | the same with `type-error` / `undefined-function` clauses | 52,394 | 52,710 | 10,743 | 10,954 |

## Out of scope (still)
The interactive debugger (`break`, `*debugger-hook*`, rendering a restart's `:report` or running its
`:interactive` function), condition-restart association, a `store-value` restart for
`check-type`/`assert`/`ccase`/`ctypecase` (`expandCcase` -> `expandEcase`, `expandCtypecase` ->
`expandEtypecase`), `--no-gc` catching (the GC path's `$lisp-cond` tag has no MVP equivalent, which
is why `--no-gc` rejects the forms outright rather than degrading). Postmodern is the real restart customer
(`prepare.lisp:54-66`, `transaction.lisp:63-70`); verbatim cl-postgres needs no restart system at
all, so **`restart-case` alone unblocks nothing real**.

## Tests
- Behavior in `LispEvaluatorTest` / `JvmLispCompilerTest` (`compileAndRun*` twins) / the `eh*` block
  of `WasmLispCompilerIntegrationTest`; `--no-gc` compile-error pins in `NoGcWasmCompilerTest`. Named
  groups: `conditionReport*`, `simpleConditionFamily*`, `warnRenders*`, `aRuntimeControlStringDatum*`,
  `aPrintObjectMethodStillWins*`, `aConditionWithNoReport*`,
  `readCharEndOfFileIsCatchableAsEndOfFile`, `noApplicableMethodIsCatchableAndReportsTheSameText`,
  `handlerBindSees*`, `handlerBindRunsEachClusterOnceForABuiltInErrorInnermostFirst`,
  `arefOutOfBoundsAndNegativeMakeArrayAreCatchable`, `outOfRangeSubscriptsAreTypeErrorsNamingTheirBound`
  (+2), `aTypedLoopReportsAnOutOfRangeSubscriptAsTheBoxedPathDoes`,
  `argumentShapeErrorsSignalACatchableProgramError` (+2, the compiled twins being
  `compileAndRunArgumentShapeErrorsSignalACatchableProgramError` and
  `ehArgumentShapeErrorsSignalACatchableProgramError`), `allowOtherKeysSuppressesTheKeywordCheck`,
  `compileAndRunAnUncaughtArgumentShapeErrorReportsTheSameLine`,
  `ehAnUncaughtArgumentShapeErrorReportsTheInterpreterLineBeforeTrapping`,
  `anInnerHandlerCaseShadowsAnEnclosingHandlerBind` (+3),
  `handlerBindHandlersRunOnceWhileACleanupSignals` (+2),
  `signalFallsThroughAHandlerCaseWhoseClausesDoNotMatch` (+3),
  `nonNumberArithmeticOperandsSignalCatchableTypeErrors`,
  `argumentTypeErrorsNameTheOperatorBeyondArithmetic` (+2), the restart block (15-16 cases each),
  `compileRuntimeErrorDispatchScalesPastTheBranchLimit`, `anUncaughtCondition*`, `ehUncaught*`, and
  the `compileAndRunHandlerCaseIn*` block -- which must COMPILE, LOAD and RUN the class, since the
  broken class was written without complaint and only failed at link time.
- Location lines: `RontoLispCliStreamsTest`'s `anUncaught*`/`anAsyncBodys*` (interpreter, `java -cp`,
  `java -jar`), `cli/UncaughtReportParityTest` (both backends, every shape above: tail calls,
  `labels`, macros, a loaded file, library callbacks, methods, nested defuns, async chains,
  fused trees, typed loops, continuations, nothing located), `am.ik.jvm.LineNumberTableTest` (the
  attribute through the relaxer, shaker, splitter and frame pass).
- Gates in `LispMacroExpanderTest`: `conditionNarrowing*`,
  `anExplicitFormatControlInitargForcesTheRenderer`, `aComputedDatumMakesTheConditionSetUnknowable`,
  `aDirectiveFreeLiteralFormatControlStillDeclinesTheRenderer`,
  `aHandlerCaseThatNeverNamesItsConditionDoesNotRouteReports`,
  `ignoreErrorsRoutesReportsOnlyWhereASecondValueCanBeRead`,
  `a{ThrowOnlyConstructionDoesNot,HeldConditionStill}RouteReportsWhereMessagesAreLazy`,
  `aNonOperatorRestartNameDoesNotFlipRestartMode`,
  `anOperatorPositionRestartFormStillFlipsRestartMode`,
  `needsSignalClauseMatchRequiresBothASignalAndACatchingForm`,
  `keywordTailProblemHonoursAllowOtherKeys`,
  `conditionNarrowingMarksProgramErrorConstructibleOnlyBehindALandingPad`,
  `establishesLandingPadReadsOperatorPositionOnly`; plus
  `WasmLispCompilerTest.typedErrorWithLambdaReportCompilesOutsideEhMode`,
  `CodeReplayTest.aTypedCatchAndACatchAnyBothLand`,
  `WasmTreeShakerTest.shakesEhModeModules`, `RontoLispCliTest`, `JvmFloatArrayTest`.
- ci-spec: `condition-objects`, `condition-types`, `condition-report-printing`,
  `signal-runtime-control-string`, `handler-case-catches-typed-and-plain-errors` (+2),
  `handler-case-in-argument-position`, `restart-system`,
  `handlers-run-once-while-a-cleanup-signals`,
  `signal-declines-an-unmatched-handler-case`, `no-applicable-method-report`,
  `non-number-arithmetic-operands-are-catchable`,
  `argument-type-errors-name-the-operator-beyond-arithmetic`,
  `argument-shape-errors-signal-program-error`,
  `out-of-range-subscripts-are-type-errors-naming-their-bound`,
  `applying-a-non-function-signals-its-condition`,
  `runtime-type-dispatch-residue`,
  `runtime-type-dispatch-and-symbol-designators`, `postmodern-language-incidentals`, plus the
  `standalone:` list. Their presence puts the concatenated program in EH mode, so
  `CiSpecE2eTest.runBackend` passes `-W exceptions=y` to both wasmtime invocations.
  `ParseNumberE2eTest` and `JzonE2eTest` cover the report text and the runtime dispatch.
