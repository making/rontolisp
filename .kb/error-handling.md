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
  `IndexOutOfBoundsException` (`program-error`) and cast / arithmetic / negative-size failure into
  one first, and only an `UnsupportedOperationException` (a limitation) left raw ("Argument-shape
  errors" below) -- JVM any `RuntimeException`, wasm-GC only `$lisp-cond` throws -- raw traps there
  (failed ref.cast, `unreachable`) are uncatchable and skip unwind-protect cleanups.

## Phase 1 -- unwind-protect
`LispEvaluator.evalUnwindProtect`, `JvmUnwindProtectCompiler` (method exception table),
`WasmUnwindProtectCompiler` (`try_table (catch_all_ref $u)`, landing = cleanups then `throw_ref`).
A cleanup that signals replaces the pending unwind (CL: newer exit wins).

- JVM handler frames are computed as the class is written ([stack-map-frames.md](stack-map-frames.md));
  a handler's catch type counts toward the method's pool entries when the class is split.
- **The `return` channel is a plain GOTO/br and would skip the cleanups**, so `Ctx.unwindScopes`
  records active scopes. JVM: `JvmReturnCompiler` inlines every ESCAPED scope's cleanups before its
  GOTO, and those inlined ranges are `holes` the scope's own exception entries exclude -- a throw
  from an inlined cleanup must not re-enter its own handler but must still reach OUTER ones. WASM:
  `WasmReturnCompiler` branches to a trampoline block emitted lexically OUTSIDE the try_table (the
  structural equivalent of `holes`), cascading innermost-first.
- **A cleanup inlined at an exit is compiled with its own scope, and the ones inside it, OFF
  the scope stack** (`JvmUnwindProtectCompiler.compileEscapedCleanups`, `WasmTagbodyCompiler` for
  `go`): otherwise an exit the cleanup itself takes inlines the cleanup again without end
  (`(block b (unwind-protect (return-from b 1) (return-from b 2)))` overflowed the JVM compile).
- **A named `return-from` takes a trampoline too, on wasm**: inlining the cleanups at the exit puts
  them inside every try_table between exit and `unwind-protect`, so a signalling cleanup ran twice
  or was taken by an inner `handler-case`. `WasmReturnFromCompiler.holdsNamedExit` (syntactic)
  opens `block $ntramp`; the exit stores its target's depth in the scope's selector local
  (`UnwindScope.namedTargets`, `WasmReturnFromCompiler.exit`). Cleanups that throw nothing are still
  inlined; `go` keeps the inlining. Pinned by `ci-spec.yaml`
  `a-cleanup-a-return-from-runs-is-outside-every-handler-it-left` and `java-spec.yaml`
  `try-with-resources-finally-exits-and-causes`.
- **A cleanup's VALUES are discarded, its value COUNT included**: save/restore of the `%mv-spill`
  channel in each backend's SHARED cleanup emitter, so `return`/`go`-inlined copies get it too
  ([multiple-values.md](multiple-values.md)).
- **with-\* retrofit**: `expandWithOpenFile` / `expandWithOutputToString` /
  `expandWithInputFromString` / the usocket `with-*` take `boolean unwindProtect`; WASM call sites
  pass `false`, so interpreter/JVM close on EVERY exit, WASM on normal exit only.
- **A special `let` IS a region of this machinery** (`%dyn-restore` cleanups), so the restore rides
  every channel above and bindings and cleanups unwind innermost-first
  ([dynamic-special-variables.md](dynamic-special-variables.md)). `internalOnly` exempts those
  cleanups from the `%mv-spill` save like `%hc-depth-dec`.

## Phase 2 -- condition objects
A condition is a CLOS-subset instance ([instance-syntax.md](instance-syntax.md)), printed
`#<NAME :SLOT value ...>`, NOT a list.

- `ClosRegistry.CONDITION_SEEDS` is ONE static list with two consumers that must keep READING it,
  never a copy: the constructor, and `PackageRegistry.CL_CONDITION_TYPES` =
  `ClosRegistry.CONDITION_CLASS_NAMES` ([packages.md](packages.md)).
- Eight classes carry `format-control`/`format-arguments` beyond CLHS's slot lists -- `type-error`,
  `arithmetic-error`, `program-error`, `parse-error` (so `reader-error`), `package-error`,
  `file-error` and the two `cell-error` leaves -- because that pair is how a BUILT-IN error carries
  its message. A report-less user subclass prints it: `#<MY-PE :FORMAT-CONTROL NIL
  :FORMAT-ARGUMENTS NIL :POS 1>`. Second ancestors on seeds (lite multiple parents): `reader-error`
  -> `stream-error` (CLHS 9.1.2), `simple-type-error` / `simple-error` / `simple-warning` ->
  `simple-condition` (so a `simple-condition` clause catches a `check-type` failure).
- **Trap**: a seeded class's hand-built factory instance (`newEndOfFileCondition`,
  `newReaderErrorCondition`) must mirror its seed's slot order exactly; the report partition groups
  by slot position, so a drift reads the wrong slots silently instead of failing.
- `define-condition` = `defineConditionToDefclass` -> `expandDefclass` (top-level-only on the
  compile path); `:report` is a string, lambda, or symbol (funcalled); `:documentation` dropped.
  **Lite multiple parents**: the FIRST parent provides the slot layout, the rest join the ancestor
  set only (`registerExtraAncestors`).
- `error`/`warn`/`signal` share `expandSignalDesignator`: string designator -> legacy
  `(%error message)`; quoted type -> `buildTypedConstruct` + `(%error-cond instance message)`;
  object -> runtime dispatch. A literal `(make-condition 'type ...)` argument takes the typed path.
