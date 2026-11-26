# Multiple values -- syntactic tier

The `multiple-value-bind`-over-`floor`/`gethash` idioms real CL code uses, WITHOUT a runtime
multiple-value representation. A `%mv-spill` global carries the cases the syntactic tier cannot.

**Invariant: the channel is EXACT -- after any form a consumer can read it behind, it holds that
form's own extra values and nothing older.** nil is one value, a fresh list the values after the
primary, `t` (`LispMacroExpander.MV_ZERO_VALUES`) no value at all. The interpreter keeps it as a
value-count register (every primitive step clears), the compile paths by settling every function
tail ("A tail settles the channel"); the four backends agree line for line with SBCL on the
`multiple-values-single-value-contexts` ci-spec case. **The channel is one per THREAD** on the
interpreter and the JVM ("One register per thread"): exactness is a per-thread property.

## What ships
- `values` is a CL **function**, NOT in `expandBuiltinMacro`, so `macroexpand-1` leaves it alone as
  in CL. `multiple-value-call` as a macro deviates from CL's special operator.
- Secondary values for the floor family, `gethash`, `subtypep` and `read-line`: on the compile
  paths ONLY inside consumers and through the walks below; on the interpreter every call publishes.
  Each as a FUNCTION object publishes too. Two-argument `(floor a b)` elsewhere lowers to `(floor
  (/ a b))`, which no backend divides through a ratio when both are integers (`.kb/linalg-simd.md`,
  "Two integers").

## The lowering (`LispMacroExpander`)
`lowerMvProducer` binds ordered `__mv<id>` temps as **nested single-binding lets** so evaluation
order holds on every backend. `gethash`'s missing-key default is the internal symbol
`%GETHASH-ABSENT` (+ `(eq v sentinel)`), NOT a `(gensym)`: a gensym advanced the program's gensym
counter on every lookup (the corpus printed `#:G2` for `#:G1`) and cost more than the lookup.
`subtypep` ([[declarations-type-checks]]) and one-argument `read-from-string`
([[read-load-streams]]; more arguments go to `%read-from-string-full`, whose tail answers both) are
recognized too. A form NOT recognized goes through `spillEscapingMvProducers` FIRST, so a recognized
producer in the TAIL of a wrapper `(let ...)`/`(progn ...)` publishes -- else the tier boundary is
visible through a wrapper. `multiple-value-call`: fn temp FIRST, then producers' temps, one direct
`funcall` (static count).

## The `%mv-spill` runtime channel
`values` PUBLISHES its extras as it returns its primary; zero values are represented
(`MV_ZERO_VALUES`), and `values-list` of nil says the same.
- A consumer whose producer may pass values along clears the spill, evaluates the SETTLED form,
  then snapshots it with `(prog1 %mv-spill (setq %mv-spill nil))` -- the snapshot CLEARS, so an
  enclosing consumer cannot re-read what an inner one took. A producer the tail walk proves
  single-valued takes the temp-only path (extra variables nil).
- The zero-values marker is a non-list, so every read normalizes (`allValues`, `restAsList`).
  `multiple-value-bind` cannot tell zero from one, which is CL.
- `multiple-value-call` with any spill producer spreads at runtime via `apply`, so it forces the
  eval runtime (`usesEval`).
- Interpreter: a FIELD of the global `Environment` (`mvSpill`), not a binding: it is written on
  every primitive step, which a map put could not afford. Compilers call
  `LispMacroExpander.injectMvSpillGlobal` AFTER lambda-list desugaring, gated on a name scan.
  Scalar `--no-gc` keeps `expandValuesPrimary` (no reference globals).
- **A program WITHOUT the global still compiles every expansion that names the channel**: a read is
  nil, a `setq` keeps only the value, `FreeVarAnalyzer` never counts it free. Sound because the gate
  puts every CONSUMER in the scan. Such expansions exist: `restart-case`/`with-simple-restart` carry
  the primary form's values through `multiple-value-list`/`values-list`. Pinned by ci-spec
  standalone `restart-case-in-a-lambda-without-the-spill-global`.