- **A datum that is a STRING at run time is a format control and the arguments after it are its
  format arguments** (`expandObjectSignal`'s string arm). **The rendering is EAGER**: the instance
  carries the rendered text as its TEXT CONTROL in `format-control` and nil `format-arguments`.
  **Re-evaluate if** the renderer becomes free.
- **Invariant: rendered TEXT enters a `format-control` slot only as its text control** -- every `~`
  doubled -- so the report, which renders the slot as a control, prints it exactly once. Producers:
  `LispMacroExpander.textControlForm` / `%text-control`, `reportingConditionForm`,
  `lowerFileError`, the JVM/wasm pads' `simple-error` synthesis, `ClosRegistry.textControl`, the
  wasm-GC `emitConditionThrow` through `_tilde` (`FUNC_TILDE`). An `(error <literal>)` built from a
  program-chosen name goes through `textDatum`. Consequence: `(simple-condition-format-control e)`
  of `(error "a~~b")` is `"a~~b"` and `(apply #'format nil control args)` reproduces every report.
  Pinned by ci-spec `condition-report-prints-rendered-text-once` and the three
  `aConditionCarryingRenderedTextReportsItOnce` tests. Cost: +417 B on a wasm-GC `handler-case`
  program, +229 B on its JVM class.
- The lite `#'error`/`#'warn`/`#'signal`/`#'cerror` WRAPPERS forward the datum only
  (`BuiltinFunctionWrappers.SIGNAL_FUNCTIONS`), so `(apply #'error c '(1 2))` drops the arguments on
  the compiled backends -- documented lite semantics.
- Channels: interpreter `LispEvalException.condition()`; JVM `RuntimeException(message)` + record
  under it (`JvmLispCompiler.ConditionChannel`, emitted only when used; "The JVM keeps what a
  throwable carries under the throwable" below); WASM `%error-cond` traps like `%error` outside EH
  mode.
- **The JVM message local is shared per method but NOT past its scope** (`Ctx.errorMessageSlot`):
  a handler-case in the SAME method resumes after the throw, so a live variable reusing the slot
  read the message instead of its value. Pinned by
  `JvmLispCompilerTest#anErrorCaughtInTheSameMethodLeavesTheSpilledArgumentsAlone`.
- `makeTypeTest` has a class branch (descendant-tag membership). `with-slots` is read-only;
  assignment does NOT write back.

## Phase 3 -- handler-case / ignore-errors
**A clause variable a slot cannot serve goes through a `let`** (`compiler/HandlerCaseClauses.letBound`):
a closure in the body capturing it (log4cl's `log-error` in a clause), a special. Pinned by ci-spec
`a-closure-over-a-handler-case-clause-variable`.

Clause types are `makeHandlerTypeTest` = `makeTypeTest` + an exact-tag fallback for unknown names. A
condition-less throw is caught as a synthesized `simple-error`. No match -> rethrow (the JVM records
the condition under the throwable again first). `:no-error` runs OUTSIDE the handler.

- **Interpreter** `evalHandlerCase`: `handlerCaseTypes` holds every active handler-case's clause
  TYPE SPECIFIERS, and `%signal-cond` raises only when some clause MATCHES
  (`anyHandlerCaseMatches`). The built-in seam in `LispEvaluator.apply` wraps escaping Java
  failures into `LispEvalException`, so `aref` out of range and `(make-array -1)` are catchable.
- **JVM** `JvmHandlerCaseCompiler` and **WASM** `WasmHandlerCaseCompiler` share the layout: the
  landing synthesizes the simple-error and compiles clause tests and bodies as ORDINARY Lisp over a
  `__hc_cond$<slot>` pseudo-local. Depth: `_hcDepthTl` (JVM), a depth global (wasm); a `return`
  inside decrements it through the internal `%hc-depth-dec` cleanup.
- **The JVM operand-stack spill (why handler-case works in an ARGUMENT position).** The handler
  MERGES back into the normal path and the JVM discards the operand stack on handler entry, so
  `Ctx.spillOperandStack()` saves live operands into locals before the region and `Spill.restore`
  reloads them; a statement-position handler-case spills nothing. Liveness comes from
  `am.ik.jvm.OperandStack`, which also supplies `max_stack` and raises on a merge-point mismatch.
  **An object under construction (`new`, pre-`<init>`) can never be spilled** (`Slot.UNINIT`). A
  `return` escaping a spilled region reloads from the outermost escaped `SpillScope`
  (`JvmReturnCompiler.emitStackUnwind`).
- `FreeVarAnalyzer` knows `handler-case` (clause var BOUND in the clause body), `ignore-errors` and
  `with-slots`.

## The JVM keeps what a throwable carries under the throwable
**Invariant: a compiled landing pad reads only the record of the throwable it caught.** A
`RuntimeException` has nowhere to carry an object (`RontoSignal`, "A signal renders nothing",
carries only what it renders from), so the condition (`_condTl`) and a wrong-type operand's datum
and type (`_teTl`, "A non-number reaching arithmetic") live per thread in a `java.util.WeakHashMap`
keyed by the throwable (`JvmThrowableRecords`). One slot per thread was wrong: a condition handled
inside a cleanup while another was on its way out read or took the outer one.

- **Writers.** Nothing is recorded before the exception exists: `%error-cond` / `%signal-cond`
  evaluate condition, then message, then `throw _condPut(new RuntimeException(message), condition)`.
  `_await` / `_thread_join` re-record the payload's condition (and the `_teTl` record, carried LAST
  in the error payload) on the awaiting thread. Pinned by `AsyncStreamOperandFixture`'s `car` row,
  `JvmThreadTest#aBuiltInsTypeErrorStaysOneAcrossTheJoin`.
- **A condition's message is rendered, not cast** (`JvmErrorCompiler`, `_lispToDisplayString`): a
  nil `:format-control` reports `NIL`. Pinned by
  `JvmLispCompilerTest#compileAndRunErrorWithComputedConditionType`.
- **Readers TAKE** (`_condTake`): a `handler-case` landing, the `_hbGuard` pad, async `run()`,
  thread `call()`, `_jsig`. What passes the throwable on records it again (`_condPut`, `_condRan`,
  `_jfail`). **Why take, not read**: C2 throws one preallocated exception per class from a hot site
  (`OmitStackTraceInFastThrow`), so a record left after a flight describes the next failure (a
  plain read ran a `handler-bind` handler ~5,300 times in 300,000 hot `char-code` failures). Graal
  allocates every exception and hides this, hence the child JVM on C2 in `JvmThrowableRecordsTest`.
  A `_teTl` record is made for a fresh exception only and is never taken.
- **Weak keys**: no record may hold its throwable (a value reaching its weak key never dies).
- Byte-identical without the channel (`ConditionChannel.used`) and, for `_teTl`, without a landing
  pad (`examples/jvm/java-interop.lisp`, `examples/jvm/life-gui.lisp`,
  `examples/console/calc.lisp`). Exception-using programs pay a few bytes per site; the
  throw-heavy `examples/net/hello-clack.lisp` shrinks.
- `_condRan`: "Whether the handlers ran rides the flight" below.
- A `java:` member's throwable is recorded in one weak map for the class (`_jexMap`), read by
  `_hcSynth` ([java-interop.md](java-interop.md), "What a member throws").
- Pins: ci-spec `condition-on-its-way-out-keeps-its-record`,
  `JvmLispCompilerTest#aConditionOnItsWayOutKeepsItsRecord*`,
  `#aConditionWhoseMessageIsNoStringIsStillSignalled`,
  `JvmAsyncCompilerTest#anAwaitHandledInACleanupLeavesTheConditionOnItsWayOut`,
  `JvmThreadTest#aJoinHandledInACleanupLeavesTheConditionOnItsWayOut`, the swallowed-plain-failure
  row of `testsupport/JavaImplementationPrograms.CALLBACK_SIGNALS`, `JvmThrowableRecordsTest`.

## WASM EH-mode specifics
- **The gate** (`WasmLispCompiler.compile`): the program (post pre-passes, libraries spliced) is
  scanned for `handler-case`/`ignore-errors`/`unwind-protect`. Only then: the tag section with ONE
  tag `$lisp-cond` (type reuses `TYPE_PRINT_VAL`), the handler-depth global (appended AFTER the user
  globals), the throw path and the entry wrappers. Byte-identity without the forms holds across P1 /
  component / http-client / sockets / serve / --optimize / --dynamic / --no-wasi / --no-gc.
- **Throw path** (`WasmErrorCompiler`): in EH mode `%error`/`%error-cond` throw the payload cons
  `(condition-instance . message-string)`; outside EH mode a bare `unreachable` evaluating nothing.
- **Top-level trap shape** (`WasmEmitHelper.emitCatchAllPrologue/Epilogue`): every export wrapper
  (incl. serve's `%http-dispatch`) runs inside `try_table (catch_all)` landing on `unreachable`;
  the ENTRY function and, where it reports, a program's export use the reporting variant.
- **Walkers**: `WasmSections.scanInstr` (shared by `WasmImportInjector`) knows `throw`, `throw_ref`,
  `try_table` and `exnref`; tags are their own index space. Component path: core-module-internal
  only. V8: exnref default-on in Chrome 137+ / Node 24+; Node 22 needs `--experimental-wasm-exnref`.
- **usocket typed conditions**: `(usocket::%usock-guard form)` expands per backend
  (`expandUsocketGuard`): interpreter/JVM = `handler-case` + `usocket::%usock-resignal` (always
  `usocket:socket-error`), WASM = pass-through. The shim source is parsed ONCE for all backends, so
  the branch cannot be a reader feature.

## The read family signals a TYPED end-of-file
`read-char` / `read-byte`, and `read-line` with an explicit non-nil `eof-error-p`, signal the SEEDED
`end-of-file` class on all four backends (`END_OF_FILE_MESSAGE`).

- Interpreter: `ClosRegistry.newEndOfFileCondition()` is a static factory -- sound because the class
  is SEEDED and `handler-case` dispatches on the instance TAG, not layout identity.
- Compiled: one shared CALL-SITE lowering, `LispMacroExpander.expandReadEofSignal`: call with
  `(nil nil)` eof parameters and test the nil result. Sound for exactly these three operators
  because a successful read never answers nil; runtime helpers keep their throw as a BACKSTOP.
- **Two gates easy to miss**: `mayCreateInstances` scans the SOURCE program, so the read family is
  in `constructsInstance`; `#'read-char`/`#'peek-char`/`#'read-byte` are in
  `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`.
- **The `--component` socket rewrite needs the same lowering under its ALIAS** (`WasmSocketsRewrite`
  maps to `%read-char-raw` etc.): `WasmExprCompiler` lowers `%read-char-raw`/`%read-byte-raw`/
  `%read-line-raw` too, or a sockets component traps uncatchably at EOF.
- **`read` is deliberately NOT in this family**, nor default `read-line`: both answer nil at end of
  input and `read`'s datum may legitimately BE nil.
- One-argument `read-from-string` lowers over a parse RECORD instead, since its datum may be nil
  (`expandReadFromStringFailure`, [read-load-streams.md](read-load-streams.md)).

## `parse-integer` signals a `parse-error`
A string that is no integer syntax without `:junk-allowed` is a `parse-error` reporting
`parse-integer: junk in string "12a"` / `parse-integer: no integer in string ""` on all four
backends. SBCL's class is `sb-int:simple-parse-error`; here it is `parse-error` itself (which carries
the message pair), so `type-of` answers `PARSE-ERROR`, and the text keeps the operator prefix SBCL's
lacks.

- Compiled: `lowerParseError` -- behind a landing pad a typed `(error 'parse-error ...)` signal, so
  in restart mode the handlers run at the signal point with the call's restarts still established
  (`%program-error`'s bare `%error-cond` would lose them); without a pad the plain `%error`.
- The instance is built after the scans, so `PARSE_ERROR_SITES` stands in for the tag in
  `conditionNarrowing` and `usedLayoutTags` (as `FILE_ERROR_SITES`).
- ANSI `numbers`: +13 (`PARSE-INTEGER.ERROR.4`-`.15`, `.5A`). Pinned by `ParseIntegerSyntaxFixture`
  in the three backend suites and ci-spec `parse-integer-signals-a-parse-error`.

## A condition's `:report` is what PRINTS it
**Invariant: the text a condition REPORTS has exactly one implementation, `%condition-report-str`,
and both the printer and the signal message go through it.** `princ`/`~A` answer the report;
`prin1`/`~S` keep `#<TYPE :SLOT value ...>` -- CLHS's escape-mode split, matching SBCL.

- **It rides the `print-object` seam** ([clos.md](clos.md)): `expandPrintObjectHook` fires when the
  program defines a `print-object` method **or** can build a condition. A `print-object` method on
  a condition class still wins in BOTH modes.
- **`%condition-report-str` answers nil when the class reports nothing** and every caller supplies
  its own fallback, so an under-approximating gate degrades to pre-report text instead of failing.
- **A class whose built-in signal carries a text control reports with none too**
  (`ClosRegistry.controlFreeReport`): `(error 'undefined-function :name 'f)` prints what `(f)`
  prints; likewise `unbound-variable`, `type-error`, `division-by-zero` and subclasses. A control
  the program gave wins. Only for a class a site names (`ConditionNarrowing.programTags`), so a
  program catching `(/ 1 0)` does not pay. `arithmetic-error`, `file-error`, `package-error`,
  `program-error`, `parse-error` keep `#<...>`. Wording is this implementation's. ci-spec
  `a-standard-condition-a-program-signals-reports-with-no-control`.
- **`arithmetic-error` carries `operation` and `operands`** (behind the message pair, so built-in
  positional `%obj-new` is unchanged). A failure the arithmetic signals leaves them nil where SBCL
  answers `(/ (1 0))`: every division path would have to carry its operands.
- **The class partition** (`conditionReportGroups`): own `:report`, else nearest along the
  SLOT-LAYOUT parent chain, else CLHS's `simple-condition` report. Groups are keyed by report owner /
  slot-index pair, NOT one clause per class: cl-postgres registers 100+ condition classes
  ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- **`%format-condition` renders through `%fmt-render`** ([format.md](format.md)). A FUNCTION control
  is called through FIXED-ARITY `funcall`s for 0-3 arguments: `apply` would drag the whole wasm eval
  runtime into every program that prints a condition.
- **The gate is `mayCreateConditions(program, registry)`** (the condition half of
  `mayCreateInstances`), answered TWICE in `expandTopLevelDefinitions` -- source program and expanded
  program -- and recorded in `ClosRegistry.routesConditionReports()`.
- **Trap: the generated defuns must not contain the SYMBOL `with-output-to-string`.** The wasm-GC EH
  gate (`programUsesEhForm`) scans after they are spliced in and would force EH mode;
  `renderedToString` pre-expands it.
- The interpreter loads the same generated AST (`ensureConditionReportRuntimeLoaded`) and re-loads
  when the registry changed.
- **Lite**: the rewrite is per CALL FORM, so a condition reached through a FUNCTION VALUE gets the
  raw conversion, as a `print-object` method does. `(error "~a" "~a")` prints `~a`.

## The condition floor is narrowed to what the program can construct
The compile path shrinks the condition runtime; the interpreter never narrows, and every narrowing
is IMPOSSIBILITY-based.

- **`conditionNarrowing`** scans the expanded program for the constructible `%class-` tag set plus
  whether any site can hand `%format-condition` an UNRENDERED control. BAILS to `none()` on a
  computed datum, `eval`/`symbol-function`/`fdefinition`, an escaping `#'error`-family value or
  quoted designator in data, `--dynamic`. Name forgery from computed strings can reach a pruned arm
  -- the failure is fallback report text, never a lost signal.
- **Restart mode narrows like any other program**: what it adds is seen by the scan or always in
  the set. `(car 5)` under a printing handler-bind, wasm-GC: 113,391 -> 26,365 B (JVM 106,911 ->
  38,728 B).
- **`%format-condition` declines the renderer** when every possible control has no directive but
  `~~` (a literal, nil, or `(%text-control x)`) with nil arguments -- the common case, since every
  string-datum signal site and `check-type` ([declarations-type-checks.md](declarations-type-checks.md))
  pre-render (`formatMessagePieces`). The declined arm `(%control-text control)` undoubles `~~`, so
  both print the same text. Only an explicit `:format-control` with another directive forces the
  renderer back.
- **A tag built by a LATER lowering is taken from its site's presence**: `end-of-file`
  (`expandReadEofSignal`) and `file-error` (`lowerFileError`) are constructed after both scans, so
  `conditionNarrowing` and `usedLayoutTags` read `LispMacroExpander.END_OF_FILE_SITES` /
  `FILE_ERROR_SITES` (missing it printed `#<END-OF-FILE :STREAM NIL>` for a caught one). Same for
  "`warn` of what is no warning", below.
- **`WasmInstanceLayouts.emit` takes a used-tag set** (`usedLayoutTags`): a layout ships only when its
  tag or bare name occurs in the final program (plus the synthesized simple-* three); null (= bake
  all) under `--dynamic`, an embedded eval runtime, subclass enumeration,
  `find-class`/`change-class`/`allocate-instance`/`symbol-function`/`fdefinition`. The JVM interns
  per referenced tag (`LayoutPool`).
- **`runtimeSignalSites` skips handler clause heads**: `(handler-case b (error (e) ...))` otherwise
  parses as `(error <computed> ...)` and bakes the per-class construction runtime into every
  handler-case artifact (89,138 -> 23,341 B).

## The routing gate asks whether a condition can be NAMED
`mayCreateConditions` cannot prove the body will not signal -- the handler prologue synthesizes a
`simple-error` for any caught RAW trap. What decides is whether program code can ever HOLD it.

- **`handler-case` counts only when some clause binds a variable its own body mentions**
  (`handlerCaseBindsCondition`; the occurrence test is deliberately blunt).
- **`handler-bind` counts unless every handler is a literal `lambda` whose body never mentions its
  first required parameter** (`handlerBindExposesCondition`). `(car 5)` probe on wasm-GC: 16,614 B
  ignoring, 26,365 B printing.
- **`ignore-errors` counts only where a SECOND value can be read** (`receivesMultipleValues`,
  whole-program: any `multiple-value-*`/`nth-value`/`%mv-spill`).
- **A keyword constructor no form references builds nothing, on both sides**: the `%make-X` every
  `define-condition` splices counts only when referenced; `make-instance` / `allocate-instance` /
  `change-class` naming a condition class count in its place (`instantiatesConditionClass`).
- **A signal reporting its own text needs no renderer** (`signalsItsTextControl`): `:format-control
  (%text-control v)` over a variable, of a class with no `:report` and no `:format-arguments`, reports
  exactly `v` (`textControlVariable`). `%clojure-refuse` and `%struct-type-error`
  ([defstruct.md](defstruct.md)) spell it.
- **A clause HEAD is not a call, and that skip is shared.** `evaluatedClauseForms` answers "which
  sub-forms of this clause-bearing operator are EVALUATED" for this scan and `runtimeSignalSites`.
  **A new scan that walks a program as code goes through it** -- the misread has cost three times.
- At `--optimize=size`: `(print (handler-case (+ 1 2) (error () 0)))` 23,216 -> 5,713 B. Gating the
  renderer on a pre-Pass-2 scan for printing operators is NOT sound: `format`'s `~A` lowers to
  `%princ-piece` after it.

## A signal renders nothing
**Invariant: `error` and `signal` of a condition evaluate its instance and what only the site can say
of it; the report, and any printing of a value, runs where the text is read, on all four backends.**
A report is program code: rendered at the signal, what it signalled replaced the condition and its
effects happened for a condition nothing printed. Matches SBCL 2.2.9 on all four:

| Form | SBCL |
| --- | --- |
| `(handler-case (error 'c) (error (x) (type-of x)))` (c: unbound slot read by its report), and `(signal 'c)`, `(cerror "go" 'c)` | `C` |
| `(handler-case (error 'd) ...)`, `(signal 'd)`, then the report's run count | 0 |
| `(handler-case (error 'e :a (make-instance 'o)) (error (x) (type-of x)))` (o: failing `print-object`) | `E` |
| `(signal 'e :a (make-instance 'o))` | nil |

- **The shape**: the message operand of `%error-cond` / `%signal-cond` is `(%signal-text c fallback)`
  (`LispMacroExpander.signalText`); every backend recognizes it (`conditionReportFallback`) and
  evaluates `fallback` alone. `%signal-text` (`signalTextDefun`) answers the report, else the
  fallback. A literal `:report` string and a `(%text-control v)` control with no arguments are plain
  messages.
- **Where it is read**: interpreter, `LispEvalException.getMessage` (once). JVM,
  `RontoSignal.getMessage`, once. wasm-GC, the entry pad.
- **`RontoSignal`, not the entry handler alone**: `getMessage` is what an embedder, a thread's
  default handler and `RontoHttpServer` read. `_signalNew` builds it; the `MethodHandle` names
  `_signalText` (`JvmSignalThrow`), and `am.ik.jvm.JvmClassSplitter` reads a handle as a call of its
  target. `toString` keeps `java.lang.RuntimeException: <text>`. It travels (`.kb/jvm-export.md`,
  "What travels") only where such a site compiled.
- **The failing report** is text: `Unhandled condition: #<error printing a C: <what failed>>`, SBCL's
  words, on all four. `%signal-text` holds one `handler-case`; a raw failure's classes stay in its
  narrowing (`withRawFailures`). `princ` of the same condition still signals, as on SBCL. Outside EH
  mode nothing reads the text, so `%signal-text` carries no guard (`SignalMessages.LAZY`).
- **The `~s` arm**: the fallback list is rendered once per program, in `%signal-text`;
  `buildsSignalledInitargs` keeps it out where every signal reports.
- **wasm-GC's "handlers ran" payload** is a cons opening on nil (`WasmErrorCompiler.emitSaysHandlersRan`):
  the initarg list is a cons too, opening on its type.
- **Deliberate differences**:
  - A report reads the dynamic bindings of where it is READ: `(let ((*base* 16)) (error 'c))` uncaught,
    reporting `*base*`, says 10 where SBCL says 16. Rendering at the signal when `%hc-match-p` finds no
    taker was rejected: code on every typed signal in hot methods, and no taker is not no reader (an
    `await`, a Java catch).
  - `warn` renders at its write, as SBCL does.
  - The object arm `(error c)` still evaluates its fallback at the site.
- Pinned: ci-spec `a-signal-renders-no-report`, `standalone:` `uncaught-failing-report-is-the-report`,
  `uncaught-initargs-past-the-handler-bind-handlers`; `JvmClassSplitterTest`,
  `JvmLispCompilerTest#anUncaughtConditionReportsOneLineAndRethrowsWithoutATrace`.

## A module with nowhere to report
Under `--no-wasi` standard error is a sink (unless `--host-output`: `WasmLispCompiler`'s `outputSink`
gates the pad, `entryReportOnly` and `unreadReports`), so `_start` lands on a plain trap. Where no
program code can hold a condition either (`mayHoldConditions`, and not `--dynamic`, restart mode, P1
futures or a data evaluator), no report is read at all (`Ctx.unreadReports`): a throw carries its
class and no text (`WasmRuntimeBuilder.emitUnreadThrow`, `buildUnreadThrowBody`), `SignalMessages`
is `LAZY`, and `%error` evaluates a message only for its effects (`buildsTextOnly`). A landing reads
no cdr. `(error (e) (declare (ignore e)) ...)` does not count as holding `e`.

The surface scan runs before the expansion, so an expansion that lets program code hold a condition
retries with the broad answer (`UncaughtReportUnforeseen`).

**`Ctx.handlersReadNoClass`** (`LispMacroExpander.handlersTakeEveryThrow`): every clause is headed
`error`, `serious-condition`, `condition` or `t`, and nothing throws what is no error. The first
clause takes whatever arrives, so a landing asks nothing, `hasLandingPad` is off and
`mayCreateInstances` leaves the catching form out. A wrong argument count keeps its throw
(`padAsksNothing`).

Proxy-Wasm SDK default handler: 8,420 -> 300 B raw on `http_body`. Bare `handler-case` with
`(error () 0)`: 2,701 -> 453. Pin:
`WasmLispCompilerIntegrationTest#aReactorNothingReadsAReportOfStillCatchesEveryClassItCan`.

## Signal messages are lazy on wasm-GC
**Invariant: on the wasm-GC backends a signal's message string is rendered only where a condition
value can reach program hands -- never at the signal point.** In EH mode the entry landing pad is a
second payload reader, so both gates go broad; outside EH mode nothing is observable.

- **`WasmErrorCompiler.compileCond` / `WasmSignalCondCompiler` never compile the REPORT half of their
  message operand**: only the fallback (`LispMacroExpander.conditionReportFallback`). The instance
  operand still compiles (initargs are evaluated at construction per CL).
- **A plain `%error`'s message operand always compiles in EH mode** -- it is what a caught raw trap
  becomes a `simple-error` from and the entry pad's only text.
- **The routing gate narrows outside EH MODE**: `macro/SignalMessages` `LAZY` when `!reportsUncaught`,
  answer `mayHoldConditions` = `mayCreateConditions` minus throw-only constructions. The keyword
  constructor every `define-condition` splices is exempted by SHAPE (`conditionConstructorName`)
  unless referenced -- otherwise every library that merely DEFINES conditions keeps the renderer.
- **`reportsUncaught` is a PRE-SCAN, not the definitive `ehMode`**: it scans `programUsesEhForm`,
  `catch`/`throw`, restart mode, async mode and `LispMacroExpander.runtimeErrorDispatchReports`.
- **What the pre-scan cannot see costs a second attempt, never a wrong report**: a cross-lambda
  `return-from` is lowered by `CrossLambdaExitLowering` after the expansion and reaches `ehMode`
  through `blockExitTag`; `ehMode && !reportsUncaught` abandons the attempt (`UncaughtReportUnforeseen`)
  and retries with the pre-scan forced. **Invariant: EH mode implies `reportsUncaught`.**
- **`%no-applicable-method` signals VALUES, not prose**: `no-applicable-method-error`, seeded ON
  DEMAND (`ClosRegistry.ensureNoApplicableErrorSeeded`; %-fenced slot names). Injection sits BEFORE
  the report-renderer injection (its tag must be in `conditionNarrowing`'s set) and AFTER the routing
  answer (a dispatcher alone must not flip routing). A `(simple-error ...)` clause no longer matches
  it (deliberate). The report strips the tag (`ClosRegistry.tagStrippedText`). With no stream the
  report renders over a raw handle ([read-load-streams.md](read-load-streams.md), "A
  `with-output-to-string` the gate never saw").
- **The remaining floor is a floor**: zlib still carries the value printers because the entry edge is
  `%SEQ-TO-STRING` (via `%FILL-RUNTIME`/`%REPLACE-RUNTIME`), whose element conversion IS princ
  semantics. **Trigger: a program family carrying the printers ONLY through
  `%seq-to-string`/`%schar-set-runtime` and where those bytes matter.**

## An uncaught condition reports ONE line, the same one, on all four backends
**Invariant: a signaled condition escaping the top level writes `Unhandled condition: <report>` to
standard error -- the same line on all four backends -- then the process exits the way it always
did.** Built from `compiler/UncaughtReport.PREFIX` at all three emission sites; the report text is
the one `princ` writes. A struct accessor on a non-instance agrees ([defstruct.md](defstruct.md),
"Accessors check their object"), and so does an out-of-range subscript ("An out-of-range subscript
is a type-error naming its bound").

**A program's own line**: the top-level form `(%reporting-uncaught #'line after body...)`
(`LispNames.REPORTING_UNCAUGHT_INTERNAL`, `UncaughtReport.Reporter`) is `body` as a `progn`, and
makes the report of what escapes `(line condition text)` instead of `PREFIX` + report (`condition`
nil where the backend has none). Location lines follow, then `after` is evaluated (`(%host-exit
1)`). It is no handler (holds no condition for the routing gate) but an EH form on wasm-GC
(`usesEhForm`). Per backend: `evalReportingUncaught`; `JvmUncaughtHandler.emitOwnLine`;
`WasmUncaughtReportCompiler` compiles `(%warn (line cond text))`. The Java front end's `%uncaught`
is the one user ([java-frontend.md](java-frontend.md), "Exceptions"): its handler holding the
condition cost every program ~38 KB of wasm (`Fib.wasm` 41,656 -> 2,868 B). Pinned by
`java-spec.yaml`'s `standalone:` cases with `error:` and `at:` on four backends.

**Under it, location lines** (`UncaughtReport.atLine` / `asyncLine`):
`  at FILE:LINE in FUNCTION` -- the innermost form read from a named file that the condition passed
through and the PROGRAM'S named function that form is written in -- then one
`  in NAME (async), awaited at FILE:LINE` per async boundary crossed. Nothing known (`-e`, only
macro-built forms) prints none. **The interpreter and the JVM backend print the same lines, for
Scheme and Starlark source too** (`cli/UncaughtReportParityTest`); wasm-GC only under
`--report-locations`. The interpreter alone ends with `  (use CL:CAR)` under the report of a name a
package read as its own ([packages.md](packages.md), "A package that does not read a standard name
as `cl`'s").
- **Which function: LEXICAL** (every backend) -- the named function whose text holds the form. A form
  in an anonymous lambda belongs to the function the lambda is written in, whoever calls it; one at
  top level, or in an async body, to none. Only a function read from a named file is named
  (`LispLambda.sourced`, `JvmSourceSites.sourced`). **Why lexical**: a property of the FORM, so no
  backend's tail calls, inlining or threads can change it (the dynamic rule gave three different
  answers on three backends). Internal names are reported as the program spelled them
  (`UncaughtReport.functionName`, `UncaughtReport.nestedDefun`,
  `ClosRegistry.functionNameForReport`: `%setf-W` -> `(SETF W)`, SBCL's). A renamed definition keeps
  the form's position, so `--report-locations=function` names the line the definition starts on.
- **Interpreter -- recorded on the throw path only** (`eval/ConditionTrace`, `LispEvalException.trace()`):
  `evalCons` keeps the innermost `LocatedCons` and the scope's `Environment.lexicalFunction`
  ([source-positions.md](source-positions.md) Phase 4). A form `eval` runs names none.
- **Interpreter async**: `runAsync` names the async function from its own frame and runs the thunk
  under a scope of no function (`asyncBody`); `crossedAsync` closes segment 0.
- **The await that re-signalled it** (every backend): a failed future holds ONE condition, which
  every `await` rethrows, so each await first rewinds the trace to what that future stored
  (`ConditionTrace.reawaited`, keyed by the `CompletableFuture`). A second await after a handler
  caught the first names ITSELF.
- **JVM -- read off the stack trace** (`codegen/jvm/JvmSourceSites`, `JvmUncaughtHandler`): the
  `LineNumberTable` numbers are SITE ids -- (file, line, function) in a table of string constants --
  not lines, since a method holds several files' forms and a class names one `SourceFile`.
  `Ctx.enterSite`/`leaveSite` mark changes; a lambda's method takes its builder's `writtenIn`
  (`LambdaInfo.writtenIn`), a tail-spine item the site where it was queued (`JvmBodyOutliner.Entry`).
  `_where` walks the trace. **Trap**: code outside any site is `JvmSourceSites.NO_SITE` (0xFFFF),
  never 0: `java.lang.classfile` drops a line-0 entry
  (`UncaughtReportParityTest#aComputedConditionTypeReportsTheInitargsTheCallPassed`).
- **JVM async**: the thunk appends a made-up frame `rontolisp/async.crossed(HEAD)` (`_asyncCross`;
  `/` is in no binary name); `run()` keeps the trace as a fourth payload element and each `_await`
  appends its own frames (`_asyncAwaited`). The report empties the trace, so it shows only under
  `RONTOLISP_DEBUG`.
- **JVM optimizations keep the granularity**: a fused integer tree ([jvm-int-fusion.md](jvm-int-fusion.md))
  reporting only its call's site stays shared and line-free; one spanning lines or holding an inlined
  defun gets its own method whose fallback marks each operation. A typed loop marks its array accesses.
- **Nothing located, nothing emitted** (`UncaughtReportParityTest#aProgramWithNothingLocatedCompilesAsItAlwaysDid`).
  `JvmClassSplitter` plays each line entry at its instruction, so it survives relaxed branches and
  splits (`LineNumberTableTest`).
- **Harness decisions, per suite**: `ci-spec.yaml`'s `standalone:` compares stderr lines as CONTAINED,
  in order (wasmtime prints around ours), so location lines are pinned by `RontoLispCliStreamsTest`'s
  `anUncaught*` cases and `UncaughtReportParityTest`. `scheme-spec.yaml` compares as CONTAINED too.
  `JvmLispCompilerTest`, `JvmSizedMainTest`, `WasmLispCompilerIntegrationTest` read from no file, so
  they pin the report line alone.
- **Interpreter / compile failures** (`RontoLispCli.runReporting`): only `main` catches -- `run` still
  throws, so an embedder keeps the exception. A rontolisp diagnostic says `error:` and keeps the
  `file:line:column:` prefix (`locateCompileFailure`). **A RUNTIME condition's MESSAGE carries no
  position on any backend**: the location goes under the report, never into text a program can read.
- **JVM** (`JvmUncaughtHandler`): a last exception-table entry over `main` prints, EMPTIES the stack
  trace and RETHROWS. **Not `System.exit(1)`**: a compiled `main` is invoked in-process by ~110
  assertions and by any embedder; the launcher supplies exit 1.
- **wasm-GC, EH MODE ONLY** (`WasmUncaughtReportCompiler`): the entry function's
  `try_table (catch $lisp-cond 0) (catch_all 1)` landing compiles
  `(%warn (%string-concat "Unhandled condition: " (if cond (%signal-text cond msg) m)))`, `m` =
  `(if msg msg "NIL")`: `(error 'simple-error :format-control nil)` prints `NIL` on all four
  (`standalone:` `uncaught-nil-message-report`). Then `unreachable`: the trap is the exit CLASS hosts
  expect. **An export wrapper reports the same way where the entry does** (`Ctx.exportsReportUncaught`,
  `WasmUncaughtReportCompiler.emitExportEpilogue`): the line, location lines, never the reporter's
  `after`, then the trap, the re-entry guard cleared on both landings. A Rust component's panic
  prints before its trap, and the trap alone is what a host's log shows. Serve's wrappers keep the
  catch_all-only landing (the serve loop's to report). Outside EH mode an export traps unreported, as
  the entry does. Pinned by `anOptimizedComponentStillReportsAnUncaughtConditionOfItsEntryAndOfAnExport`,
  `JavaWitImportTest.aClassImplementsTheWorldAComponentExports`.
- **It is a FIFTH producer of the reserved `*error-output*` handle**, synthesized in Pass 2 and
  invisible to any source scan: `WasmUncaughtReportCompiler.emittedFor(ehMode)` is the ONE predicate,
  ORed into `WasmComponentBuilder.Narrowing`'s `reachesStandardError`
  ([standard-output-redirect.md](standard-output-redirect.md)).
- **Outside EH mode nothing changes, byte for byte**, unless `--report-locations` turns the report on
  (`SignalMessages.ENTRY_REPORT`); reporting by default would turn EH mode on for every program
  (121,572 -> 175,486 B on a toy). `--no-gc` is exempt; a `--no-wasi` reactor without `--host-output`
  gets the plain trap (`doc/{en,ja}/guides/wasm-gc-module.md` says so). Broad routing gate in EH mode
  on zlib `--optimize=size`: +17.2%, taken deliberately. Narrowing to classes that can ESCAPE buys
  nothing: without a catching form every constructible class escapes.
- **A class that reports nothing is reported by its SIGNAL SITE**: `Condition (C :INITARG v) was
  signalled.` (typed signal) / `Condition of type C was signalled.` (`(error c)`), from the site's
  fallback, compiled into the payload cdr. **The text is built in ONE place per shape**
  (`signalTextDefun` over `signalledInitargs`, `legacySignalMessage` for `warn`,
  `expandObjectSignal`'s `typeMessage`): a change to its shape changes all four together. Chosen over
  a pad-side fallback because the typed text names the initargs AS WRITTEN, which a built instance
  cannot reproduce. A COMPUTED type lists the plist itself, and `:format-control` is the message only
  when the plist carries the key (`ComputedConditionTypeReportFixture`). A class that INHERITS a report
  builds no fallback (`inheritsConditionReport`).
- `(error c)` names the class by `symbol-name` of its tag; a tag another package names is qualified
  (`%class-PKG::C`), so `conditionTypeName` cuts the prefix only where it is (ci-spec
  `a-signal-renders-no-report`).
- Pinned cross-backend by `ci-spec.yaml`'s `standalone:` list (`CiSpecE2eTest` runs one program at a
  time, per backend; the corpus cannot host these since running one ends the program).

## Location lines on wasm-GC (`--report-locations`)
**Invariant: `--report-locations=line` makes a wasm-GC module (Preview 1, `--component`,
`--native`) print the interpreter's report and location lines, byte for byte -- turning the report
on for a program outside EH mode too; `=function` prints the function and the line its definition
starts on. OFF BY DEFAULT, and a module with no file-read form (`-e`), no option, or no
report anywhere to print to (a `--no-wasi` reactor outside EH mode) is byte-identical to a build
that never knew about it.** `codegen/wasm/WasmUncaughtLocations`; pinned
by `cli/WasmReportLocationsTest` (each case against the interpreter's own output) and
`e2e/NativeOutputE2eTest`.

- **Opt-in because size decides it** (the user's call): the lines cost bytes in every function read
  from a file, and outside EH mode the report itself costs the EH machinery.
- **Outside EH mode the option turns EH mode on, as `SignalMessages.ENTRY_REPORT`**
  (`entryReportOnly`: option, no EH trigger, `WasmUncaughtLocations.readsAnyFile`, never a `--no-wasi`
  reactor). The printing operators then route through the report only when program code can HOLD a
  condition (`ClosRegistry.printsConditionReports`) while the renderer stays on
  `routesConditionReports`.
- **A declined renderer drops `%format-condition`'s FUNCTION-control arm in every mode**: the arm is a
  funcall of a runtime value, which with `read` keeps every built-in dispatchable by name. (The user
  approved giving up byte-identity of `--report-locations` vs plain EH mode for this.)
- **A frame** = a defun, lambda, top-level chunk or `--component` resume whose OWN code has a form
  read from a file. Its `try_table (catch $lisp-cond)` landing calls `_uncaught_note(payload, file-id,
  line, name, hop)` and rethrows the SAME payload. Library source and macro output are not frames,
  except an ASYNC BODY with no located form (`Spec.hopOnly`): it notes its hop.
- **The note is keyed by payload IDENTITY**: a caught condition cannot leak into a later report, and a
  rethrow keeps the inner frames' note. Rules = `ConditionTrace`'s (`Ctx.ucWrittenIn`). So every
  rethrow keeps the payload: `%hb-guard` stores into the payload it caught rather than consing a new
  one (under the option only) -- a fresh payload lost the handler frame's lines.
- **A landing pad inside a frame notes first** (`notePad`): its refresh
  ([wasm-landing-pad-refresh.md](wasm-landing-pad-refresh.md)) would put the line local back to the
  region's entry. The position locals stay out of the pad's push (`positionSlot`); `resyncPad`
  resets them after.
- **An await rewinds the note** (`_uncaught_reawait(future, payload)`): the first await records a
  snapshot keyed by the future, each later one restores it.
- **Texts ride with their frame**: name and hop text are unspelled literals in the frame's own landing
  (`compileUnspelledLiteral`: a spelled one would arm the dispatch gate), so the shaker drops them with
  the frame. A name TABLE was rejected: names outlived shaken functions. Files are the one table.
- **The line**: an i31 in an eqref local (mirrored by a resume's spill array), static tracking in
  `Frame.curLine`; starts NULL. Printed by `_uncaught_digits`, NOT `%princ-to-string`, which would pull
  the printer into a module that never prints.
- **Tail calls**: a `return_call` leaves the try_table. A direct one stays one only into another frame
  (`tailCallOp`); through a function value the callee is tested at run time (`_uc_frame_p`). An async
  body's frame never tail-calls out. **Trap**: disabling tail calls in frames outright breaks the
  `.kb/wasm-tail-calls.md` invariant (a Scheme named `let` overflows); so would a plain `call` through
  every function value.
- **A fused integer tree** ([wasm-int-fusion.md](wasm-int-fusion.md)): its fallback marks each
  operation (`enterOperation`) with an owner local for inlined defuns; the fast path bails, marks
  nothing.
- **Preview 1 async**: a failed future's await rethrows its payload ([async-await.md](async-await.md)),
  so the hop names the await's line.
- A raw trap prints no report at all, with or without the option: the
  three-point spectrum above.

Cost: `--report-locations` grows an EH module by single-digit percent at `function`, about twice at
`line` (zlib); toys pay a few dozen bytes per frame; outside EH mode, a few hundred bytes plus the
throw path. Run time, `function` vs off (x86-64 Linux, bench-report programs): `fib` 1.28x on wasmtime
49.0.0, 1.26x on 51.0.0, others 0.96-1.24x; V8 within noise. The cost is Cranelift's try_table around
calls. **Re-evaluate if** wasmtime stops spilling across calls inside a try_table -- or if the option is
meant to stay on in production, in which case the per-call catch is what to replace.

## cerror, signal-operator function values, runtime type dispatch
- `cerror` has TWO lowerings on the restart-mode gate: outside it `(error datum args...)` (nothing
  could invoke `continue`); in restart mode the REAL `(restart-case (error ...) (continue () ...))`.
- **A report over format arguments is a function**: `with-simple-restart`'s `:report (lambda (s)
  (format s control args...))`, arguments evaluated when it prints (CLHS, SBCL); `cerror`'s over
  temporaries the `error` call assigns, so error and report see one evaluation and the datum stays as
  written for static dispatch.
- **A control that is not a literal string is formatted too** (both lowerings and `eval-macros.lisp`):
  serapeum's `(with-simple-restart (recklessly-continue continue-string) ...)`.
  `reportRendersRuntimeControl` brings the renderer. ci-spec `restart-system`.
- `runtimeSignalSites` reads `cerror`'s datum one place later, and `assert`'s two (they lower to
  `error` after the scan).
- **What a computed datum is, on all four** (ci-spec `run-time-condition-designators-of-the-signal-family`,
  SBCL 2.2.9): a string is a control, a symbol names a class (arguments are initargs), a condition is
  signalled, anything else or a symbol no class of the operator holds is the `type-error` of a
  designator.
- **Interpreter: `error`/`signal`/`warn`/`cerror` have FUNCTION values** that rebuild the literal call
  (`rebuildSignalForm`); `resolveFunction` checks the function namespace BEFORE the
  macro/special-operator guard. A computed symbol datum re-dispatches (`runtimeTypeDispatch`); a keyword
  datum stays quoted, or it would re-dispatch for ever.
- **Compiled: one defun per operator, `%error-runtime` / `%signal-runtime` / `%warn-runtime`
  (`runtimeSignalDefuns`), and `%make-condition-runtime`.** Once per program: inlined per class per
  site overflowed one method (cl-postgres, past 64 KB). One helper per class (`%ERROR-RT-n`). The site
  asks `ClosRegistry.dispatchesRuntimeDatum`, so a form the scan cannot see takes the inline object arm,
  never an undefined function. `#'cerror` -> `%cerror-runtime`.
- **The dispatch is narrowed to the classes a datum can name (`runtimeDesignatedClasses`)**. A program
  calling `intern`, `find-symbol`, `read`, `read-from-string`, `type-of` or `class-name` reaches all its
  own classes; a seeded one it builds from a string and never spells is a `type-error` (the deviation
  that buys this: a seeded class is 66 KB of helpers). `--dynamic` keeps every class.
- **`(format nil ...)` and `(concatenate 'string ...)` take no dispatch** (`isKnownStringDatum`). A
  wasm-GC `error` outside EH mode with no class to name stays inline (`ObjectDatum.UNCHECKED`).
- **Where the check sits**: the fallback's `%obj-is` over `condition`'s tags, once per operator
  (`renderedDesignatorTypeError`). **Trap**: a chained dispatch segment is the `chainedDispatchDefuns`
  shape: one `cond` lowers to nested `if`s on the JVM, and past ~140 classes the else-branch overflowed
  the signed-16-bit branch offset.
- **A quoted name no class holds is that `type-error` too, at run time (`namesNoSignalledClass`)**; it
  still compiles, for a library's cold branch naming a sibling package's condition unqualified
  (cl-postgres' `'protocol-error`). No registry (`EMPTY_CLOS_REGISTRY`) keeps the degradation in
  `buildTypedConstruct`.
- **Deviations kept.** A plain `type-error` `The value X is not of type CONDITION` where SBCL has a
  `simple-type-error` `X does not designate a condition class.`. A function datum signals as a string
  holding its printed self: calling it needs a string stream in every program with a computed datum.

## Phase 4 -- handler-bind + the restart stack
**Invariant: the restart system is ONE shared Lisp-level lowering in `LispMacroExpander`, identical
on the interpreter, the JVM and both wasm-GC backends.** A divergence can only come from the
primitives underneath (`catch`/`throw`, `unwind-protect`, globals, closures). `--no-gc` keeps the lite
lowering.
- `expandRestartCase`/`expandRestartBind`/`expandWithSimpleRestart`; runtime defuns from
  `LispMacroExpander.restartRuntimeForms` (`ensureRestartRuntimeLoaded()` on the interpreter).
- **Two dynamic stacks, both TOP-LEVEL GLOBALS** (`%HANDLER-CLUSTERS%`, `%RESTART-CLUSTERS%`), plain
  `setq` restored by an `unwind-protect` cleanup; per thread ([threads.md](threads.md), "What a thread
  keeps of its own") and per suspended frame ([async-await.md](async-await.md), "The eager part is a
  call"). A special `let` would work now too; the `setq` shape stays because it is pinned cross-backend.
- **The restart transfer rides `catch`/`throw`** with a FRESH cons tag (`(list '%restart)`): `eq`
  identity, no collision, crosses functions, runs cleanups, passes `handler-case` uncaught.
- **Clause bodies are compiled INLINE in the dispatch, never wrapped in a lambda** -- postmodern's
  `transaction.lisp` `(go start)` must stay a plain goto/br.
- A restart record is `(%restart name invoker report interactive test)`; the invoker is called with ONE
  fixed-arity `funcall` -- `apply` would drag the WASM eval runtime into every restart program.
- **`%run-handlers` walks the clusters and, per CLHS, rebinds the global to the REMAINING clusters
  while a cluster runs**; a handler that returns declines.
- **The signal hook** (`signalHook`): `(%run-handlers <instance>)` BEFORE the terminal, so handlers run
  with the signaling frame's restarts established (restart-mode `warn` is wrapped in a
  `muffle-warning` `restart-case`). **Every restart-mode signal terminal CARRIES the instance the hook
  just ran** (`__signal_inst`), so a `handler-case` catches the instance the handlers saw, and says the
  handlers ran (third operand `t`).

### Errors BUILT-INS raise run handler-bind handlers too
Rove's failure-recording model is `handler-bind` around USER code, so `(car 1)`, an out-of-range
`aref`, `(/ 1 0)` and an undefined function must reach the handlers.

- `(%hb-guard body)` landing pad per backend (`JvmHandlerCaseCompiler.compileGuard`,
  `WasmHandlerCaseCompiler.compileGuard`, `LispEvaluator.evalHbGuard`): synthesizes the `simple-error`
  of a condition-less throw, runs `%run-handlers` over the FULL cluster stack (one pad run covers every
  enclosing cluster), rethrows carrying the instance and saying the handlers ran. No hc-depth, no
  cleanup, does not catch the block-exit tag.
- **A handler's own call runs in a pad too** (`runHandlersDefun`), while `%handler-clusters%` holds the
  REMAINING clusters (CLHS 9.1.4.1); without it the handler-bind's pad ran the failing handler again.
  Pinned by ci-spec `restart-system` and `failing-handler-bind-handler-report`.
- **The interpreter ADDITIONALLY runs handlers at the SIGNAL POINT for built-ins**
  (`LispEvaluator.apply`, `builtinFailureMessage`), so restarts below the handler-bind are invocable.
  Zero cost until an exception escapes.
- **Deviations**: on the COMPILED backends a raw error's handlers run at the `handler-bind` boundary --
  cleanups have run and restarts below it are gone; a SIGNALED condition keeps exact signal-point
  semantics everywhere. wasm-GC runs handlers only for `$lisp-cond` throws, so **a rove test whose body
  traps still ends a wasm run**.

### Whether the handlers ran rides the flight
**Invariant: a condition's `handler-bind` handlers run ONCE per signal, whatever else is signalled,
handled, declined or abandoned while it is on its way out -- because whether they ran is carried by
the condition's own throw, never by a global.** Identical on all four backends; pinned by
`HandlersRunOnceFixture` through `LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest#handlerBindHandlersRunOnceWhileACleanupSignals` and ci-spec
`handlers-run-once-while-a-cleanup-signals` (over standard condition classes: four
`define-condition`s pushed the shaker corpus class past its 52,000 constant-pool tripwire).

- **Who says so**: the hook's terminal `(%error-cond c msg t)` / `(%signal-cond c msg t)`
  (`LispMacroExpander.handlersRanTerminal`) -- reaching it proves the walk completed -- and a pad that
  walked, on its rethrow. Every other throw is walked by the first pad or seam it crosses. A hooked site
  that forgot its operand fails SAFE: walked again, never skipped.
- **Where it rides.** Interpreter: `LispEvalException.handlersRan()`, read through
  `withHandlerBindHandlersRun`. JVM: `_condRan(t, c)` records `{_condTl, c}`, `_condOf` answers the
  condition; a declining restart-mode landing puts back the record AS TAKEN. wasm-GC: payload
  `(instance . (nil . message))` (`WasmErrorCompiler.emitSaysHandlersRan`).
- A single global mark (`%handlers-ran%`) was replaced because any condition signalled while another
  was on its way out overwrote it. Rejected too: saving the mark around a `handler-case` (misses declined
  and abandoned cases), several marks (need a bound, outlive a request), a per-instance flag (changes
  every layout; a re-signalled instance is a new flight).
- Cost: outside restart mode byte-identical. **Traps met**: the message in the wrapper's CAR broke the
  type-test fold's "no cons car holds a string" proof ([wasm-ref-type-fold.md](wasm-ref-type-fold.md));
  unwrapping before the JVM landing's inline synthesis bloated StackMapTable. **Re-evaluate if** the
  per-site wrapper matters: a hooked site whose message is nil could throw a shared `(nil . nil)` global.

### A `handler-case` joins the cluster stack, so it SHADOWS an enclosing `handler-bind`
**Invariant: CLHS 9.1.4.1 -- handlers run MOST RECENT FIRST and `handler-case` transfers control, so
a `handler-case` established inside a `handler-bind`'s extent handles the condition and the enclosing
handler-bind handler never runs.**

- `LispMacroExpander.handlerCaseProtectedForm` wraps the PROTECTED FORM (only it) in the same push,
  one cluster of `(type-test-closure . nil)` entries. **The nil cdr is the marker**: `%run-handlers`
  stops there (`__rh_stop`); the transfer is the terminal's ordinary throw.
- **Wrapping only the protected form is what pops the cluster for the clause bodies**.
- **Three call sites = the three handler-case implementations** (`LispEvaluator.evalHandlerCase`,
  `JvmHandlerCaseCompiler.compile`, `WasmHandlerCaseCompiler.compile`). **Restart mode is the gate**;
  `ignore-errors` inherits it.

### The restart-mode gate
**`LispMacroExpander.usesRestartSystem(program)`, computed on the SURFACE program**, matching names in
OPERATOR POSITION of evaluated forms only (no spine cells, no `quote`d data, no keyword heads). **Trap:
a spine-walking scan read chipz's `tagbody` tag `CONTINUE` as an operator and put every chipz program
into restart mode.** A binding pair or clause head spelling a restart name still over-approximates
(safe).

It must be computed before `expandTopLevelDefinitions` and threaded into the JVM `blockExitChannel` /
WASM `blockExitTag` (on WASM that implies EH mode), `mayUseInstances`, and `Ctx.restartMode`. **All of
these pre-scans run before Pass 2, where the expansions happen, so none of them can see the expansion
products -- that is why the gate is a separate surface scan.**

- **Trap (closed)**: `WasmAsyncEmit.freshCtx` once enumerated flags explicitly and dropped
  `restartMode`, so a top-level `handler-bind` never ran its handlers; it now inherits every
  `Ctx.Builder` field ([wasm-function-body-size.md](wasm-function-body-size.md)).
- Interpreter: `ensureRestartRuntimeLoaded()` on the first restart form or restart-runtime name; the
  interpreter re-expands per evaluation so later signals pick the hook up.
- `FreeVarAnalyzer` expands all four macros; `expandHandlerBindForAnalysis` substitutes `t` for clause
  type tests.
- Lite deviations (on the doc pages): `&optional` clause parameters take nil rather than their
  default, no condition-restart association, restart records print as plain lists,
  `:report`/`:interactive` rendered/run only by `rontolisp --swank`'s debugger ([swank.md](swank.md)),
  `check-type`/`assert`/`ccase`/`ctypecase` offer no `store-value` restart.
- `use-value`/`store-value` share `LispMacroExpander.valueRestartDefun` (nil when none is active). In
  `RESTART_RUNTIME_FUNCTION_NAMES` and `PackageRegistry.CL_FUNCTIONS`.
- **ci-spec `restart-system` puts the whole concatenated program into restart mode**, so a hook
  regression shows up as an unrelated case failing.

### SIGINT
`rontolisp:interactive-interrupt` (`ClosRegistry.ensureInteractiveInterruptSeeded`: a
`serious-condition`, no `error`, as SBCL's `sb-sys:interactive-interrupt`; the interpreter seeds it
always, a compile path where the program names it). `LispInterruptSignal` (nREPL, SWANK) is
deliberately no condition.

**Interpreter**: `cli/InterruptSignal` (the CLI's `interpret` only) registers `sun.misc.Signal` when a
clause first names the condition (`LispEvaluator.noteInterruptHandler`). `stopIfInterrupted` signals
on the thread that established that handler, and past a declining handler throws `LispExitSignal(130)`
(no cleanups, as SBCL under `--disable-debugger`).
- **Why opt-in, not always on**: a request is taken only at a poll, and `read-line` on stdin or `accept`
  never polls; always-on would make the first Ctrl-C of every blocked program do nothing and move a
  server's Ctrl-C from the shutdown hook.
- `Thread.interrupt` wakes `sleep` and `condition-wait` (`resumeAfterInterrupt`); `join-thread` does not
  wake. A SIGINT finding the last one still pending, or the target dead, is `System.exit(130)`.
- A process started with SIGINT ignored stays ignoring (HotSpot `JVM_RegisterSignal` refuses; a
  non-interactive shell's `&` does that, so `InterruptSignalTest` assumes it away); `-Xrs` throws
  `IllegalArgumentException`, taken as the host keeping it.

**JVM**: same contract, pinned by `InterruptSignalTest` on both. `Thread.stop` is gone, so
`codegen/jvm/JvmInterruptPoll` calls `%interrupt-poll` (`LispMacroExpander.interruptPollDefun`) at every
function entry (past the self-tail head), tagbody label, `while` head and typed `dotimes` head. A
built-in's own loop does not poll.
- **The gate** (`JvmLispCompiler.takesInterrupts`): the program names the condition and this class's
  `main` runs it (not `jvm-export`, `--no-main`, `.war`). Off, no byte. The injection precedes the
  surface scans, so its `signal` opens `signalClauseMatch`.
- `runtime/RontoInterruptSignal`: `listen` at a clause naming it
  (`LispMacroExpander.namesInterruptCondition`), `take` under the poll.
- **The request's interrupt must land before a take**, or the status wakes the next blocked call with
  `was interrupted`. Both hold one lock over request and take (`interruptedThreads`): 0 in 2000.
- ci-spec's `interactive-interrupt-condition` makes the concatenated JVM leg always compile with polls.

**WASM** has no signals: wasmtime dies of SIGINT and no handler runs.

### `signal` declines a handler-case no clause matches -- on every backend
**Invariant: CLHS 9.1.4.1 -- `signal` transfers control only to a handler that will handle the
condition. A `handler-case` whose clause types do not match is not applicable: the signal passes it
by, returns nil so the forms after it run, and the handler-case stays armed for a later matching
condition -- identically on all four backends.**

- The clause types ride the cluster stack outside restart mode too (`ctx.restartMode ||
  ctx.signalClauseMatch`).
- **`%signal-cond` consults the stack**: depth positive FIRST (depth is per thread, the stack a shared
  global), then `%hc-match-p` over nil-cdr entries only. A `:no-error`-only handler-case pushes nothing
  and is declined (CL).
- **The gate is `LispMacroExpander.needsSignalClauseMatch(program)`**: BOTH a `signal` (operator
  position or `#'signal`) AND a `handler-case`/`ignore-errors` head. Otherwise byte-identical.
  `WasmAsyncEmit.freshCtx` copies the flag.
- Known blind corner: a `signal` or catching form reachable only through runtime `eval` or a forged
  designator keeps depth-only behavior.

### `warn` signals before it writes -- on every backend
**Invariant: `warn` is `signal` and then the write (CLHS `warn`). A `handler-case` clause matching the
warning takes it and nothing is written; a report renders only for the warning that is written.**
SBCL 2.2.9's answers on ci-spec `warn-signals-to-a-handler-case`.
- `expandWarn`'s `signalsHandlerCase` puts `(%signal-cond instance message)` ahead of the `%warn`
  (`warnAfterSignal`).
- **The compiled gate is `needsSignalClauseMatch` itself**. Warn half: the program warns and a clause can
  hold a warning (`clauseTypeCanHoldWarning`; a program's own class counts whatever its parent), or it
  runs forms at run time (`runsFormsAtRunTime`) with any catching form. Otherwise the program keeps its
  write and pays nothing. A program a warning clause holds pays the walk: `(handler-case (f) (warning ()
  1))` wasm-GC 3,297 -> 9,883 B, class 10,932 -> 15,727 B.
- The interpreter always asks (`evalSignalCond`).

### `warn` of what is no warning is a `type-error`
**Invariant: `warn` signals nothing but a warning. Anything else is a `type-error` with that datum and
expected type `warning`, before any signal, restart or write, on all four.** SBCL 2.2.9's answers on
ci-spec `warn-of-what-is-no-warning-is-a-type-error`.
- **A quoted name of a class outside `warning`, or of no class,** is known at expansion
  (`namesNoSignalledClass`): the `type-error` holds the symbol, as SBCL's does.
- **The object path tests at run time**, ahead of the `muffle-warning` restart (so `find-restart` is
  nil, SBCL's). The registry is complete on the compile paths, since `define-condition` does not run in
  compiled `eval`.
- **It is the backend's own `error` lowering**, after the scans, so three read the site:
  `conditionNarrowing` (else a caught one prints `#<TYPE-ERROR ...>`), `usedLayoutTags`
  (`signalCanSignalTypeError`; else `no layout was baked` fails the compile), `constructsInstance`.
- **Deviations kept.** SBCL: `simple-type-error` `X does not designate a WARNING class`; here a plain
  `type-error` (a control with directives pulls the renderer in). A non-condition expects `warning`
  here, SBCL's internal `condition-class` there.

## A built-in error carries its CONDITION CLASS
`(handler-case (car 1) (type-error ...))` matches, and so does rove's `(ok (signals (car 1)
'type-error))`. **The class is decided where the failure is DETECTED, never by pattern-matching the
message at the catching end** -- except for the failures the backends report as bare text.

- **Interpreter**: `LispEvalException.ofClass(className, message)`; `LispEvaluator.synthesizeCondition`
  (the ONE synthesis point, shared by `handler-case` and `%hb-guard`) via
  `ClosRegistry.newReportingCondition`. A raw Java failure escaping a built-in is classified by
  `LispEvaluator.rawFailureConditionClass` at the `apply` seam.
- **JVM**: the landing pad (`JvmHandlerCaseCompiler.emitSynthesizeCondition`) classifies the caught
  `Throwable`: `ClassCastException`/`IndexOutOfBoundsException` -> `type-error`; `ArithmeticException`
  -> `division-by-zero` when its message contains `ClosRegistry.DIVISION_BY_ZERO_MESSAGE_TOKEN` else
  `arithmetic-error`; and -- the message exceptions -- `The variable `/`The function ` ... ` is
  unbound`/` is undefined` -> its cell-error class, because those sites are plain `RuntimeException`s
  with no channel to carry a class; a wrong-type operand's exception is recognized by identity
  (`_teSlot`, "A non-number reaching arithmetic"). The arms are Lisp forms
  (`LispMacroExpander.reportingConditionForm`), so no slot index is baked. **The synthesis is ONE
  method per class, `_hcSynth`** (`JvmHandlerCaseCompiler.conditionSynthesizer`, memo
  `ConditionChannel.conditionSynthesizer`), called when `_condTake` answers null: roughly halves a
  landing's code and StackMapTable. The constant pool barely moves (a landing interns nothing new).
- **WASM**: only `$lisp-cond` throws land in the pad.
- **Undefined functions keep the call-time stub contract**: a call to a name with no definition
  compiles to `The function X is undefined` at call time plus a compile-time warning, matching the
  interpreter's late binding. In a program that can bind a name at run time the call reads the
  function namespace first (`.kb/symbol-runtime-api.md`, "A name only the run time binds is looked up
  when it is used"). **The stub constructs no instance itself**: it is produced during BODY
  compilation, after `mayCreateInstances` and `usedLayoutTags` decided (`%OBJ-NEW reached the compiler
  with no instance representation`). JVM throws the text raw
  (`JvmFunctionFormCompiler.emitUndefinedFunctionThrow`); wasm calls `_undefined_function` wherever the
  module baked the class (`emitsUndefinedFunction`); only a wasm module without it keeps the
  `(error "...")` stub (`LispMacroExpander.undefinedFunctionCallStub`) -- the one divergence left: a
  `simple-error` clause catches it there (pinned by `WasmLispCompilerIntegrationTest`
  `ehAnUndefinedFunctionCallIsCaughtAsTheClassTheProgramNames`).
- **A literal REFERENCE to such a name keeps the same contract**: `#'name` / `(symbol-function 'name)`
  compile to the call's signal at the reference with `warning: ... compiled as a run-time error`
  (`Jvm`/`WasmFunctionFormCompiler.compileNamed`, `WasmFunctionCallCompiler.emitUndefinedFunctionSignal`).
  A QUOTED designator (`(funcall 'name ...)`, `apply`, `mapcar`, ...) stays the symbol
  (`FunctionDesignators.normalize(fnForm, defined)`), looked up when the call runs -- after the
  arguments (SBCL: `(:SPREAD :ARG)`), through `_fenv` where a runtime `eval` defined it -- with
  `warning: ... looked up when the call runs`. **A standard function is not undefined**:
  `PackageRegistry.isClFunctionName` or `BuiltinFunctionWrappers.isWrappedBuiltin` never takes the
  late-binding arm. Names with no value route refuse with `Cannot compile: NAME as a function value
  (this backend has none for the built-in)` (`.kb/lisp2-namespaces.md`, "A native built-in's function
  value"; `BuiltinFunctionWrappers.noFunctionValueMessage`). Pinned by
  `UndefinedFunctionNameFixture.REFERENCE` / `REFERENCE_RESTART`
  (`anUndefinedNameTakenAsAFunctionSignalsWhenTheReferenceRuns` in `LispEvaluatorTest` /
  `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`) and `RontoLispCliStreamsTest`
  `aReferenceToAnUndefinedFunctionWarnsAtTheReferenceOnEveryBackend`; not in ci-spec, whose compiles
  forbid the warning.
- **A macro or special operator taken as a function is an `undefined-function` on every backend**
  (`resolveFunction` throws `CellErrorException.undefinedFunction`): `The function NAME is undefined`.
  SBCL words it per kind (`COMMON-LISP:WHEN is a macro, not a function.`, class
  `SB-INT:SPECIAL-FORM-FUNCTION` for special operators), not pinned; `symbol-function` of a macro
  answers the expander in SBCL and signals here. Pinned by `UndefinedFunctionNameFixture.MACRO_OPERATOR`
  / `MACRO_OPERATOR_RESTART` (`aMacroOrSpecialOperatorTakenAsAFunctionIsUndefined` in the three
  suites).
- **Every undefined-function names its function, every unbound-variable its variable**
  (`cell-error-name`, SBCL's answer). Interpreter: `CellErrorException`, `symbolOfSpelling`. JVM: the
  pad reads the name back out of the message (the text between `*_MESSAGE_PREFIX` and suffix IS the
  symbol, `"NIL"` -> null; `emitCellErrorConstruction`). wasm: `NotFunctionReport`'s typed throw builds
  the slot array on the stack (`emitConditionThrowOnStack`) because local 0 is still live;
  `_symbol_value`'s miss throws `UnboundVariableReport`'s `unbound-variable` in EH mode and still traps
  outside it. Pinned by `UndefinedFunctionNameFixture` / `UnboundVariableNameFixture`
  (`an{UndefinedFunction,UnboundVariable}CarriesItsNameInTheCellErrorNameSlot`) and ci-spec
  `undefined-function-carries-its-name` / `unbound-variable-carries-its-name`. A compiled read of a
  special without a value or a global before its first store signals it too
  ([dynamic-special-variables.md](dynamic-special-variables.md), "A read of a special without a value",
  "A read of a global before its first store").
- **The report spells the name as the symbol is stored** (`symbol.name()`): `P::V`, `#:U`, a keyword's
  colon, `lower` without bars (`princ` drops the qualifier, `prin1` adds bars).
  `WasmRuntimeBuilder.emitSymbolSpelling` frames a copy (a symbol is an unframed `TYPE_STRING`). The
  compiled backends print an `(intern "F" :p)` symbol `P:F` where the interpreter prints `P::F`
  (`.todo/156`), so `UndefinedFunctionNameFixture.SPELLING` names its symbols by literal. An EMPTY
  symbol name still traps on wasm and gives `index out of bounds` on the JVM.
- **An undefined `(setf name)` function is reported as `(setf name)`, not as the `%setf-name` it is
  stored under** (SBCL: `cell-error-name` is `(SETF NAME)`). `ClosRegistry.functionNameForReport` is
  the one place it is decided. JVM: `emitCellErrorConstruction` reads `startsWith("(SETF ")`. wasm:
  `NotFunctionReport.emitUndefinedSetfThrow` (cons test ahead of the symbol arm). Only a name known at
  compile time is mapped. Pinned by `UndefinedFunctionNameFixture.SETF_FUNCTION` /
  `SETF_FUNCTION_RESTART` (`anUndefinedSetfFunctionIsReportedAsTheNameTheProgramWrote` in the three
  suites) and the warning by `RontoLispCliStreamsTest`.

- **The message a raw host failure reports is rontolisp's, not the host's**:
  `ClosRegistry.TYPE_ERROR_MESSAGE` replaces a `ClassCastException`'s Java class names and
  `INDEX_OUT_OF_BOUNDS_MESSAGE` the JVM's `Index 10 out of bounds for length 3` (whose length counts the
  layout cell) -- now only for a string's index (`.todo/186`). Per-site texts a built-in writes itself
  are NOT identical across backends. The numeric operators name themselves at the call, never by
  message parsing. The substitution does NOT reach the UNCAUGHT line on the JVM -- deliberate.
- **Restart mode would move a string-datum signal out of the pad's reach**: its `simple-error` is built
  at the SIGNAL point, which is why the undefined-call stub is a raw throw classified at the pad.
- **`conditionNarrowing` marks the five raw-failure classes constructible**
  (`LispMacroExpander.rawFailureConditionClasses`) -- else a caught `(car 1)` prints a bare
  `#<TYPE-ERROR>` -- CONDITIONAL on a pad (`LANDING_PAD_HEADS` -> `ConditionTagScan.hasLandingPad`);
  unconditional cost zlib 680 B.

## A non-number reaching arithmetic signals a catchable type-error
**Invariant: a wrong-type operand reaching a numeric operator signals a CATCHABLE error whose text
is `OP: The value <prin1> is not of type T` -- the operator and the type IT accepts, CL's
`type-error` shape -- byte-identical on all four backends, and the condition is a `type-error`
whose `type-error-datum`/`type-error-expected-type` answer the operand and `T`.** One table,
`compiler/OperandTypes` (`NUMBER`, `REAL`, `INTEGER`, `RATIONAL` per operator; narrowed to `REAL`
where a complex must be real, two-argument `atan`). The table's other operators are FUNNEL-TYPED ("A
wrong-type argument names its operator" below). A failure outside a named operator reports unnamed.
Pinned by `ci-spec.yaml`'s `non-number-arithmetic-operands-are-catchable` and
`operand-type-errors-name-the-operator-wherever-it-compiles`; the wasm class by
`WasmLispCompilerIntegrationTest.ehANonNumberArithmeticOperandSignalsATypeError`.

**Detection stays at each backend's coercion FUNNEL; the operator is attached ONE LEVEL UP**, because
a funnel (and every shared helper above it -- `_cmpb` serves `< > <= >= = min max`) cannot know which
operator it serves:
- **Interpreter**: the funnels throw an UNNAMED `eval/OperandTypeException`; the `apply` seam names it
  after the built-in (`named`). Built-ins reaching a funnel through `callGlobal`/`applyGlobalFunction`
  (bypassing the seam) name it themselves (`floorFamilyQuotient`, `evalFloorFamilyDivision`,
  `requireRealOperand`, `roundToInteger`, `float`).
- **JVM** (`JvmOperandTypeRuntime`): every funnel throw is `_teRaw(x, kind)`. `Ctx.operator` /
  `Ctx.numOp` / `JvmComplexCompiler.complexOp` hand a wrappable helper
  (`JvmNumericRuntimeBuilder.wrappedDesc`) back as a per-(helper, operator) WRAPPER whose catch-any
  handler throws `_opTypeErr(e, "OP", "TYPE")`. No cost on the normal path. `_teTl` maps each such
  exception to `{datum, type}`, read by `_teSlot`.
- **wasm-GC, EH mode** (`WasmOperandTypes`): `WasmOperandTypes.emitCall` stores the operator's id in
  the operator register (a global) for the call's duration; the `_type_err_*` landings read and clear
  it and look the id up in the operator table. **Trap: the table is a blob placed before any body
  compiles -- a string interned during emission lands after the data segment is fixed and reads back
  as blanks**; so it holds only spelled operators, their rewrites, the `+ - * / = < > <= >=` family and
  `WasmOperandTypes.LOWERED_TO`. Boxing, the fused raw i64 helpers and fdlibm take no register
  (`mayReject`). Non-EH module byte-identical; outside EH mode the landings stay a bare `unreachable`.
- **wasm-GC, one landing body**: `_type_err_int`/`_num`/`_real`/`_list` are stubs handing their kind
  to `_type_err(culprit, kind)` (`buildSharedLandingBody`); its type chain covers only codes a landing
  or row can produce (`Operators.rowCodes`).
- **wasm-GC, the class**: the landings throw a raw-wasm `type-error` instance
  (`WasmOperandTypes.TypeErrorShape`, `WasmRuntimeBuilder.emitConditionThrow`). The GATE is the pad
  (`establishesLandingPad`): `usedLayoutTags` bakes `TYPE-ERROR` on the pad alone -- not on the program
  spelling `type-error`, because `type-of`, `class-of` and a `simple-error` clause observe the class
  without naming it. Without a pad the `(nil . message)` payload. **The type symbols are interned as
  their own names before any body compiles** (`Texts.typeNames`): a symbol's identity is its
  string-table entry, so `(eq (type-error-expected-type e) 'number)` holds only if landing and literal
  share it -- a view into the suffix text would print right and fail `eq`.
- **The operator is the innermost FORM being compiled**, so a lowering that re-emits an operator's
  calls away from its form sets it back (`JvmIntFusionCompiler.numOpFor`, `compareOperator`,
  `WasmOperandTypes.withOperator`, `WasmComparisonCompiler.tryCompileConditionI32`).
- **A call-position rewrite reports under the operator it becomes** (`OperandTypes.REWRITTEN`: `1+` ->
  `+`, `zerop` -> `=`, `evenp` -> `mod`, `logtest` -> `logand`, ...), because the compiled function
  values (`#'1+`) ARE the rewrite (`BuiltinFunctionWrappers`), so the interpreter reports what a call
  does.
- `_int_val`'s limb-tier arm still TRAPS ([wasm-bignum.md](wasm-bignum.md)'s exact-or-trap boundary is
  about values that ARE integers). The `_as_f64` ladder is float-first
  ([wasm-shared-coercion.md](wasm-shared-coercion.md)). `--no-gc` still traps.
- **What still traps on wasm-GC**: the limb-tier boundaries (a limb-sized `random` limit) -- and
  everything outside EH mode.
- **The funnels' reach is wider than arithmetic**: a STORE into a packed float array goes through
  `_dbl`/`_as_f64` and reports under `(SETF AREF)`. Pinned by `JvmFloatArrayTest`'s
  `nonRealStoreIsATypeError`/`singleNonRealStoreIsATypeError` -- the reason to run the WHOLE suite
  after changing a shared runtime helper.
- Known edge: a condition thrown from INSIDE a wasm to-string capture leaves the capture flag set.

## A division by zero signals division-by-zero
**Invariant: a division by an exact zero -- `/`, the two-argument rounding family, `mod`/`rem`, at
every integer tier and over a ratio, `expt` of an exact zero to a negative integer power -- and the
rounding family and `mod`/`rem` by ANY zero (exact or float) over a finite dividend, signal a
CATCHABLE `division-by-zero` (an `arithmetic-error`) reporting `ClosRegistry.DIVISION_BY_ZERO_MESSAGE`
(`Division by zero`), byte-identical on all four backends (wasm-GC: in EH mode).** `/` and `expt`
with a float operand stay IEEE (`(/ 1.5 0)` is infinity, `(expt 0 -1/2)` too); see "Per operator"
below. Pinned by `DivisionByZeroFixture` (`LispEvaluatorTest`/`JvmLispCompilerTest`/
`WasmLispCompilerIntegrationTest#divisionByZeroIsACatchableCondition`, P1 and component),
`ci-spec.yaml`'s `division-by-zero-is-a-catchable-condition` and `clojure-spec.yaml`'s
`catch-takes-a-division-by-zero-as-an-arithmetic-exception`; the uncaught report by
`ehAnUncaughtDivisionByZeroReportsBeforeTrapping`.

- **Interpreter**: `mod`/`rem`'s integer arms check the divisor (`Environment.nonZeroDivisor`), else the
  text is the host's `/ by zero`.
- **JVM**: `_mod`/`_rem` check every arm (`JvmNumericRuntimeBuilder.DivZeroRefs`) and throw `_rat`'s
  `ArithmeticException("Division by zero")`; a fused tree's `floorMod`/`lrem` throws into the bail.
- **wasm-GC**: every zero divisor reaches `_rat_new`, `_big_divrem` (i64 fast path, where
  `rem_s`/`div_s` trap natively, and limb path) or `_fx_mod`/`_fx_rem`, each calling `_div_zero`
  (`FUNC_DIV_ZERO`, `WasmRuntimeBuilder.buildDivZeroBody`): the instance where `usedLayoutTags` baked
  the class, the `(nil . message)` payload elsewhere.
- **The gate is `divZeroLanding`**: EH mode AND a dividing operator spelled (or any name resolvable at
  run time). Otherwise byte-identical.
- **A float rounded by an exact zero**: a FINITE float dividend over an exact zero in the two-argument
  rounding family signals `division-by-zero`. A NaN or infinite dividend keeps the non-finite-rounding
  `simple-error` even over a zero (SBCL too), so the dividend's check comes first on every backend.

### Per operator: where a zero signals and where IEEE answers (decided 2026-10-05)
Oracles: SBCL 2.2.9 default (float traps on) and masked (`sb-int:with-float-traps-masked`), ECL 21.2.1,
ABCL 1.9.0, Clojure 1.12.6, Gauche 0.9.15:

| Form | SBCL default | SBCL masked | ECL | ABCL | Clojure | Gauche | rontolisp |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `(/ 1.5 0)`, `(/ 1.5 0.0)` | DBZ, DBZ | inf, inf | DBZ, DBZ | inf, inf | ##Inf, ##Inf | +inf.0 | inf, inf |
| `(expt 0 -1.5)`, `(expt 0 -1/2)` | DBZ | inf | DBZ | 0 (wrong) | `Math/pow` inf | +inf.0, error | inf |
| `(expt 0.0 -1)` | DBZ | inf | DBZ | 0.0 (wrong) | -- | +inf.0 | inf |
| `(mod 7.5 0)`, `(mod 7.5 0.0)` | DBZ | simple-error | DBZ | fp-overflow | ArithmeticException | error | DBZ |
| `(floor 7.5 0.0)` | DBZ | simple-error | DBZ | fp-overflow | ArithmeticException | error | DBZ |
| `(mod inf 2.0)`, `(mod inf 0)` | simple-error | simple-error | -- | fp-overflow | NumberFormatException | +nan.0 | simple-error |

SBCL's default signal is its FLOAT TRAP, not an exact-zero rule (CLHS 12.1.4.1 contagion makes the
exact zero `0.0` first). No implementation draws an exact-versus-float line for `/`. rontolisp's float
environment is the non-trapping one, so:

- **`/` with a float operand: IEEE, kept.** Making only an exact zero signal would break contagion
  equivalence and put the Clojure and Scheme frontends out of line with their oracles. `(/ 0.0 0)` is
  NaN. Typed double loops keep their bare `ddiv` / `f64.div`.
- **`expt` with a float result: IEEE, kept.** `(expt 0 -1)` (exact result) signals.
- **The rounding family by a zero FLOAT: signals `division-by-zero`** over a finite dividend (any real).
- **`mod`/`rem` by any zero, and of a NaN or infinite operand: signal as `floor`/`truncate` do** (CLHS
  defines them via `floor`/`truncate`; no implementation answers IEEE `fmod`'s NaN). An INFINITE divisor
  keeps its value (`(mod -7.5 inf)` is inf: linalg-simd.md, "mod / rem"). `(mod 0.0 0)` is
  `division-by-zero` where SBCL reports `floating-point-invalid-operation`, a class rontolisp does not
  define. Clojure deviation (`doc/*/clojure/deviations.md`): `(mod ##Inf 2.0)` is an
  `ArithmeticException` here, `NumberFormatException` in the oracle.

Where: `Environment.integerQuotientZero`, `ExactRounding.quotient`; JVM `_frem` (on a NaN result --
moving the throws out of line or testing `|r| > 0` cost more bytes and no time) and `_fdiv`; wasm-GC
`WasmFmodRuntimeBuilder.emitRemainder`, `WasmRatioRuntimeBuilder.emitUndefinedRemainder`,
`_f64_fdiv`. Outside EH mode the wasm remainder TRAPS; `--no-gc` keeps NaN (its own IEEE tower). The
non-finite message is interned LAST and only where `REMAINDER_OPERATORS` are spelled, so a module whose
remainder helper is shaken keeps every other address. Performance unchanged within noise; a tight float
`mod` loop on the JVM pays one NaN compare per call.

### A zero base on the complex `expt` path (decided 2026-10-06)
`exp(w*log z)` multiplies `log 0 = -inf` into NaN parts. SBCL 2.2.9 (default / masked):

| Form | SBCL default | SBCL masked | rontolisp |
| --- | --- | --- | --- |
| `(expt 0 #c(1 1))`, `(expt 0 #c(1/2 1))` | 0 | 0 | 0 |
| `(expt 0 #c(1.0 1.0))`, `(expt 0.0 #c(1 1))`, `(expt #c(0.0 0.0) 2.5)`, `... 2`, `... 1/2` | `#C(0.0 0.0)` | same | same |
| `(expt -0.0 #c(1 1))` / `(expt #c(0.0 -0.0) 2.5)` | `#C(-0.0 -0.0)` / `#C(0.0 -0.0)` | same | `#C(0.0 0.0)` |
| `(expt #c(0.0 0.0) 0)`, `(expt 0 #c(0.0 0.0))` | `#C(1.0 0.0)` | same | same |
| `(expt #c(0.0 0.0) 0.0)` | arguments-out-of-domain | same | `#C(1.0 0.0)` |
| `(expt 0 #c(-1 1))`, `(expt 0 #c(0 1))`, `(expt 0.0 #c(0 1))` | DBZ | `#C(NaN NaN)` | `#C(NaN NaN)` |
| `(expt #c(0.0 0.0) -2)` / `... -2.5` | DBZ | `#C(inf NaN)` | `#C(Infinity NaN)` / `#C(NaN NaN)` |

SBCL's rule for a float or complex power: `(if (and (zerop base) (plusp (realpart power))) (* base
power) (exp (* power (log base))))` behind `(zerop power) -> (1+ (* base power))`; a complex base to a
RATIONAL power is the polar form (`.kb/jvm-complex.md`), which rontolisp takes too, so it never reaches
these arms. Any zero power answers `#C(1.0 0.0)` first. The zero-base arm (`Environment.zeroBasePow`,
`JvmComplexRuntimeBuilder.emitZeroBasePow`, `WasmComplexCompiler.emitZeroBasePow`): a positive real part
answers exact `0` when both operands are exact, else `#C(0.0 0.0)` -- positive zeros for every sign, as
IEEE `pow(±0, y)`; anything else keeps the formula's NaN parts. **A non-positive real part does NOT
signal, an exact zero base included**: the result is a float, so the `(expt 0 -1/2)` row applies. A zero
is decided on the VALUE, not its double (a ratio below the least subnormal is no zero).

Pinned by `ZeroBaseComplexPowerFixture` (`LispEvaluatorTest`/`JvmLispCompilerTest`/
`WasmLispCompilerIntegrationTest#zeroBaseToAComplexPower`, P1 and component) and `ci-spec.yaml`'s
`a-zero-base-to-a-complex-power`.

## A left `ash` that cannot be built signals a simple-error
**Invariant: a left `ash` of a non-zero value whose result the backend cannot build signals a
CATCHABLE `simple-error` reporting `ClosRegistry.ASH_COUNT_TOO_LARGE_MESSAGE_PREFIX` and the count
as `prin1` prints it (`ASH: shift count too large: 1180591620717411303424`), byte-identical on all
four backends (wasm-GC: in EH mode). A zero stays zero at any count; a right shift by any count
answers the sign past the value's width.** Pinned by `AshCountFixture`
(`LispEvaluatorTest`/`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest#...aRunawayAshCountSignalsASimpleError`,
P1 and component) and `ci-spec.yaml`'s `a-runaway-ash-count-signals-a-simple-error`.

SBCL 2.2.9: `(ash 1 (expt 2 40))` is `HEAP-EXHAUSTED-ERROR` (a storage-condition no `error` clause
catches), `(ash 1 (expt 2 70))` a `SIMPLE-ERROR` `can't represent result of left shift`, `(ash 1
2147483647)` computes. The class is SBCL's for the one runaway count it classifies; the text names the
operator and the count. Where the limit lies stays the backend's: interpreter and JVM refuse a result of
`Integer.MAX_VALUE` bits or more (checked before `BigInteger.shiftLeft` allocates -- 256 MB at the
edge), wasm-GC a count past 2^25 (`_big_ash`'s allocation guard), so `(ash 1 40000000)` signals there
and computes elsewhere.

- **JVM**: `_ash` throws a plain `RuntimeException` (`JvmNumericRuntimeBuilder.AshTooLargeRefs`), taken
  by the pad as a `simple-error`. Fused trees bail to `_ash` (`_fxAsh` refuses a count past int range).
- **wasm-GC**: `_big_ash` saturates a limb-tier count to +-2^32 by its sign, answers 0 for a zero value,
  and at the guard throws the message-only payload after clearing the operator register. Gate: EH mode
  with `ash` reachable. **Trap**: the saturation is arithmetic, `(sign | 1) << 32`, because an `if` over
  two `i64.const +-2^62` made `WasmLispCompilerTest`'s
  `theSizeLevelShrinksTheModuleAndTheDefaultLevelIsTheBareFlag` program larger at `--optimize=size` than
  at the default level.

## A wrong-type argument names its operator
**Invariant: outside arithmetic too, a wrong-type argument reports `OP: The value <prin1> is not of
type T` with the type the operator requires, as a catchable `type-error` answering the datum and
`T`, byte-identical on all four backends (wasm-GC: in EH mode).** Covered:

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
| `(close 1)`, `(get-output-stream-string nil)` / `(make-string-input-stream 1)` | `CLOSE:` / `GET-OUTPUT-STREAM-STRING: ... STREAM` / `MAKE-STRING-INPUT-STREAM: ... STRING` |
| `(get-output-stream-string (make-string-input-stream "a"))`, of `*error-output*` | `GET-OUTPUT-STREAM-STRING: ... (AND STRING-STREAM (SATISFIES OUTPUT-STREAM-P))` |
| `(rontolisp:stream-read 1)`, `(rontolisp:stream-close nil)`, `(rontolisp:stream-write 1 "x")` | `STREAM-READ:` / `STREAM-CLOSE:` / `STREAM-WRITE: ... (SATISFIES RONTOLISP:STREAMP)` |

- **`copy-list` of a non-list**: FUNNEL-TYPED (`Environment.requireListArgument`; `%copy-list-runtime`'s
  `(%check-list x 'copy-list)`, the shared instance-free funnel).
- **The stream operators that take a stream, not a designator** (SBCL 2.2.9): `close` and
  `get-output-stream-string` of a non-stream expect `STREAM`, `make-string-input-stream` of a non-string
  `STRING` (its bounds stay `subseq`'s). Compiled: `LispMacroExpander.checkedClose`,
  `expandGetOutputStreamString`, `expandMakeStringInputStream` -- `(streamp v)` / `(stringp v)` then
  `%operand-type-error`. `(close t)` answers `T` on all four; SBCL closes its stdout. Pinned by
  `StreamOperandErrorsFixture` (ci-spec `load-and-stream-operators-signal-their-condition`).
- **A stream operator's argument is a stream** (SBCL 2.2.9: a `type-error` for every row). The write,
  print, read, query and `pprint-*` operators, given a non-stream (struct, instance, integer, string,
  symbol, float, list): `OP: The value X is not of type STREAM` on all four (through
  `print-unreadable-object` reports `WRITE-STRING`, `pprint` `TERPRI`). `t` passes everywhere. nil passes
  where CL's designator rule says so and, unlike SBCL, for `read-byte`, `write-byte`, `read-sequence` and
  `write-sequence` (`.kb/read-load-streams.md`, "Binary streams and binary standard I/O"); the six
  queries refuse it. `make-broadcast-stream` refuses a non-output component.
  - **Compile paths: `compiler.StreamOperandChecks`**, run right after `resolveProgram` (so `foo::read`
    is not `read`), over the program and the wrappers (`FunctionValueWrappers.generate`): wraps a
    non-literal stream argument in `(%check-stream x 'op designator-p)` ->
    `%check-stream-runtime` (`checkStreamRuntimeWrapper`, one `streamp` per program). Not checked: an
    operator the program defines, a `%`-named top-level defun (a caller named that stream), a read of a
    standard stream variable.
  - **A standard stream variable is checked where it is bound or assigned** (SBCL's moment; report
    `*STANDARD-OUTPUT*: The value ...`), so a stream-less call costs nothing. Interpreter:
    `checkStandardStreamValue`.
  - **Interpreter: `LispEvaluator.installStreamOperandChecks`**, outermost over the Gray wraps;
    `evalWriteCharWithGrayDispatch` / `evalSequenceWithGrayDispatch`, `StreamOperandChecks.checkedCall`.
    `gray.lisp`'s helpers hand their built-in the stream AS GIVEN.
  - **A raw integer in a program with no stream value** (`usesStreamValues` false): reports write to a
    raw handle (`.kb/read-load-streams.md`, "A stream is a VALUE, not a handle"), so the helper passes
    an integer that is one; a computed integer equal to a live handle during such a report is the one
    value it cannot refuse.
  - wasm rows (`WasmOperandTypes.Operators.place`) are no named operators to `OperandTypes`, so a funnel
    inside `write-string` reports as it did.
  - Cost: per-site check speed within noise (3,000,000 `write-char`), so it stays at every site. No
    computed stream argument: byte-identical. `(defun f (s) (princ 1 s))`: P1 762 -> 882 B, class 5391
    -> 9156 B; under a `handler-case` P1 3371 -> 8003 B (the EH report reaches the printer).
  - Limits: `format` to a string with no fill pointer is this `type-error` too, where SBCL signals a
    `simple-error` ([format.md](format.md), "A string with a fill pointer"). A synonym stream over a
    non-stream is refused by no check. Closed / wrong direction: "A closed stream and a stream of the
    wrong direction" below.
  - Pinned by ci-spec `stream-operators-refuse-a-non-stream-in-a-program-with-no-stream` and
    `stream-operators-refuse-a-non-stream-past-every-layer` (standalone, all four).
- **`get-output-stream-string` of a stream of another kind** (SBCL 2.2.9): `GET-OUTPUT-STREAM-STRING: The
  value X is not of type (AND STRING-STREAM (SATISFIES OUTPUT-STREAM-P))` (SBCL expects its internal
  `SB-IMPL::STRING-OUTPUT-STREAM`; no standard type names it). A non-stream keeps `STREAM`, checked
  second. A synonym over a string output stream is refused too (SBCL does). The test is the value's KIND
  (`:STRING-OUTPUT`, `makeStreamKindTest`). `%operand-type-error` takes a quoted COMPOUND type: interpreter
  `OperandTypeException.notOfType(datum, type, op)`, JVM `_teOf` then `_opTypeErr`'s compound arm, wasm
  `_type_err_of`. Pinned by `OutputStreamStringKindFixture` (ci-spec
  `get-output-stream-string-refuses-another-kind-of-stream`).
- **`get-output-stream-string` of a CLOSED string output stream**: CLHS leaves it undefined; SBCL answers
  the text (closed by hand) or `MEMORY-FAULT-ERROR` (after `with-output-to-string`). Here the kind
  refusal's `type-error`, via `open-stream-p` of the VALUE (a newer stream reusing the slot changes
  nothing). The text is not kept: that would defeat the slot recycling (`.kb/read-load-streams.md`
  "String streams"). Pinned by `OutputStreamStringKindFixture.CLOSED_PROGRAM` (ci-spec
  `get-output-stream-string-of-a-closed-string-output-stream`); SBCL differs by design.
- **rontolisp's asynchronous stream verbs**: `stream-read`, `stream-close`, `stream-write` of a non-stream
  expect `(SATISFIES RONTOLISP:STREAMP)` (`OperandTypes.ASYNC_STREAM_TYPE`): no type name designates the
  stream (`type-of` answers `T`). Spelled qualified, so the wasm table tests `OperandTypes.spelling`.
  Interpreter `Environment.requireAsyncStream`; JVM `_opTypeErr(_teOf(x, type), "OP", "")`; wasm EH mode
  `WasmOperandTypes.emitStreamCheck`, a module holding no stream `WasmStreamCompiler.compileNoStream`;
  outside EH mode the cast traps ([async-await.md](async-await.md), "A value that is no stream"). Pinned
  by `AsyncStreamOperandFixture` (ci-spec `stream-verbs-signal-a-type-error-over-a-non-stream`).
- **rontolisp's future combinators**: `then`, `then*`, `catch`, `finally` of a non-future expect
  `(SATISFIES RONTOLISP:FUTUREP)` (`OperandTypes.ASYNC_FUTURE_TYPE`), spelled `RONTOLISP:`-qualified so
  CL's `catch` does not match. Pinned by `AsyncCombinatorOperandFixture` ([async-await.md](async-await.md)).
- **rontolisp's thread and socket operators** (interpreter and JVM only): `(SATISFIES
  RONTOLISP:THREADP)` (`OperandTypes.THREAD_TYPE`), `STRING`, a port `(INTEGER 0 65535)` (`PORT_TYPE`),
  `(OR NULL (INTEGER 0))` (`TIMEOUT_TYPE`). **No table rows**: no wasm backend holds a thread and the
  component's socket layer answers nil (`JvmThreadRuntimeBuilder.emitRequireThread`, `_sockStr`/
  `_sockPort`/`_sockMs`). A socket HANDLE of the wrong kind or state stays a `simple-error`: no type
  names "an open connected socket", and the JVM helper sees the resolved handle, not the value passed.
  Pinned by `HandleOperandFixture` ([threads.md](threads.md), [tcp-sockets.md](tcp-sockets.md)).
- **`scale-float`** (SBCL 2.2.9): a non-`FLOAT` first argument, then a non-`INTEGER` second, each a
  `type-error` (`LispMacroExpander.expandScaleFloat`, kind `FLOAT`); the exponent clamps. Pinned by
  `ScaleFloatOperandsFixture` (ci-spec `scale-float-refuses-a-non-float-or-non-integer-argument`). Not
  covered: SBCL's `floating-point-overflow` (`(scale-float 1.5 3000)`); every backend answers infinity.
- **FUNNEL-TYPED operators** (`OperandTypes.FUNNEL_TYPE`: `CAR`, `CDR`, `NTHCDR`, `ENDP`, `AREF`,
  `(SETF AREF)`, `CHAR`, `SCHAR`, `(SETF CHAR)`, `(SETF SCHAR)`, `ROW-MAJOR-AREF`,
  `(SETF ROW-MAJOR-AREF)`, `COPY-LIST`): each funnel checks ONE argument, so its kind IS the type --
  except a to-double funnel (`NUMBER`) there is a packed float store: `REAL`. `%aset` reports as
  `(SETF AREF)`, `nth` as `NTHCDR`, `svref` as `AREF`, `first`/`rest` as `CAR`/`CDR`
  (`OperandTypes.REWRITTEN`). One-argument `gcd`/`lcm` lower to `(gcd x 0)`/`(lcm x 1)`
  (`LispMacroExpander.expandReduction`).
- **List walks**: `nthcdr` signals on a non-list met before the count runs out (`(nthcdr 0 5)` is `5`);
  `nth`/`second`..`tenth` are `(car (nthcdr ...))`. `dolist` checks ONCE, after its loop:
  `(while (consp c) ...) (endp c) result` -- equivalent to CL's per-iteration `endp`, loop unchanged.
  JVM `_nthcdr`, `_endp`. wasm: `WasmEmitHelper.emitListCheck` (`emitListTypeError`; traps outside EH
  rather than answering nil); the `nthcdr` step is `br_on_cast_fail` ([cons-access-runtime.md](cons-access-runtime.md)).
  The operator table is placed in key order (iterating `Map.of`s varies between JVMs).
- **List consumers**: `LENGTH`, `RPLACA`, `RPLACD` FIXED-typed (`SEQUENCE`, `CONS`; wasm reuses
  `_type_err_list`); `LAST` and the six `map*` funnel-typed. Lowerings check via `(%check-list x 'op)`
  (`LispMacroExpander.checkListOf`); `loop`'s `for-in` end test is `(if (consp c) nil (endp c))`.
  JVM `_ckList`, `_ckCons` (replacing the site's `checkcast`). wasm `rplaca`/`rplacd` check in EH mode
  only.
- **The rest of the list consumers**: `REVERSE`, `NREVERSE` fixed-typed `SEQUENCE`; `APPEND`,
  `LIST-LENGTH`, `MEMBER`, `MEMBER-IF`, `ASSOC`, `ASSOC-IF`, `RASSOC`, `RASSOC-IF` funnel-typed. **A walk
  is checked where it ENDS, not where it starts**: the atom it stops at must be nil, so one check covers
  a non-list argument and a dotted tail (datum the tail), and no loop gains a test
  (`LispMacroExpander.listScanEnd`; a literal proper list keeps its bare nil, so `case`'s internal
  `member` pays nothing; `nreverseListForm(list, op)`). The `map*` walks check every cursor after the
  loop; a `mapcan`/`mapcon` PIECE as it arrives (a non-list LAST piece, which CL's `nconc` would answer,
  signals too). `BuiltinFunctionWrappers.mapFamilyWrapper` checks each cursor so `(funcall #'mapcar f l
  5)` reports `MAPCAR`. Interpreter `Environment.requireListEnd`. Not covered: `member-if-not` & co.
  report under the `-if` operator; `nconc` ("A sequence, array or hash-table operand of the wrong
  kind").
- **String accesses**: `char`/`schar` check the subscript, then the string -- that order on every
  backend, because the compiled sites check the subscript ahead of the read. A `setf` place names its
  STORE: `%schar-set`'s optional fourth operand is the quoted place head
  (`LispMacroExpander.scharSetOf`), reported as `(SETF CHAR)`/`(SETF SCHAR)`, `(SETF AREF)` for an
  `aref`/`svref`/`elt` place, `(SETF ROW-MAJOR-AREF)`. Compiled: `expandScharSetFunctional` wraps
  arguments in `(%check-string var 'op)` / `(%check-index i 'op)` -- compile-path-only forms, since
  `%schar-set-runtime` cannot know the store's name. JVM `_charRef`, `wrapForOperator`,
  `_opTypeErr(_teRaw(x, "STRING"), op, FUNNEL_TYPE)`. wasm EH mode only: `_str_char_ref` lands through
  `_type_err(x, STRING)` (`WasmOperandTypes.emitLanding`; no stub), `emitStringpI32`, `emitIndexCheck`;
  `STRING_CHECKED` gates the text.
- **String stores and `row-major-aref`**: `%schar-set` checks string, subscript, VALUE (`CHARACTER`),
  then bounds (`(%check-character c 'op)`; wasm `WasmOperandTypes.CHARACTER_CHECKED`, the one function
  every string store calls; interpreter `storeStringChar`). `row-major-aref` / `%row-major-aset` check
  their subscript as `aref` does (`compileSubscript`).
- **Interpreter**: built-ins throw `OperandTypeException` with the kind; the seam names them.
  `#'first`/`#'rest` of nil and `#'second` past the end answer nil, as the calls do.
- **JVM**: `car`/`cdr` compile to `_car`/`_cdr` (`JvmOperandTypeRuntime`), whole readers that name
  themselves -- one call per site, shrinks zlib. `_ckIdx`, `_ckRat` via the operator's wrapper.
  `_opTypeErr`'s funnel-typed arm reads the kind back off the raw report.
- **What is a cons on the JVM** (`JvmOperandTypeRuntime.ConsShape`): `_car`/`_cdr`/`_endp`/`_ckList`/
  `_ckCons`, `_nthcdr` and the inline map walks (`_isCons`) exclude the other `Object[]`s exactly as
  `consp` does: a ratio, a function reference (`Integer` head), an instance (`String[]` head; tested only
  when `mayUseInstances`) and an async value (`Object[3]` + marker). `instanceof Object[]` alone let
  `(car an-instance)` answer its layout. Speed unchanged within noise. Pins:
  `JvmLispCompilerTest.consAccessorsRejectEveryObjectArrayThatIsNoCons`, ci-spec
  `cons-accessors-reject-object-arrays-that-are-no-cons` and `failing-handler-bind-handler-report`.
- **wasm-GC, EH mode only** (`WasmEmitHelper.checksConsFields`; non-EH byte-identical): an inline
  `car`/`cdr` site is ONE type test, `br_on_cast_fail` then `call _car` on the miss
  ([cons-access-runtime.md](cons-access-runtime.md)); `_car`/`_cdr` are CHECKED there. A subscript goes
  through `call _idx_chk` (+6 B). `compileAset` names itself (a statement-position store reaches it
  without `compileCons`). `denominator`'s `_rat_den` lands a non-rational in `_type_err_int`.
- Cost: one `br_on_cast_fail` per `car`/`cdr` site keeps a tight loop as fast as unchecked (two tests,
  `ref.test` + `ref.cast`, were slower); `rplacd` still pays a `ref.test` (its block would need a
  cast-typed signature). zlib pays about a percent at most; `hello_world`, `pi_approx`, `dom_reactor`
  unchanged; JVM unchanged in speed. A TINY EH module whose only checked site is `length` or `append`
  keeps the landing: the dotted-tail test is one whole-module type facts cannot prove away.
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
CLHS's domain is a COMPOUND type, `(OR (INTEGER 1) (FLOAT (0.0)))`. A ratio limit and a limit `<= 0`
are reported under RANDOM's already-registered `REAL` -- a known simplification, not a claim that a
ratio or a negative number is not real -- rather than teaching the shared operand-type table a compound
representation for this one operator.

- **Interpreter**: `OperandTypeException.of(limit, Kind.REAL).named(RANDOM)`.
- **JVM, the general path** (`JvmNumericRuntimeBuilder.buildRandom`, `_random`): `_teRaw(limit, REAL)`
  (the `<= 0` test via `_dbl` covers Long, Double, BigInteger), renamed by the call site's wrapper.
- **JVM, the two paths that bypass `_random` for performance** (`.kb/random.md`, "Four JVM sites must
  agree on the FORMULA"): `JvmRandomCompiler`'s double-literal fast path checks the sign first and calls
  the wrapped `_random` on the still-boxed value (throws before any draw); `JvmIntFusionCompiler` builds
  no `RandomLeaf` for a constant limit `<= 0` and sends a runtime one through `emitRandomDraw`'s
  trampoline. **Trap**: the fusion planner reaches a `(random ...)` STRUCTURALLY, never through
  `JvmExprCompiler.compileCons`, so the trampoline needs an explicit `ctx.operator = RANDOM` or the
  wrapper resolves unnamed.
- **wasm-GC, EH mode only** (`WasmEmitHelper.checksConsFields`): the float branches check the `f64`
  against `0.0`; the integer branch's `_int_val` goes through `WasmOperandTypes.emitCall` (reports
  `REAL` under RANDOM) and the `i64` limit is checked `<= 0` before the unsigned remainder (which read a
  negative limit as a huge unsigned value).
- Pinned by `ci-spec.yaml`'s `random-limit-domain-violations-signal-a-type-error` and the
  `randomLimitDomainViolationsSignalATypeError` / `ehRandomLimitDomainViolationsSignalATypeError`
  triple (`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`).

## A closed stream and a stream of the wrong direction

**A stream value that is closed, or cannot do what the operator does, is refused at the operator's
stream check, the same way on all four backends.** SBCL 2.2.9: a closed stream is
`sb-int:closed-stream-error` (a `stream-error`); a write to an input stream, a read from an output
stream, a byte operator on a character stream or vice versa is a `simple-type-error` with datum the
stream and type `(satisfies output-stream-p)` / `(satisfies input-stream-p)`.

Here: `rontolisp::closed-stream-error` (a `stream-error`, `stream-error-stream` the stream, report
`#<STREAM> is closed`), and a `type-error` of the same datum and type, its report
`OP: The value #<STREAM> is not of type (SATISFIES OUTPUT-STREAM-P)`. Not SBCL's `simple-type-error`:
`%operand-type-error` is what the sibling refusals signal.

- **What a stream can do is on the VALUE**: `LispLayout.STREAM_ABILITY_CELL`, `Abilities` bits (input,
  output, character, binary), nil where a producer states nothing (a socket, a request body, a Clojure
  standard stream: passes). `Abilities.ofKind`, `emitWrapStream`, `StreamDesignators.streamValue`,
  `OpenModes.abilities` (`LispMacroExpander.fileStreamValue`), `Environment.fileStreamValue`.
- **What an operator needs is one table**: `StreamOperandChecks.neededAbilities`, by the name the check
  reports. 0 asks only that the stream is open (`force-output`, `finish-output`, `clear-output`,
  `clear-input`: SBCL answers nil on an input stream); nil asks nothing (the queries). `fresh-line`,
  `listen` and the two sequence operators need the direction alone: a binary stream stays at column 0
  (SBCL), and a sequence's buffer picks its element where the check cannot see it.
- **Compile paths**: the row is a fourth argument of `%check-stream-runtime`
  (`checkStreamValueRuntimeWrapper`): resolves a synonym, asks `%open-stream-p-raw`, tests the bits with
  `%abilities-hold-p` inline -- `logand` brought the numeric tower, +5.4 KB of every JVM class with a
  string stream. `%closed-stream-error` lowers like `%file-error`.
- **Interpreter**: `LispEvaluator.checkStreamOperand`.
- **A stream-less call**: `(print 1)` reads `*standard-output*`, which may hold a stream closed since it
  was bound. Only in a program that can build a stream value and rebinds the variable (`collectRebound`)
  does such a site check it (`standardStreamOf`).
- Cost: a program with no stream value is byte-identical (the design question). Stream programs grow
  ~0.5-1 KB; time at most ~10% on a wasm file write, so the check stays.
- **A quoted list keeps a wasm module's list printer** (+1.5 KB): without a landing pad the helper's
  refusal is `%error` over a literal (`catchable`).
- **Limits**. A sequence operator refuses only the direction, so a string buffer on a binary file moves
  characters on wasm where the JVM's writer and SBCL refuse it. On `:element-type :default` wasm moves
  bytes (as SBCL); the interpreter and the JVM refuse. A closed component under `make-two-way-stream` /
  `make-broadcast-stream`: writes and reads refuse, `listen`, `unread-char`, `force-output` of the
  composite answer; SBCL's constructors refuse first. `listen` of a file on wasm keeps its
  `simple-error` (`doc/en/reference/functions/listen.md`). `file-position`, `file-length`,
  `stream-element-type` of a closed stream answer (SBCL refuses; `.kb/read-load-streams.md`).
  `(listen string-output-stream)`, `read-char-no-hang`, `unread-char` of an output stream: SBCL's string
  output stream answers nil, here all refuse; `clear-input` excepted.
- Pinned by ci-spec `stream-operators-refuse-a-closed-stream`,
  `stream-operators-refuse-a-stream-of-the-wrong-direction` and the standalone
  `an-uncaught-write-to-a-closed-stream-reports-it`.

## One argument is still checked
**Invariant: a one-argument call compares or folds nothing, but its argument is checked like any
other: `(+ x)`, `(* x)`, `(logand x)`/`logior`/`logxor`/`logeqv`, `(< x)` and the other orderings,
`(= x)`, `(/= x)`, `(min x)`/`(max x)` and every character comparison signal the operator's
catchable `type-error` for a wrong-type argument, byte-identical on all four backends (wasm-GC: EH
mode).**

- **Types**: `+ * =` `NUMBER`; the orderings, `min`, `max` `REAL`; the bitwise family `INTEGER`
  (`logeqv` reports as `LOGXOR`, `/=` as `=`). The twelve character comparisons are FIXED-typed
  `CHARACTER` rows.
- **Interpreter**: `requireNumericOperand`; `charOperandCodes` checks EVERY argument before any pair is
  compared; `%check-character` is a built-in, for the prelude.
- **Compiled numerics**: `LispMacroExpander.checkedOneArgument` -- `(let ((__one x)) (if (realp __one) t
  (%operand-type-error __one '< 'real)))`, nothing for a literal of the type -- from `expandComparison`,
  `expandReduction`, `expandLogEqv`, `expandNumericNotEqual`, `ArithmeticIdentities.oneArgument`. JVM:
  the funnel-typed rename reads a `NUMBER` kind as `REAL`, so `%operand-type-error` hands `_opTypeErr`
  the operator's type. `--no-gc` keeps `(< x)` as `(progn x t)`.
- **Compiled characters**: every argument is evaluated and checked before any pair is compared.
  `char>`, `char>=`, `char/=`, `char-equal` compile as chains of their own, so each reports its own
  name. JVM `_ckChr`; wasm EH `call _chr_code` (`FUNC_CHR_CODE`, `br_on_cast_fail` fast path) -- the size
  of the `ref.cast; struct.get` it replaces. An inline `ref.test` at every site was rejected (the Worker
  spells hundreds of sites).
- The other character built-ins: "A character built-in checks its argument".
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

- **Table**: twelve fixed-typed `CHARACTER` rows, then `DIGIT-CHAR-P` funnel-typed -- a `CHARACTER` row
  would name its radix's `INTEGER` failure `CHARACTER` through the interpreter's seam.
  `OperandTypes.characterOperators()`.
- **Interpreter**: `Environment.requireChar` under its caller's name (also `make-array`'s, `fill`'s,
  `vector-push`'s character checks). `digit-char-p` checks the character before the radix, as compiled.
- **The radix** (`digit-char-p`, and `parse-integer`'s `:radix`): CLHS and SBCL take `(integer 2 36)`
  and refuse anything else -- a non-integer, a bignum, 0, 1, 37 -- with datum the radix and expected
  type `(INTEGER 2 36)`, after the character's own check. Interpreter `Environment.requireRadix`
  (`OperandTypes.RADIX_TYPE`); JVM `_ckRadix` (`JvmOperandTypeRuntime.CK_RADIX`); wasm
  `WasmCharCompiler.emitRadixCheck`. A literal radix inside 2..36 emits no check. `parse-integer` checks
  a non-literal `:radix` ONCE before the bounds and the scan via the probe `(digit-char-p #\0 radix)` --
  SBCL refuses it even over an empty string or with `:junk-allowed` -- so the report's operator is
  `DIGIT-CHAR-P`. Pinned by `RadixRangeFixture` in the three backend suites and ci-spec
  `digit-char-digit-char-p-and-parse-integer-refuse-a-radix-outside-2-to-36`. `digit-char` (prelude,
  `LispPreludeLibrary`) follows SBCL's order: the WEIGHT is typed `UNSIGNED-BYTE` and refused BEFORE the
  radix (a bignum weight answers `NIL`); its report is the prelude's unnamed `type-error`.
- **Compiled**: `char-code`, the folds, `alpha-char-p`, `digit-char-p` and the two case predicates push
  through `pushCheckedCode` (JVM `_ckChr`; wasm `_chr_code` in EH mode, the cast outside). The prelude
  defuns (`alphanumericp`, `both-case-p`, `char-name`, `graphic-char-p`, `standard-char-p`) check first
  with `%check-character`.
- `char-int` is the code point `char-code` answers, on all four (CL leaves no other attribute to differ
  on).
- Pinned by `ci-spec.yaml`'s `character-built-ins-check-their-argument` and the
  `characterBuiltInsCheckTheirArgument` triple (`LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest`).

## A sequence, array or hash-table operand of the wrong kind names its operator
**Invariant: a sequence operator handed a value that is no sequence, an array accessor one that is
no array and a hash-table accessor one that is no hash table report `OP: The value <prin1> is not of
type SEQUENCE|ARRAY|HASH-TABLE` as a catchable `type-error` answering the datum and that type,
byte-identical on all four backends (wasm-GC: in EH mode).**

| Operators | Reported as |
| --- | --- |
| `every` `some` (`notany` `notevery` under them, `OperandTypes.REWRITTEN`), `sort` `stable-sort` (`sort :key` is `stable-sort`), `find` `position` `count` `remove` `delete` `substitute` `nsubstitute` and their `-if`/`-if-not`, `remove-duplicates` `delete-duplicates`, `reduce`, `map` `map-into`, `mismatch` `search`, `fill` `replace` `concatenate`, `subseq` (`copy-seq` under it), `coerce` | their own name, `SEQUENCE`; a rank-2 array is no sequence |
| `aref` `svref` `elt` `#'aref`, `(setf aref)`, `row-major-aref` `(setf row-major-aref)`, `array-dimensions` (`array-rank` `array-dimension` `array-total-size` under it) | own name, `ARRAY` |
| `fill-pointer` `(setf fill-pointer)` (`%set-fill-pointer`) `vector-push` `vector-push-extend` `vector-pop` `array-element-type` `adjustable-array-p` `array-has-fill-pointer-p` `array-displacement` (`%array-disp-target`) `adjust-array`, and `#'` of each | own name, `ARRAY` |
| `gethash` `(setf gethash)` (`%puthash`) `remhash` `clrhash` `maphash` `hash-table-count` `hash-table-size` `hash-table-test` `hash-table-rehash-size` `hash-table-rehash-threshold` | own name, `HASH-TABLE` |

- **Table** (`OperandTypes`): the three families are FUNNEL-typed (`SEQUENCE_OPERATORS`,
  `ARRAY_OPERATORS`, `HASH_TABLE_OPERATORS`, appended so a module spelling none numbers every older
  operator as before). **Trap**: `Kind.typeName()` spells `HASH-TABLE`; use it, not `name()`, wherever a
  kind becomes text.
- **Two internal forms**: `(%operand-type-error x 'op 'kind)` only signals (never answers), and
  `(%check-sequence x 'op)` answers `x` when it is a list or a vector. The expander cannot resolve a name
  (`macro` may not import `compiler`), so a backend does (`OperandTypes.reportedOperator`); on wasm a
  name missing from the table reports unnamed, which is why sites name what the program SPELLS and
  `WasmOperandTypes.LOWERED_TO` covers rewrites.
- **`%check-sequence` is a call, not a test at the site**: `vectorp` is a big inline body. Sites call
  `%check-sequence-runtime (x token)` (`LispMacroExpander.checkSequenceRuntimeWrapper`, gated by
  `programUsesSequenceCheck`, `checkSequenceInline` when absent); the token is an UNSPELLED string on the
  JVM (`JvmCharCompiler.compileCheckSequence`; a quoted symbol kept the named function's `#'` wrapper
  alive), an i31 row on wasm. **A literal sequence argument is not wrapped** (`isLiteralSequence`):
  wrapping `#(...)` hid it from the folds.
- **Where the checks sit**: `seqResultDispatchForm`, `buildPositionScan`, `deleteOrSubstituteDispatch`,
  `seqAsListForm`, `%subseq-runtime`, `%seq-string`, `ConcatenateForms`, the `#'map` `#'every`
  `#'concatenate` `#'map-into` wrappers, the `mismatch`/`search`/`count-if-not` prelude defuns.
  `#'aref`'s wrapper tests `arrayp` before its fold reads the dimensions.
- **Interpreter**: `Environment.seqAsList`, `requireArray`/`requireHashTable` via
  `Environment.accessorTypeError`, `sequenceLength`.
- **JVM**: `_arrayCheckRank`, `_aref1`/`_aset1`, `_arrayDims` (`JvmArrayRuntimeBuilder.emitArrayCheck`);
  `_ckTab` (`JvmHashTableCompiler.emitTableCheck`, in front of the `java:` guard). The `java:` guards
  `_jckarr`/`_jcktab` throw the same type-error ([java-interop.md](java-interop.md)).
- **wasm-GC, EH mode only**: `_arr_check_rank(arr, given)` carries the site's operator id above the rank
  byte (`given | id << 8`, `WasmArrayRuntimeBuilder.buildArrCheckRankBody(int)`) -- a site pays no
  register write, only +1 B. `ANY_RANK` (0xFF) makes it the array check of `row-major-aref`,
  `array-dimensions` and the fused tree's `aref` fallback (`WasmArrayCompiler.emitArrayCheck`/
  `emitRank1Check`). `WasmHashTableCompiler.emitTableCheck`. The landing selects a family's text only when
  the table names an operator of it.
- Cost: about a percent on zlib, a trifle on the JVM class; toys byte-identical. **The shaker corpus
  class's constant pool neared its tripwire** (`JvmDeadMethodEliminationCorpusTest`), which is why the
  ci-spec case holds one row per mechanism.
- **The array-shape accessors** (`OperandTypes.ARRAY_SHAPE_OPERATORS`; `REWRITTEN` maps
  `%set-fill-pointer` -> `(SETF FILL-POINTER)`, `%array-disp-target` -> `ARRAY-DISPLACEMENT`). JVM: one
  shared `_ckArr` (`JvmArrayRuntimeBuilder.CK_ARRAY`) at each site, after the `java:` guard
  (`JvmArrayCompiler.emitArrayOperandCheck`) -- at the site rather than in each helper because the
  predicates must answer nil for a packed array and a string, which a helper testing the general shape
  cannot tell from a non-array. `adjust-array`: `LispMacroExpander.checkedArrayOf`.
- **A symbol is no array**: a symbol shares the string representation on both compiled backends (a bare
  `String`; wasm's string struct without the quote frame), so a representation-only test made `(aref
  'foo 0)` answer `#\O`. The checks test the frame as `stringp` does
  (`JvmArrayRuntimeBuilder.emitStringTest`).
- **A vector without a fill pointer**: `fill-pointer`, `(setf fill-pointer)`, `vector-push`,
  `vector-push-extend`, `vector-pop` handed an ARRAY that has none report `OP: The value X is not of type
  (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))`, the expected type the LIST
  (`OperandTypes.FILL_POINTER_VECTOR_TYPE`) -- SBCL's run-time check's type (its compile-time
  `(AND VECTOR (NOT SIMPLE-ARRAY))` admits an adjustable vector without one). A non-array keeps `ARRAY`,
  checked first (one check per layer). A stored fill pointer outside `[0, dimension]` is `(SETF
  FILL-POINTER): The value V is not of type (INTEGER 0 dim)` (`OperandTypes.fillPointerType`, inclusive:
  SBCL's); an empty pop the simple-error `OperandTypes.VECTOR_POP_EMPTY` (CLHS: "an error of type
  error"). Interpreter `Environment.fillPointerVector`, `OperandTypeException.notOfType`. JVM `_ckFp`
  (`JvmArrayRuntimeBuilder.CK_FILL_POINTER`), `_teOf` (`JvmOperandTypeRuntime.TE_OF`). wasm EH:
  `_fp_hdr(x, id)` (`FUNC_FP_HDR`, `WasmOperandTypes.buildFillPointerCheckBody`) tests the fill-pointer
  shape FIRST so the hot path makes one call (`_arr_check_rank` only on refusal -- calling it up front
  made a push/pop loop pay); lands through `_type_err_of` (`FUNC_TYPE_ERR_OF`,
  `buildCompoundLandingBody`), symbols interned in `Texts.compoundNames`. The empty pop throws a message
  payload (`WasmErrorCompiler.emitThrowPayload`). Pinned by `FillPointerVectorFixture` through
  `fillPointerSurfaceRefusesAVectorWithoutOne` (`LispEvaluatorTest`, `WasmLispCompilerIntegrationTest`)
  and `compileAndRunFillPointerSurfaceRefusesAVectorWithoutOne` (`JvmLispCompilerTest`), and
  `ci-spec.yaml`'s `fill-pointer-surface-refuses-a-vector-without-one`.
- Pinned by `WrongTypeArgumentFixture` through `sequenceAndAccessorOperatorsNameTheirWrongTypeArgument`
  (`LispEvaluatorTest`, `WasmLispCompilerIntegrationTest`) and
  `compileAndRunSequenceAndAccessorOperatorsNameTheirWrongTypeArgument` (`JvmLispCompilerTest`), and
  `ci-spec.yaml`'s `sequence-and-accessor-operators-name-their-wrong-type-argument`.

## An out-of-range subscript is a type-error naming its bound
**Invariant: a subscript outside its dimension reports `OP: The value S is not of type (INTEGER 0
(D))` -- CL's `type-error` for an array index, SBCL's `invalid-array-index-error` -- as a catchable
`type-error` whose `type-error-datum` is the subscript and whose `type-error-expected-type` is the
LIST `(INTEGER 0 (D))`, byte-identical on all four backends (wasm-GC: in EH mode).**

- **Per axis.** Each subscript is checked against ITS OWN dimension, the first failing axis reporting
  (only the row-major total let `(aref m 0 2)` on a 2x2 array answer `m[1][0]`). A row-major access is
  bounded by the total size. A bignum subscript is out of range, not a wrong-type one.
- **Names**: `AREF` (also `svref`, and `elt` of a vector), `(SETF AREF)`, `ROW-MAJOR-AREF`, `(SETF
  ROW-MAJOR-AREF)`. `OperandTypes.indexType` holds the text.
- **Order**: every subscript's type, then a store's value (its evaluation and a packed store's
  coercion), then the bounds: `(setf (aref dv 5) "x")` on a 3-element `double-float` vector reports
  `REAL`, and a value form's side effects run before an out-of-range store fails.
- **Interpreter**: `Environment.subscriptValue` / `inBounds` / `boundedSubscripts`;
  `OperandTypeException.outOfRange`. A string's `aref`, `char`, `schar` and stores report the same text
  -- the decision for `.todo/186`, whose compiled string paths are still unchecked.
- **JVM** (`JvmOperandTypeRuntime`): `_oob(datum, dim)`; `_ckBound(Object, int)` for a boxed subscript
  (a compare), `_ckBoundJ(long, int)` for a raw one through `Objects.checkIndex`, the JIT's range-check
  intrinsic. `emitFlatBound` bounds rank-1 accessors against the total. `_opTypeErr` finds the type
  after `TYPE_INFIX`, not after the last space, and keeps a compound type verbatim. Typed loops
  (`.kb/jvm-typed-loops.md`) use `_ckBoundJ`; the int-fusion aref leaf bails on a long index past int
  range.
- **wasm-GC, EH mode** where the operator table names an access (`Operators.indexed`): a read goes
  through `_idx_ref(array, subscript, op)` INSTEAD of `_idx_chk`; a store checks after its value; at the
  speed levels an inline compare calls it only on a miss. `_idx_chk` answers a limb-tier integer rather
  than trapping. A rank-1 access of an unpinned or `GENERAL` array at the speed levels first asks
  inline for the general-vector fast case, and a miss runs the calls above unchanged
  (`.kb/adjustable-arrays.md`, "What an element access costs").
- **wasm-GC, every mode**: a rank-2+ access checks each axis (`_idx_in`, a trap outside EH mode); a
  `--simd` block's zero padding is checked (`_idx_bound`), where no engine check reaches. **A rank-1
  access outside EH mode keeps the engine's own trap and its module byte for byte**, except that a
  displaced view's out-of-range read still reaches through to its target there.
- Cost: a few percent of artifact on zlib in EH mode; run time low double-digit percent on wasm EH micro
  loops, single-digit on the JVM. **A hand-written compare in the typed loop costs far more than the
  intrinsic**, and the intrinsic in the BOXED `_ckBound` slows a general-vector store (inline depth), so
  the boxed check stays a compare.
- Pinned by `ci-spec.yaml`'s `out-of-range-subscripts-are-type-errors-naming-their-bound` and the
  `standalone:` `uncaught-out-of-range-subscript-report`, and the
  `outOfRangeSubscriptsAreTypeErrorsNamingTheirBound` triple (`LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`).
- **`#'aref` / `#'array-row-major-index` as a FUNCTION VALUE**: the shared Horner fold
  (`BuiltinFunctionWrappers.rowMajorFoldBody`) rejects a wrong subscript COUNT ("A rank mismatch"
  below) and checks each axis inside the loop. Pinned by
  `LispEvaluatorTest#functionValueArefAndArrayRowMajorIndexCheckRankAndBounds`,
  `JvmLispCompilerTest#compileAndRunFunctionValueArefChecksRankAndBounds` and
  `WasmLispCompilerIntegrationTest#compileFunctionValueArefChecksRankAndBounds`.
- **A rank-1 `aref` over another rank, and a fused aref's subscript**: the JVM int-fusion aref leaf
  (`.kb/jvm-int-fusion.md`) guards the rank and its fallback runs the ordinary checks; EH-mode
  `_arr_check_rank` signals a mismatch. Pinned by `ArefRankAndSubscriptChecksFixture`
  (`LispEvaluatorTest`, `JvmLispCompilerTest` both levels, `WasmLispCompilerIntegrationTest` every level
  and component).

## A rank mismatch is a type-error over the array
**Invariant: an `aref`, `(setf aref)`, `#'aref` or `#'array-row-major-index` whose subscript count
is not its array's rank reports `OP: The value A is not of type T` -- a catchable `type-error`
whose datum is the ARRAY and whose expected type `T` is the array of the rank the subscripts
spell (`OperandTypes.rankType`): `VECTOR` for one subscript, `(ARRAY * NIL)` for none,
`(ARRAY * (* *))` for two -- byte-identical on all four backends (wasm-GC: in EH mode). Every
argument form is evaluated first (CL's left-to-right call), then: each subscript's type
(`INTEGER`), the array's (`ARRAY`), its rank, a store's value (a packed store's coercion), the
bounds.**

- **Why a `type-error`, and these types**: SBCL 2.2.9 signals exactly this where it checks, spelling
  one subscript's type `VECTOR`; a `type-error` handler written for SBCL catches the same thing here.
  Rejected: a `simple-error` (a class SBCL never signals for this).
- **Why subscripts before the array** (SBCL checks the array first where it checks at all): on the
  compiled backends each subscript is type-checked where it is evaluated, after the array, so checking
  the rank last needs only the array's reference again; the other order would hold every subscript
  unchecked in a temp.
- **Interpreter**: `Environment.rankedArray` after `subscriptValues`; the per-representation
  `flatIndex` checks stay as internal guards no access reaches.
- **JVM**: array into a temp, subscripts (`_ckIdx`), store value, then the array again through the
  `java:` guard and `_*CheckRank` (`JvmArrayCompiler.emitRankCheck`). One shared tail
  (`JvmArrayRuntimeBuilder.emitRankCheckAndReturn`) throws `_rankErr(arr, n)`. **Hot path**: the same one
  `_*CheckRank` call plus a local store, load and pop; the type is built only on refusal.
- **wasm-GC**: `_arr_check_rank`'s mismatch (EH mode, `WasmArrayRuntimeBuilder.RankNames`) conses the
  type and lands `_type_err_of`. A rank-2+ read evaluates its subscripts ONCE before the packed/general
  split; a rank-0/1 read hands the rank to `_idx_ref` (`given | id << 8`), so its hot path keeps the same
  two calls; `ANY_RANK` skips the nested call. A pinned `(unsigned-byte n)` store with no subscript takes
  the generic path (the kinded arm coerces before the check could run). Outside EH mode the check traps;
  a flat-index computation may trap first -- never a read or store before the check.
- **SBCL 2.2.9 is no oracle for WHEN**: compiled `(aref v x)` over an unknown type and `(funcall #'aref
  m 1)` read a rank-2 array row-major without a rank check. The project keeps its own contract: the rank
  always checked, a non-integer subscript `INTEGER`, subscripts first.
- Pinned by `ArefRankAndSubscriptChecksFixture.ORDER_SOURCE` (the three suites above),
  `*FunctionValueAref*ChecksRankAndBounds` (the `#'` rows) and
  `JvmLispCompilerTest#compileAndRunArefRejectsAWrongSubscriptCount`.

- **`elt` of a LIST outside it**: the list arm is `(car (%elt-cell seq idx))`, the place `(rplaca
  (%elt-cell seq idx) v)`: ONE walk counting cells, so an index it never meets (past the end, negative,
  bignum) reports `ELT: The value I is not of type (INTEGER 0 (LEN))`. Interpreter `%ELT-CELL` ->
  `OperandTypeException.outOfRange`; JVM `_eltCell` (`JvmEltCellRuntimeBuilder`, a method for the
  `_nthcdr` OSR reason); wasm `WasmEltCellCompiler` -> `_idx_in`. `ELT` is a named operator
  (`%ELT-CELL` rewritten to it); a vector's `elt` reports `AREF`, a string's `CHAR`.
  - **Why not a Lisp-level expansion**: an `expandElt` signalling `(error 'type-error ...)` made even
    `(print 42)` fail on wasm (`%OBJ-NEW reached the compiler with no instance type emitted`): `elt` is
    read by bodies the compiler injects unconditionally (`BuiltinFunctionWrappers`' `findFamily`
    wrapper), which `mayCreateInstances`/`conditionNarrowing`/`WasmLispCompiler.usedLayoutTags` cannot
    see. A raw host failure classified lazily needs no layout at the site.
  - **The operator name follows the SPELLED program on wasm**: an `elt` only a lowering introduces
    (`make-array :initial-contents` rank >= 2) reports unnamed where the module never spells `elt`.
  - The prelude `search`/`mismatch` fall back to `(elt seq i)` (`.kb/seq-coerce-runtime.md`), so an
    invalid list bound reaching that read signals.
  - **The walk counts DOWN** from the target, as `_nthcdr` does, checking consness once after the loop.
    Counting UP with an i31 counter made a long `(elt list (mod i 10))` loop markedly slower on the JVM
    and wasm.
  - Pinned by `eltOfAListOutsideItIsATypeErrorNamingItsLength` (`LispEvaluatorTest`,
    `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`) and two lines of `ci-spec.yaml`'s
    `out-of-range-subscripts-are-type-errors-naming-their-bound`.

- **`make-array :initial-contents` whose shape does not match the dimensions**: the compiled fill
  (`LispMacroExpander.lowerInitialContentsMakeArray`) checks each level and signals the interpreter's
  `Environment.fillInitialContents` text, `MAKE-ARRAY :initial-contents dimension D has N elements,
  expected M`, a `simple-error` on all four. A literal rank-0 dims stores the contents as the one
  element (`#0A5`).
  - **Why a plain `error` is safe here**: the lowering runs during code generation, after the layout
    scans; a `simple-error` needs no layout.
  - **One walk of a list, one report per level.** An outer level compares `length` up front (contents
    wrong at two levels report the outer one, the interpreter's order); the leaf level streams its check
    on the cons cursor (`buildInitialContentsLeafFill`) into ONE `error` after the loop. The store is
    spelled once over a three-way read: a store per branch made a large list fill markedly slower on
    wasm.
  - **A run-time `:element-type` fills ONCE** (`lowerRuntimeElementTypeMakeArray` ->
    `initialContentsFill`). **Trap**: a fill per dispatch arm took `#'adjust-array`'s wrapper past
    HotSpot's 8000-bytecode limit (`JvmLibraryMethodSizeTest`: `ADJUST-ARRAY` over ironclad). The literal
    dims form, not the arm's variable, picks the fill's shape.
  - Benchmark trap: the same fills as ONE top-level form run markedly slower on the JVM than inside a
    `defun` -- measure a JVM fill inside a function.
  - Pinned by `compileAndRunMakeArrayInitialContentsChecksItsShape` (`JvmLispCompilerTest`) and
    `makeArrayInitialContentsChecksItsShape` (`WasmLispCompilerIntegrationTest`, P1 and component).
  - **A dims form whose RANK is only known at run time**: `isRankOneDimensionSpec` keeps the rank-1 fill
    for a literal or a `NUMBER_VALUED_OPERATORS` call; anything else takes
    `buildRunTimeRankInitialContentsFill`, a depth-first walk over an explicit stack (no recursive
    helper: the lowering runs during code generation), reporting in the interpreter's recursive order.
    Pinned by `compileAndRunMakeArrayInitialContentsFillsARunTimeRank` (`JvmLispCompilerTest`) and
    `makeArrayInitialContentsFillsARunTimeRank` (`WasmLispCompilerIntegrationTest`, P1 and component).
- **`subseq` outside its sequence** is a `type-error` too, but keeps its own text, `SUBSEQ: invalid
  bounds S, E for KIND of length N`: datum the first bound outside its range, expected type `(INTEGER low
  N)`. Not through `_oob` / `_idx_in`: interpreter `OperandTypeException.reported`, JVM `_subseqBad`,
  wasm-GC `_subseq_bad` ([subseq-runtime.md](subseq-runtime.md), "Bounds check"). A non-integer bound
  takes the same refusal.

## A make-array dimension
**Invariant: every `make-array` dimension is an integer in `[0, array-dimension-limit)` and the
running product of the dimensions is below `array-total-size-limit`, checked before anything is
allocated; one outside reports `MAKE-ARRAY: The value D is not of type (INTEGER 0 (LIMIT))` as a
catchable `type-error` (datum the dimension, or the FIRST partial product at or past the limit, as
SBCL reports it; expected type the list), a dotted dimension list's tail `MAKE-ARRAY: ... is not of
type LIST`, on all four backends (wasm-GC: in EH mode).** LIMIT is the backend's own
(`ClConstants.arraySizeLimit`: 2147483639 interpreter/JVM, 1073741823 wasm), so the text differs in
that number only; SBCL 2.2.9 spells the type `(MOD 4611686018427387901)`, the same set.
- **Interpreter**: `Environment.parseDimensions` (one walk, `long` product). `adjust-array`'s new
  dimensions report under `MAKE-ARRAY`, as the compiled `adjust-array` does.
- **JVM**: `_arrayDimsTotal(dims) -> int` (`JvmArrayRuntimeBuilder.buildDimsTotal`) checks before every
  make parses. The packed float makes are invoked through `MAKE-ARRAY`'s wrapper so a non-real
  `:initial-element` reports `MAKE-ARRAY: ... REAL`.
- **wasm-GC**: `_arr_dims` checks each dimension and the i64 product through `_idx_in`, re-arming the
  operator register before each call (`_idx_in` clears it on success). Outside EH mode the site keeps its
  bytes. A dimension the site proves in range has no check ([array-literals.md](array-literals.md), "What
  a wasm `make-array` site costs").
- `MAKE-ARRAY` is in `OperandTypes.operators()` (funnel-typed) and `WasmOperandTypes.INDEXED`.
- Pinned by `ci-spec.yaml`'s `make-array-dimensions-outside-the-limit-signal-a-type-error` (datum and type
  compared against `array-dimension-limit`, not printed) and the
  `aBignumDimensionOrRandomLimitIsCheckedOrDrawnInFull` triple (`LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`), each pinning its own LIMIT.
- **`make-list`'s length is checked the same way**, under `MAKE-LIST`, before anything is consed.
  Compiled: `LispMacroExpander.expandMakeList` -> `(%check-dimension n 'make-list)`, skipped for a literal
  in `[0, 1073741823)`. Call order is CL's: length, then `:initial-element`, then the check. SBCL 2.2.9
  reports `(UNSIGNED-BYTE 58)`. Pinned by `ci-spec.yaml`'s
  `make-list-length-outside-the-limit-signals-a-type-error` and the
  `aMakeListLengthOutsideTheLimitIsATypeError` triple.

## Argument-shape errors signal a catchable program-error
**Invariant: a keyword the operator does not accept, an odd keyword tail and a non-keyword in
keyword position signal a CATCHABLE `program-error` carrying one text -- `REMOVE expects keyword
arguments :TEST/:TEST-NOT/:KEY/:START/:END/:COUNT/:FROM-END, got: :BOGUS` -- on all four backends,
from a call form and from a first-class call alike, and `:allow-other-keys` suppresses the check per
CLHS 3.4.1.4.1.1.**

- **One validator, `LispMacroExpander.keywordTailProblem(name, parts, start, allowed)`**, over argument
  FORMS (`keywordTailError` / `testKeyKeywordTailError`) and over VALUES
  (`LispEvaluator.requireKeywordTail`, `make-string-output-stream`), so the two cannot disagree. The
  LEFTMOST `:allow-other-keys` pair decides; a non-literal-nil value counts as true (lenient); an odd
  tail is malformed whatever the suppression says.
- **The expander RETURNS the signal instead of throwing.** `programErrorForm(call, text)` is
  `(%program-error "text")` with the call's position (also the arity checks and
  `LambdaLists.unknownKeyCheck`). Interpreter: `evalConsRareOperator` throws `LispEvalException.ofClass`.
  Compiled: `lowerProgramError` -- a typed `%error-cond` behind a landing pad (`Ctx.hasLandingPad` =
  `establishesLandingPad(program)`, a scan for `LANDING_PAD_HEADS`), a plain `(%error text)` otherwise.
  `CompileWarnings.warnStaticProgramError`: `warning: <text>; compiled as a call-time program-error`.
  `PROGRAM-ERROR` is seeded with `format-control`/`format-arguments`.
- **Three gates stand in for the construction, which happens during BODY compilation where no scan sees
  it**: `conditionNarrowing`, `WasmLispCompiler.usedLayoutTags`, and `WasmErrorCompiler.compileCond`
  compiling the message operand when the program has NO report renderer (else the entry pad printed
  `Unhandled condition: ` and nothing else).
- **The interpreter's evaluation seam** (`LispEvaluator.evalCons`'s catch clauses,
  `.kb/interpreter-tail-calls.md`): `IllegalArgumentException` / `IndexOutOfBoundsException` ->
  `program-error`; cast / arithmetic / negative-size -> `rawFailureConditionClass`; an
  `UnsupportedOperationException` -- a rontolisp LIMITATION -- stays raw, since catching a limitation
  would let a program run on past what this implementation cannot do. Per-site rejections not lowered
  are catchable interpreted and a compile-time `error:` compiled -- the CL-conformant asymmetry.
- **Arity**: a surplus argument past `&optional` is `Function expects at most N ...`
  ([lambda-lists.md](lambda-lists.md)). Interpreter counts (`Environment.requireArgCount*`, lambda
  application, macros, built-ins) are `program-error`s spelled through `ClosRegistry.arityMessage`. The
  compiled backends signal the same through a function VALUE ("A wrong argument COUNT" below), `apply`
  included. `-`/`/` have no identity (`compiler/ArithmeticIdentities`), so `(-)`/`(/)` report `- expects
  at least 1 argument, got 0` (`Environment.registerArithmetic`), and the `-`/`/`/`min`/`max` wrappers
  take `(n &rest r)`. First-class twins pinned on `#'member` / `#'find` / `#'position` / `#'remove`
  ([sequence-bounding-keywords.md](sequence-bounding-keywords.md)).

## A wrong argument COUNT through a function value
**Invariant: calling a function VALUE with a count its lambda list cannot take signals a catchable
`program-error` on every backend, spelled by the ONE `ClosRegistry.arityMessage`** -- `Function
expects [at least ]N argument(s), got M`, with the OPERATOR in place of `Function` when the callee
is a built-in's (`CONS expects 2 arguments, got 1`; "Naming the operator" below). A DIRECT call
is judged where the backend compiles it ("A DIRECT call of a built-in" and "A DIRECT call of a
program's own function" below); everything else (`funcall`, `mapcar`, `sort`, a bare `(f x)` whose
head is an expression) arrives at an `_invoke_N` dispatcher. A silent nil there would be the worst
outcome: an ANSI `signals-error ... program-error` row passing interpreted and returning a WRONG
VALUE compiled. Pinned by ci-spec `wrong-arity-funcall-signals-program-error` and
`JvmLispCompilerTest` `compileAndRunWrongArityThroughAFunctionValueSignalsProgramError` /
`...ThroughABuiltinDesignatorNamesTheOperator` (and its wasm and interpreter twins).

- **JVM** (`JvmRuntimeBuilder.ArityReporting`): the arm calls `_arityErr(funcId, got)`, which reads the
  callee's SHAPE (required count doubled, plus one for `&rest`) out of a STRING indexed by funcId and
  throws `new WrongMethodTypeException(_arityMsg(shape, got))`. **A search tree over the ids is the
  wrong structure**: ~15 bytes per callable in ONE method overflowed the signed 16-bit branch offset on
  the cl-postgres corpus. The table is one byte per funcId and three instructions; past a 30,000-funcId
  span it moves to 20,000-id chunks, each with a range check (dropping the table made every value-path
  arity miss answer nil). `JvmHandlerCaseCompiler.emitRawFailureTest` recovers `program-error` from the
  exception's CLASS (`JvmRuntimeBuilder.ARITY_EXCEPTION_CLASS`, the sixth entry in
  `LispMacroExpander.rawFailureConditionClasses()`) -- not from a `Function expects ` text prefix, which
  a user's `(error "Function expects ...")` would match too. Visible cost: an UNCAUGHT report's JVM
  stderr names `java.lang.invoke.WrongMethodTypeException` (the `Unhandled condition:` line is
  unchanged).
- **wasm-GC** (`WasmRuntimeBuilder.ArityReport`): the `br_table`'s labels for ids this arity cannot serve
  point at one ARM PER SHAPE; the arm builds the message from interned pieces (counts by
  `_prin1_to_str` over an `i31`) and throws the `program-error` payload. EH mode behind a handler
  landing pad only; outside it the arm is `unreachable`, byte for byte. The pieces are interned on FIRST
  USE: eager interning costs a module whose dispatchers are all dead an extra data-segment header.
- **`apply` is covered by a COUNT GUARD, not by a dispatch miss**. Neither of its shapes can miss: a
  literal `(apply #'f ... list)` is a direct call, and the SPREAD dispatcher reads parameters with
  car/cdr, so a short list BINDS nil and a long one drops its tail. Both sites call a shared helper that
  measures the LIST against the shape baked at the site: `_arityChk(argList, shape)` (JVM,
  `JvmRuntimeBuilder.buildArityChkBody`, ~6 B per site), `_arity_chk(argList, shape) -> i32` (wasm,
  `WasmRuntimeBuilder.buildArityChkBody`, 7 B). The wasm helper is a CONDITIONAL function index
  (`WasmLispCompiler.arityChkFuncIndex`) decided in the pre-pass, because it shifts `userFuncBase()`.
  Inlining the throw would cost ~100 B per site.
  - **Outside the report gate the wasm guard TRAPS**: `emitsArityChk` is every module that spells
    `apply`, runs the apply runtime or bundles a wide defun, whatever its EH mode; without an
    `ArityReport` it gets `buildArityChkBody(null, ...)`, whose refusals are `unreachable`. A stub
    answering 0 there let `(apply #'one '(1 2))` print `1` and every Clojure function-value call bind
    silently. Pinned by `WasmLispCompilerIntegrationTest.applyWithAWrongCountTrapsWithoutAHandler`.
  - The walk **stops at the first non-cons**; a list ending in anything but nil signals the
    interpreter's `simple-error` `APPLY: last argument must be a list`
    (`ClosRegistry.APPLY_IMPROPER_LIST_MESSAGE`) BEFORE the count is judged.
  - The ALIGNED literal `apply` (a variadic target whose required parameters the leading arguments cover)
    passes its tail through the helper with shape `(0, variadic)` for properness alone, unless provably
    proper (`LispMacroExpander.applyListProvablyProper`). So does an eval-built closure with a `&` marker.
  - `WasmAsyncEmit.freshCtx` must carry `arityChkFuncIndex` (it inherits every `Ctx.Builder` field): it
    builds the SYNCHRONOUS top level too.
- **Naming the operator**. ONE rule on all four: `BuiltinFunctionWrappers.arityOperator(name)` -- the
  callee's name when it is a catalog name, else `Function`. By NAME, because the interpreter's catalog
  lambda and the compilers' wrapper defun are the same function under it. A program's own functions
  keep `Function`: naming them would also have to name the `&optional` surplus and destructuring checks
  the lambda-list desugaring emits without a name ([lambda-lists.md](lambda-lists.md)), or one function
  would report under two names.
  - **Interpreter**: `resolveFunction` names the catalog lambda; `checkArity` asks `arityOperator`.
  - **JVM** (`JvmArityOperators`): the shape carries the operator's 1-based index from bit 16 up; the
    `_arityErr` cell keeps `shape + 1` in the low seven bits and the index in the nine above.
    `buildArityMethods` registers every dispatchable callee before freezing the registry; a late
    registration throws. **Trap**: `_arityMsg` and `_arityChk` must mask the operator bits off before
    reading the required count -- unmasked, `_arityChk` rejected every RIGHT-count `apply` of a built-in
    (`(apply #'cons '(1 2))`), which wrong-count tests alone cannot see.
  - **wasm-GC**: the shape carries `funcId + 1` from bit 16 up (`WasmRuntimeBuilder.arityShape`); one
    shared `_arity_message(shape, got) -> string` (`WasmLispCompiler.emitsArityMessage`) selects the
    operator with `emitCaseSelector`. The named set is only catalog defuns that are DISPATCHABLE or a
    guarded `apply`'s callee (`Ctx.arityNamedCallees`, forwarded by `WasmAsyncEmit.freshCtx` with
    `namesArityOperators`): wasm injects every wrapper and shakes most. Inlining the selection per
    dispatcher was rejected (one copy per arity).
- **A report the function DECLARES**. **Invariant: a function whose body opens with `(declare
  (%arity-report prefix suffix))` (`DeclaredArityReport`; a docstring, other declarations and a defun's
  `block` may come first) reports `prefix + count + suffix` on all four backends, through every path
  above; it wins over a built-in name.** Used by language front ends (Clojure's `Wrong number of args
  (0) passed to: my.app/f`, [clojure-frontend.md](clojure-frontend.md) "Arity refusals"). Pinned by
  `DeclaredArityReportPrograms` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest`) and clojure-spec on all four legs.
  - Direct calls: `DefinedCallArity` takes the callee's report (`JvmArityOperators.declared`,
    `WasmFunctionInfo.declaredArity`, `DeclaredArityReport.ofLambda`).
  - JVM: a declared entry is `'\0' + (char) prefixIndex + suffix`. Past nine bits of operator index a
    second char-per-funcId table (`compileAndRunSixHundredDeclaredReportsEachRefuseInTheirWords`); past a
    30,000-funcId span 20,000-id chunks (`JvmArityTableTest`); past one constant's 60,000 bytes a constant
    per name and `_arityOp`
    (`compileAndRunDeclaredReportsPastTheOperatorNamesBudgetRefuseInTheirWords`) -- **no entry is ever
    dropped for not fitting**.
  - wasm-GC: `ArityReport.declaredFuncIds`. No span cliff: miss arms are one per distinct callee SHAPE,
    and bodies past `DISPATCH_PAGE_BUDGET_BYTES` page into 256-case leaves (`WasmAritySpanTest`,
    `anArityMissPastThirtyThousandFuncIdsStillReportsInWords`). **The one ceiling is NAMING**: a shape
    carries `funcId + 1` in sixteen bits (`ARITY_MAX_NAMED_FUNC_ID` = 65,535); past it the callee reports
    as `Function`, still the same catalogued `program-error` and counts. Kept deliberately: twice the
    clojure-spec corpus (~30,500 dispatchable funcIds), and widening the shape would rework every report
    site for no program in reach. All four gates pinned by `WasmAritySpanTest`. Past
    `MAX_EXTRA_CALL_ARITY` (4) wide funcalls reroute through the spread dispatcher
    (`compileFuncallEitherSideOfTheDerivedArityCeilingAnswersTheSame`).
- **A DIRECT call of a built-in**. **Invariant: `(op args...)` in call position, `op` a
  `BuiltinFunctionWrappers` name, with a count the operator's call shape rules out, evaluates its
  arguments and then signals `program-error` with ONE text on all four backends** (`CAR expects 1
  argument, got 2`, `FLOOR expects at most 2 arguments, got 3`), and each compiled backend warns
  (`warning: ...; compiled as a call-time program-error`), which `--warnings-as-errors` makes a failed
  compile ([compile-warnings.md](compile-warnings.md)). Pinned by ci-spec
  `direct-builtin-call-wrong-count-signals-program-error`, `BuiltinCallArityTest`,
  `LispEvaluatorTest.everyWrappedBuiltinReportsAWrongDirectCountWithItsCallShape` (every catalog name,
  counts 0..4) and ci-spec `a-direct-builtin-call-with-a-wrong-count-signals-at-call-time`.
  - The count is judged ONCE, where each backend decides a form is a built-in call, never per lowering:
    `compiler/BuiltinCallArity.wrongCountSignal` -> `(progn args... (%program-error "msg"))`. JVM / wasm:
    first thing in `Jvm/WasmExprCompiler.compileConsLocated`. Interpreter: `LispEvaluator.wrongCountCall`
    (memoized per site); the fall-through HashMap probe is within noise.
  - **NOT the front end (`CompileFrontend.expand`)**: built-in macros are still unexpanded there, so a
    walker would have to know that `(let ((nth 5)) ...)`'s binding or a `case` key list is no call (~240
    such conses in the repo's Lisp sources). The backends' own dispatch already knows.
  - The SHAPE is the catalog wrapper's lambda list, the operator's standard one (keywords unbounded).
  - A name the program defines itself keeps its own call path (`ctx.userDefunNames`; a `LispLambda`
    global in the interpreter), except a NATIVE built-in's library defun (below).
- **A DIRECT call of a program's own function**. **Invariant: `(f args...)` with `f` a compiled defun,
  or `((lambda ...) args...)`, with a count the lambda list rules out evaluates its arguments and then
  signals the interpreter's `program-error` at run time on all four backends** (`Function expects 2
  arguments, got 1`), with a compile-time warning ([compile-warnings.md](compile-warnings.md)). A
  compile failure was wrong: a wrong call in a branch never taken, or under a `program-error` handler,
  kept the program from compiling. Pinned by ci-spec `direct-defun-call-wrong-count-signals-program-error`,
  `DefinedCallArityTest` and `JvmLispCompilerTest`
  `compileAndRunADirectCallOfAProgramFunctionWithAWrongCountSignalsAtCallTime` with its wasm twin.
  - `compiler/DefinedCallArity.wrongCountSignal`, from `Jvm/WasmFunctionCallCompiler.compileDirectCall`
    and `Jvm/WasmLambdaCompiler.compileCall`.
  - `arityOperator` also maps the `ShadowedBuiltins` dispatcher rename (`%LENGTH--dispatch`) back to the
    built-in, as the interpreter's dispatcher keeps the name: `(length x 2)` says `LENGTH expects 1
    argument, got 2` everywhere.
- **A built-in's function VALUE takes the operator's standard lambda list**. `(funcall #'string-upcase s
  :start 1)`, `(apply #'gethash k h '(d))`, `(funcall #'typep x 'y env)` answer what the call position
  answers, on all four backends. Pinned by ci-spec `builtin-function-values-take-the-standard-lambda-list`,
  `BuiltinCallArityTest`, `LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`
  `...BuiltinFunctionValuesTakeTheStandardLambdaList`.
  - An absent keyword gets the lowering's default; where presence picks the expansion, the wrapper decides
    at run time (`adjust-array`: `LispMacroExpander.ADJUST_CONTENTS_P_KEYWORD`, so the element-copy loop
    is not carried twice).
  - Direct `(string-upcase s :start a :end b)`: `LispMacroExpander.expandBoundedCaseConversion`,
    `Environment.boundedCaseConversion`; `typep` / `upgraded-complex-part-type` with an environment:
    `withEnvironmentEvaluated` evaluates and drops it.
  - **Trap**: `#'file-position` with a computed position reaches `_fileLength`, so
    `filePositionMayNeedLength` must count a `(function file-position)`, or the JVM self-call check
    refuses the class.
  - **The comparisons and the folds take their full lambda list without consing**: comparisons `(a
    &optional b &rest r)`; `+ * gcd lcm logeqv logand logior logxor` `(&optional (a identity) (b
    identity) &rest r)` (one argument is `(op a identity)`, so a non-number is the type error); `min max -
    /` `(n &optional b &rest r)`; `append nconc` `(&optional a b &rest r)`. An optional travels as a
    parameter ([lambda-lists.md](lambda-lists.md), "Optional arguments travel as parameters"), so the
    two-argument call -- a sort predicate's, a `reduce`'s -- conses no rest list. Why: `(&rest r)` consed
    per call, and on wasmtime that cost grows with the live heap (a sort through `(a &rest r)` was many
    times slower).
  - **A wrapper whose body calls a prelude helper**: `#'make-broadcast-stream` (`%make-broadcast-stream`),
    `#'write-to-string` (`%write-to-string-keyed`), `#'read-from-string` (`%read-from-string-full`,
    [read-load-streams.md](read-load-streams.md)). What each helper needs is decided from the program's
    spelling before any wrapper exists, so the prelude splices the helper from that spelling and the
    compile paths inject the full wrapper exactly where the helper is, the narrow one elsewhere
    (`BuiltinFunctionWrappers.HELPER_WRAPPERS`): the two cannot disagree. Pinned by ci-spec
    `helper-wrapped-function-values` and the `HelperWrapperFixture` trio (`...HelperWrappedFunctionValues`).
    Sizes: [gray-streams.md](gray-streams.md), [pretty-printer.md](pretty-printer.md).
  - Size: an eval-using program grows (the eval registry carries every wrapper; `adjust-array` the
    largest); a program taking none as a value is unchanged.
- **A DIRECT call of a native built-in outside the catalog**. **Invariant: a built-in the interpreter
  implements natively that has no `BuiltinFunctionWrappers` entry (`boundp`, `export`,
  `get-universal-time`, `rontolisp:tcp-connect`, `rontolisp:tls-connect`, ...) is judged by the same
  `BuiltinCallArity` check, its shape from `compiler/NativeCallShapes`, and reports under the
  interpreter's name for it** (`TCP-CONNECT expects 2 arguments, got 1`, `TLS-CONNECT expects 2 or 4
  arguments, got 3`). Pinned by ci-spec `direct-native-builtin-call-wrong-count-signals-program-error`,
  `NativeCallArityCompileTest` (every row x every wrong count, through the CLI for the JVM, wasm and the
  component: the warning's literal IS the run-time message),
  `LispEvaluatorTest.everyNativeBuiltinReportsAWrongDirectCountWithItsCallShape`,
  `JvmLispCompilerTest.compileAndRunADirectNativeBuiltinCallWithAWrongCountSignalsAtCallTime` and its
  wasm twin.
  - A row is the counts the INTERPRETER's implementation takes, not the standard lambda list: a wider
    shape would hand a lowering a count it cannot take. Keywords count as unbounded; a PAIRED row
    (`tls-connect`, `tls-upgrade`: 2 or 4; `close`: 1 or 3, `:abort v`) is the one shape a lambda list
    cannot spell. `wrongCountSignal` rejects a 3-argument `close` whose second argument is not the literal
    `:abort` -- a COMPUTED keyword too, which the interpreter would accept when it evaluates to `:abort`.
    Pinned by `MethodedBuiltinTailFixture.CLOSE_PROGRAM`. A range reports `at least` / `at most`.
  - A library defun implementing a native built-in (`BuiltinCallArity.builtinShapedDefuns`, read before
    `LambdaLists.desugarProgram`) lets the check through `Ctx.builtinShapedDefuns`, so its `&optional`
    surplus is the built-in's report; `arityOperator` names native rows too.
  - Residual: a PROGRAM's own redefinition of a native built-in with its exact shape reports under the
    built-in's name compiled, `Function` interpreted (the compile path cannot tell a library splice from
    the program's defun). Undefined consequences for a cl name (CLHS 11.1.2.1.2).
  - Not listed: internal `%` operators, and `rontolisp:http-handler` (a compile-time directive, like
    `wit-export`).
  - **Front-end passes that lower a native call by its shape leave a wrong count alone** for the
    backend's check (`JsonLibrary`, `TlsPemInliner`). `WasmAwaitAnalysis` counts a wrong-count `await` as
    no suspend point -- it compiles to the signal.
  - Known, not fixed: a program whose only package operation is `delete-package`, `shadow`,
    `shadowing-import` or `unintern` fails to compile (a library splice short of `string<` /
    `%baked-packages%`).
- **A built-in's function VALUE with a wrong count**. **Invariant: a wrapped or native built-in reached
  through its function value -- `funcall` / `apply` of `#'op` or `'op`, a mapping function, a methoded
  built-in's dispatcher, a compiled `eval` -- with a count its `BuiltinCallArity` shape rules out signals
  `program-error` with the text its DIRECT call reports, on all four backends** (`(apply #'floor 7 '(2
  3))`: `FLOOR expects at most 2 arguments, got 3`). A program's own function keeps `Function`. Pinned by
  ci-spec `builtin-function-value-wrong-count-reports-the-call-shape`, the
  `BuiltinFunctionValueCountFixture` trio (`...BuiltinFunctionValueReportsAWrongCountWithItsCallShape`),
  the methoded lines of `MethodedBuiltinTailFixture` and
  `LispEvaluatorTest.everyBuiltinFunctionValueReportsAWrongCountWithItsCallShape`.
  - **Interpreter**: `LispEvaluator.apply` turns whatever a body raises into
    `BuiltinCallArity.wrongCountMessage(name, count)` when the shape rules the count out (`wrongCountOr`)
    -- on the way OUT only: judging before the body cost on a built-in-heavy loop. A body that ANSWERS a
    ruled-out count must check first (`Environment.requireCallShape`: `constantp`, `subtypep`, the Gray
    `file-position` layer); the sweep test fails on the next one.
  - **Compiled**: the wrapper's `&optional` surplus check names the operator
    ([lambda-lists.md](lambda-lists.md), "The operator is the function's name"), concatenated in front of
    a shared ` expects at most N arguments, got ` piece (lighter than an opening per operator).
  - Not covered: `rontolisp:await` has no function value in the interpreter.
- **Every count a shape ADMITS reaches the operator**. **Invariant: a direct call or a function-value
  call with a count its `BuiltinCallArity` shape accepts is never refused for its count -- not by the
  interpreter's Java body, not by a compile path's lowering, rewrite or scan.** Pinned by
  `LispEvaluatorTest.everyCountABuiltinCallShapeAdmitsReachesItsBody` and
  `cli/BuiltinCallArityCompileTest` (jvm / wasm / component, compile only; only a COUNT refusal fails
  it; `tls-listen-pem` is out, it reads certificate files at compile time).
  - An argument the operator accepts and IGNORES (`recursive-p` of the read operators, `environment` of
    `subtypep`) goes through `macro/IgnoredArgument` (`drop` keeps it evaluated in place,
    `withoutArgument` for scans). Its consumers are every place that judges one of these calls by its
    count: call dispatch, `GrayStreamsLibrary`, `UnreadCharLibrary`, `WasmSocketsRewrite`, the
    `mayCreateInstances` scan, the runtime-`subtypep` scan and the `subtypep` multiple-value producer. A
    new ignored argument is one `POSITIONS` row. Pinned by the `IgnoredArgumentFixture` trio (also the
    evaluation order).
  - A shape wider than the operator's lambda list is narrowed at its wrapper: `#'find-symbol` is `(n
    &optional (p nil pp))`, `#'list*` is `(a &rest r)` (CL's `object+`).
  - `read-char-no-hang` is in `END_OF_FILE_SITES` and the `#'` list of `constructsInstance` (missing, it
    failed the wasm compile with `no layout was baked for instance type %class-END-OF-FILE`). A computed
    eof-value is evaluated by every read.
- **Inside a compiled `eval`** the same reports hold: the runtime evaluates every argument of a
  registered function and the spread case judges the count, `apply` is a catalog wrapper, an eval-built
  closure without a `&` marker is checked, and `_eval`'s inline operators report too
  ([eval-runtime.md](eval-runtime.md), "Argument counts"). `eval` itself has no wrapper: the JVM
  registers it by name (`JvmArityOperators.namedShape`), wasm gives it an id one past the largest named
  funcId that only `_arity_message` reads (`ArityReport.unbackedOperators`; `names(id)` stays false, so
  no dispatcher can name a real callee by that id).
- **Sizes**: a program with no indirect call is byte-identical on both backends. The JVM pays on any
  handler-case (the sixth classification arm) and any dispatcher (`_arityMsg` + `_arityErr` + table);
  wasm pays nothing without a landing pad. The `apply` guard costs bytes per site, an order of magnitude
  under the per-case cost feared ([wasm-function-body-size.md](wasm-function-body-size.md)). Naming the
  operator costs a little per module with a report, percent-level for an eval-using program.

## Applying a value that names no function
**Invariant: applying a non-designator (`(funcall 3 1)`, a Scheme `(h 1)` over a number) signals a
catchable `type-error` reporting `Not a function: <prin1>`
(`ClosRegistry.NOT_A_FUNCTION_MESSAGE_PREFIX`), and a SYMBOL no function answers -- NIL included --
an `undefined-function` reporting `The function NAME is undefined`, on all four backends, through
`funcall`, `apply`, `mapcar` and every other dispatcher route.** Pinned by ci-spec
`applying-a-non-function-signals-its-condition` + standalone `uncaught-non-function-report`,
`LispEvaluatorTest` `applyingANonFunctionSignalsATypeErrorAndNilAnUndefinedFunction`,
`JvmLispCompilerTest` `compileAndRunApplyingANonFunctionSignalsTheInterpretersCondition`,
`WasmLispCompilerIntegrationTest` `ehApplyingANonFunctionSignalsTheInterpretersCondition`.

- **Detected where each backend already dispatches on the callee's representation, never in front of
  a call.** Interpreter: `LispEvaluator.apply`'s fall-through. JVM: segment 0 of every `_invoke_N` /
  `_invoke_v` dispatcher uses `instanceof` twins (the JIT folds them into the casts that follow) and
  sends a miss to `_notFn(Object) -> RuntimeException` (`JvmRuntimeBuilder.buildNotFnBody`); the pad
  recovers `type-error` from the prefix. wasm-GC: `emitDispatchPrologue` in EH mode tests the CLOSURE
  first (testing it after the symbol -- three checks -- measured slower); the failure arms sit AFTER the
  dispatch (`emitDispatchEpilogue`). `_apply` hands every value it cannot call to the spread dispatcher.
  A quote-framed string shares `TYPE_STRING` with a symbol, so the undefined arm re-tests the first byte.
- **Class on wasm rides on a layout the module already has**: a program naming `type-error` /
  `undefined-function` has it baked (`usedLayoutTags`) and gets the typed instance
  (`WasmRuntimeBuilder.NotFunctionReport`, `conditionInstance`); otherwise nothing can tell it from the
  message-only payload, so nothing new is baked.
- **Outside EH mode wasm is byte-identical** and still traps. `--no-gc` unaffected.
- **One text in every mode**: the Scheme REPL prints the condition's text (`.kb/scheme-frontend.md`).
- **Scheme never reaches this path for its own values**: a lowered combination whose operator is not a
  known procedure goes through `rontolisp::%scheme-ensure-procedure` (`The object is not applicable:
  <scheme-print>` via `%scheme-error-message`), so a Scheme `(h 1)` over a number reports `The object is
  not applicable: 3`, a CL `(funcall 3 1)` `Not a function: 3` -- each front end in its own terms; no
  backend learns a Scheme name.
- Cost: the JVM's monomorphic `funcall` loop folds entirely; other legs within noise. A program with no
  indirect call is byte-identical.

## Out of scope (still)
A program's interactive debugger (`break`, `*debugger-hook*`; only `rontolisp --swank` has one,
SLIME's, [swank.md](swank.md)), condition-restart association, a `store-value` restart for
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
  `standalone:` list. Their presence puts the concatenated program in EH mode; wasmtime
  47+ enables exception handling by default, so `CiSpecE2eTest.runBackend` passes no
  `-W` flag to either invocation.
  `ParseNumberE2eTest` and `JzonE2eTest` cover the report text and the runtime dispatch.