- `parse-integer`, `values-list` and `multiple-value-setq` are in the `injectMvSpillGlobal` scan
  (ci-spec standalone `multiple-value-setq-as-the-only-multiple-value-operator`). The `#'values`
  wrapper is `(values-list r)`, so `(apply #'values '())` answers as `(values)` on every backend.

## An unwind-protect cleanup may not clobber the channel
**Invariant: a cleanup's values are DISCARDED and the protected form's value COUNT is restored.**
Save before the cleanup sequence, write back after, on every exit path (`runUnwindCleanups`,
`JvmUnwindProtectCompiler.compileCleanups`, `WasmUnwindProtectCompiler.compileCleanups` -- via a
LOCAL, not the operand stack: landing pads run with the caught `exnref` beneath them).
- The save lives in the SHARED cleanup emitter, so every exit path inherits it, including the copies
  a `return-from`/`go` inlines at an escape site.
- Byte identity for unaffected programs: the `injectMvSpillGlobal` gate, and the compiler's own
  `(%hc-depth-dec)` cleanup (`internalOnly`) is excluded.

## `handler-case`'s `:no-error` clause is a multiple-value consumer
The variable list binds the protected form's full VALUES list, nil-padded and surplus dropped, as
`multiple-value-bind` does; the protected form goes through `spillEscapingMvProducers`.
- REQUIRED-ONLY variables (`&optional`/`&rest`/`&key` signal on parse). SBCL lowers `:no-error`
  through `multiple-value-call`, which requires exact arity; rontolisp nil-pads -- a deliberate
  divergence, the same `multiple-value-bind` makes.
- `usesMvOperator` includes `HANDLER_CASE` with `:no-error`, so a `handler-case` without it stays
  byte-identical and one with it in an otherwise mv-free program still publishes.
- `LispEvaluator.evalHandlerCase`, `JvmHandlerCaseCompiler` / `WasmHandlerCaseCompiler
  .compileNoErrorClauseBody` (gated on the spill global's existence).

## `read-line`'s missing-newline-p
**Invariant: `read-line` answers `(values line missing-newline-p)` on all four backends, through a
consumer, a function tail, `#'read-line`, every stream kind, a parked `unread-char` and a Gray
`stream-read-line`; SBCL 2.2.9's answers.**
- A syntactic producer: `lowerReadLine` does ONE read, `(%read-line-pair stream)` -> `(line .
  missing-newline-p)` or nil at end. A call outside a consumer or a tail keeps the one-value
  built-in: only a site that can observe the value pays.
- The pair reads a character at a time (`BufferedReader.readLine` drops the terminator). WASM's
  shared `_read_line` stores its flag in `READ_LINE_END_ADDR`, an alias of `TIME_SCRATCH_ADDR`,
  sound because the expansion reads it straight after the call in a single-threaded module (the low
  256 bytes hold no free word). Line terminators: [[read-load-streams]], "`read-line`, `read-char`,
  `peek-char`".
- Gates: JVM `_readLinePair` exists where `%READ-LINE-PAIR` is spelled OR (the spill global exists
  AND the program names `read-line`). The first clause is load-bearing: a settled tail has already
  REPLACED its `read-line` by the pair, so a program whose only `read-line` is a tail names none.
  `MutableStringProducers` lists the pair for the same reason.
- Library wrappers carry the value through quiet operators (next section).
- A Gray method's `("" t)` (SBCL's end of file) needs the second value in the dispatch, so
  `GrayStreamsLibrary` splices it with a REAL `multiple-value-list` when the program defines its own
  `stream-read-line` method (else such a method loops forever on `""`).
- Pinned by `ReadLineValuesFixture` in the three backend suites (P1 and component); on a socket
  by `TcpOutputBufferFixture` on all four ([tcp-sockets.md](tcp-sockets.md)).

## Quiet operators in library source
`(%quiet-values form...)` / `(%quiet-multiple-value-list form)`: for library source whose extra
values only a program's OWN consumer can observe. Neither is in the `usesMvOperator` scan;
`injectMvSpillGlobal` spells them as the real operators where the program has the channel, as
`prog1` / `list` where it does not, so splicing a library never makes a program a multiple-value
one. Calls, not binding forms, so earlier passes read them right either way.

## A syntactic producer's tail escapes through the spill
**Invariant: the tier boundary is not observable through a function return.** A recognized producer
in a value-escaping position publishes (`spillEscapingMvProducers`), so `(defun f (h) (gethash "K"
h))` answers two values however many calls away, including through a `defmethod`. Wiring is
SELECTIVE -- an unconditional spill would tax the hottest built-ins on every call:
- TAIL positions only (through `progn`, the `let` family, `if`/`when`/`unless`, the last form of
  `and`/`or`, clause bodies -- a bodyless `(test)` clause keeps one value --, blocks, an
  `unwind-protect` protected form, `return`/`return-from`, `the`).
- The publish is a `(values primary secondary)` CALL, not `(progn (setq %mv-spill ...) primary)`: on
  the interpreter the step after a publish clears the channel, so the publish must be the last step.
- Applied to every top-level `defun` (gated on `usesMv`; interpreter ungated; `--no-gc` never) and
  to lambda and `flet`/`labels` bodies at any depth (`settleLambdaTails`; the interpreter memoizes
  by the body's cons identity, `lambdaTailSettlements`, `.kb/clos.md` "Interpreter, the call side").
  It runs BEFORE Pass 2 on purpose: after `expandFloorFamilyDivisor` the body's `(floor (/ a b))`
  has a different remainder. A built-in WRAPPER's tail stays one value, the floor family's
  excepted. `multiple-value-prog1` needs no walk.
- **A `return`, `return-from` or `throw` anywhere** (`settleExits`, compile paths): its value leaves
  however deep the exit sits (trivia's `match` returns from inside a `tagbody`; serapeum's `href`
  is an early return). Quoted data is skipped.

## The floor family as a function object
**Invariant: `#'floor`/`#'ceiling`/`#'round`/`#'truncate` and the `f` twins take the optional
divisor and answer both values, through `funcall`, `apply`, a variable, `mapcar` and a function
return, on every backend.**
- Interpreter: `Environment.floorFamilyValues` is `passesValues` and publishes the remainder
  itself, so a call in any position comes here (a producer a macro expands to has no lowering). The
  exception is `(floor (/ a b))`, the lowering's shape, which rounds the exact quotient and answers
  one value.
- Compile paths: the wrapper is `unaryOptionalSecond`, and `settleWrapperLambdas` runs the `PUBLISH`
  walk before the `CLEAR` one, gated on the spill global. A LITERAL `(funcall #'floor ...)` direct-
  calls that wrapper, so `settleTail` classifies it as passing values.
- No divisor: the remainder is `(- x q)` (`floorFamilyRemainder`), one correctly rounded subtraction
  since `q` converts exactly, with CL's zero signs (`(truncate -0.0)` is `0`, `-0.0`). It replaced
  `mod`/`rem` by 1, which dominated the cost. A program referencing neither is byte-identical.

## The other producers as function objects
**Invariant: `#'gethash`, `#'find-symbol`, `#'intern`, `#'subtypep`, `#'read-from-string` and
`#'array-displacement` answer both values through `funcall`, `apply`, a variable, `mapcar` and a
function return, on every backend.** Pinned by the `multiple-value-builtins-function-object` ci-spec
case and its three backend-test copies.
- Interpreter: `LispEvaluator.installValuePublishingFunctions` rebinds each to a `passesValues`
  function.
- Compile paths: the wrapper publishes and takes the full lambda list ONLY in a program that names
  the operator as a designator (`BuiltinFunctionWrappers.designatedValueProducers`) or runs forms at
  run time. Unlike the floor family's, these wrappers are reachable in programs that never take them
  as values (the JVM keeps every wrapper under an eval runtime; a shaken wrapper's literals still
  move the WASM string table), so an ungated change touched every program that interns or reads.
  Given up: the second value of a wrapper reached only through a run-time designator.
- Computed arguments carry the computed-name deviations (`.kb/symbol-runtime-api.md`, "Computed
  `find-symbol`"): `(funcall #'find-symbol "CAR")` is `(CAR :INTERNAL)` compiled, `:INHERITED` in
  SBCL and the interpreter.

## A tail settles the channel (compile paths)
**Invariant: once a function body's tail has run, the channel holds that body's extra values. The
channel is read only by a consumer that cleared it first and then ran a settled form, so a publish
in a non-tail position never reaches a consumer.** `LispMacroExpander.settleTail` classifies a tail:
- **Passes values along, untouched**: a `values` tail; a call of any operator that is not a `cl`
  function name; `funcall`/`apply`/`eval`/`multiple-value-call`/`multiple-value-prog1`; the `cl`
  functions answering several values here (`passesMultipleValues`, pinned against the prelude
  source by `LispPreludeLibraryTest`). A LITERAL designator is classified by its name.
  `rontolisp:await` too (`.kb/async-await.md`, "Multiple values").
- **One value, cleared**: atoms and constructing forms get `clearBefore`; any other `cl` function
  call, the assignment macros (except a `(setf (values ...) ...)` place), the definers, `format`/
  `error`/..., `rontolisp::%async-run` get `clearAfter` (an argument or a callback may have
  published), or `clearBefore` when the operator is in `QUIET_OPERATORS` and every argument is quiet
  -- a structure's reader and predicate bodies among them (as internal helpers they passed an
  argument's values on; ci-spec
  `a-structure-reader-or-predicate-answers-one-value-from-a-function-tail`). `while`, `tagbody` and
  a result-less `dotimes` whose body names no nil-block exit get `clearAfterStatement` (an exit out
  skips the clear and keeps its own values). **A `dotimes` whose body may `return` gets a cleared nil
  as its RESULT form instead**: its own nil block takes the `return`, so a clear behind the whole
  loop threw the returned value away (ci-spec
  `a-return-out-of-a-dotimes-answers-its-values-from-a-function-tail`). The test is a lexical scan
  (`mentionsNilBlockExit`).
- **Tails of their own, settled each**: bodies of the binding/block forms -- a `block` or `catch` is
  never single for sure, its value may come through an exit fired anywhere inside (ironclad's
  `loop ... finally (return (values a b c))`) --, `if` (a missing else is a cleared nil), the
  protected form, exit values, `handler-case` parts, `typecase` clauses. `cond`/`case`/`and`/`or`/
  `when`/`dolist`/`loop`/`prog1`/... are EXPANDED first -- only the expansion shows a `cond`'s
  fall-through nil or a `prog1`'s temporary -- while `dotimes` keeps its form (the wasm counted loop
  and the JVM typed loop read it).
- Where it runs: every top-level `defun` (`settleDefunTails`, gated on `usesMv` -- a consumer-free
  program stays byte-identical); every `lambda` as the backends compile it; the built-in wrappers
  (`settleWrapperLambdas`); an int-fusion-inlined local function or defun gets its clear at the
  fused call site (`clearedForm`, [wasm-int-fusion.md](wasm-int-fusion.md)); every consumer's
  producer; a `:no-error` protected form. `TailMode`: `PUBLISH` (the interpreter; a macro form keeps
  its shape because the macro-time purity walks read stored bodies), `PUBLISH_AND_CLEAR`, `CLEAR`.
- Every rebuild carries the original's source position (`SourceProvenance.inherit`,
  [[source-positions]] "Half 2"; `RontoLispCliTest.aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice`).
- Residue, by construction: a program REDEFINING a `cl` function to answer several values is
  classified by the name (one value); a `macrolet` expansion is produced after the walk; an operator
  the walk does not know passes values along -- the leak-safe direction (a stale publish may travel,
  never a value lost).
- Cost: a program without a multiple-value operator is byte-identical. With one, the settled
  `let`-temporary tail costs nothing measurable on the JVM, and on wasm the same as a bare tail once
  the generic helpers' i31 head landed (`.kb/wasm-int-fusion.md`).

## The interpreter's value-count register
**Invariant: on the interpreter the channel reflects the LAST value-producing step, exactly -- a
native implementation's value-count register.** Every primitive step that is not a publish and not
a call of user code clears it (`Environment.clearSpill`, `LispEvaluator.singleValue`): an atom (a
symbol read reads the channel first, which is how the snapshot works); a closed argument list; a
built-in's return unless it `passesValues`; a constructing special form (`setq` answers ONE value
however many its value form produced).
- **A new `LispFunction` that publishes or passes values must say so** (`passesValues`), or its
  extra values die at its own return. A callback the built-in ran may have published; the built-in's
  answer is one value regardless.
- NOT `await`: it publishes the values its future settled with (`.kb/async-await.md`, "Multiple
  values"); `%async-run` answers one value, its future.
- A form that merely passes a sub-form's value on leaves the channel to that sub-form. That includes
  the last form of an `or` on all four backends (`LispMacroExpander.expandOr`): `(or nil (values 1
  2))` answers both values as CLHS 7.4 says.

## Through `eval`
The interpreter's `eval` settles its form's tail as a function return does (`.kb/eval-runtime.md`,
`evalDatum`). `_eval` keeps the channel exact itself (`.kb/eval-runtime.md`, "The built-in macros",
Values); its consumers are compiled helpers over a closure (`%eval-values-of`). Pinned by ci-spec
`eval-binds-specials-by-name-and-answers-its-own-values`.

## One register per thread
**Invariant: each thread that runs Lisp code has its own channel** on the two multi-threaded
backends; the WASM tiers are single-threaded (the component's tasks suspend only at an `await`,
which publishes afresh).
- Why: async bodies, `make-thread` threads and served requests run in parallel with `main`, and
  every primitive step (interpreter) or function tail (JVM) writes the channel; through ONE register
  a sibling cleared the values between a callee's publish and its consumer's read (8 threads x
  20,000 `multiple-value-bind`s failed on every run), and an async body's values were lost between
  its tail and its future.
- Interpreter: `ValueCountRegister` -- the creating thread keeps a plain field behind one
  `Thread.currentThread()` compare; every other thread a `ThreadLocal`.
- JVM: `JvmMvChannel`, under `lispOnOtherThreads`, the one gate the special-binding representation
  also follows (`.kb/dynamic-special-variables.md`, "One thread"; it includes every thread a HOST
  calls in on -- a jvm-export, the java: bridge, callbacks). The OWNER keeps its register in the
  middle slot of `_mvBox` (a 64-slot `Object[]`), every other thread the `_mvTl` ThreadLocal. The
  owner is the first thread into `main`'s prologue or a jvm-export wrapper, claimed once
  (`_mvClaim`) and never moved: a moved claim strands the values the old owner just published. A
  war's handler claims nothing (each request is a fresh virtual thread, so the first would hold the
  claim dead). Every emission goes through `ctx.mvChannel`, never `globalFields` directly. Any other
  program keeps the plain static field, byte-identical. Pinned by
  `JvmExportTest#anExportCalledOnSeveralThreadsKeepsEachCallsMultipleValues`.
- Why a box and not the static field: every non-owner reads `_mvOwner` on every access, and the
  owner writing a register among the class's statics invalidated that cache line under them.
- Why an owner fast path: a `ThreadLocal.set` on EVERY write doubles fib 34's time.

## The REPL echo is a consumer
`LispEvaluator.evalValues(form)` is the ONLY multiple-value entry point outside the macro expander.
A SYNTACTIC producer is echoed wrapped in `multiple-value-list`; anything else is evaluated
UNWRAPPED with the spill cleared first and read back after, so a top-level `defun`/`in-package`
still evaluates at top level. Resolution runs ONCE: package resolution is not idempotent under a
`:shadow` package. `ReplBuffer.eval` echoes EVERY form (as SBCL does); `PlaygroundRepl.eval` the
LAST. Remaining difference from SBCL 2.2.9: `print` omits CL's leading newline / trailing space.

## Documented deviations
- A program's own `defun` of a `cl` function name answering several values is one value on the
  compile paths ("A tail settles the channel", residue).
- `multiple-value-call` with a builtin `#'name` inherits the wrapper arity: `+`/`-`/`*`/`/`/`list`/
  `min`/`max` are variadic, every other multi-arg builtin fixed (a mismatch yields nil on JVM, traps
  on WASM).

## Wiring points
`FreeVarAnalyzer` both walks (expand before walking); `UserMacroExpander.expandAll` +
`LispMacroExpander.rewriteLocalCalls` keep the mv-bind variable list verbatim;
`SchemeLowering.exitGuardValue` and `SchemeValueCount` (a Scheme loop's leaf that may answer other
than one value leaves through a `return-from`).

## Tests
`ReadLineValuesFixture`; `ThreadTest`/`JvmThreadTest.eachThreadHasItsOwnMultipleValueChannel` and
the `AwaitValuesMatrix.CONCURRENT_PROGRAM` tests; `JvmExportTest#theFirstThreadToCallAnExportOwnsTheChannelForGood`;
`LispEvaluatorTest` (`evalValues*`, `evalMultipleValue*`, `evalNthValue`,
`evalUnwindProtectCleanupKeepsTheProtectedFormsValues`,
`evalSyntacticMvProducerTailPublishesThroughAFunctionReturn`,
`evalSyntacticMvProducerTailPublishesThroughALambdaReturn` (+ JVM/wasm twins and the
`mv-producer-lambda-tail` ci-spec case), `evalMultipleValueConsumerClearsTheSpillChannel`,
`evalValuesAtTopLevelIgnoresValuesPassedAsAnArgument`,
`evalMultipleValueChannelIsExactInSingleValueContexts`);
`LispPreludeLibraryTest.everyPreludeDefunOfAClFunctionThatAnswersSeveralValuesIsKnownToTheTailDiscipline`;
`RontoLispCliTest.theSchemeReplEchoesThroughTheSchemePrinter`, `ClPpcreE2eTest` (`(scan "abc"
"xyz")` is `(NIL)` on every leg); `RontoLispCliTest.replEchoesEveryValueOnItsOwnLine`;
`JvmLispCompilerTest.compileAndRun*` and `WasmLispCompilerIntegrationTest` twins
(`*MultipleValueChannelIsExactInSingleValueContexts` is the shared matrix); ci-spec
`multiple-values-core`, `multiple-values-single-value-contexts` (SBCL's answers),
`floor-family-function-object` (+ `evalFloorFamilyFunctionObject*` and twins),
`unwind-protect-values` (adds `--component`), `mv-producer-function-return` (its
`find-symbol`/`intern` rows probe a USER symbol because a non-literal name's runtime status diverges
between interpreter and compile paths), `split-sequence-residue-features`,
`rontolisp-package-introspection`; scheme-spec `multiple-values`. The unwind-protect pins run a
cleanup-shape x exit-shape matrix. Caveats: compiled `print` returns nil; JVM argument evaluation
order inside one call differs -- side-effect assertions go through `setq` in separate top-level
forms.
