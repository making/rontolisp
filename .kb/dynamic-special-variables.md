# Dynamic (special) variable binding

A *special* is bound with dynamic extent by `let`/`let*`/`progv` by SHALLOW BINDING: the value
lives in the global cell and a binding is save/set/restore over it. THREAD-scoped on the
interpreter, and on the JVM wherever another thread can run the program's Lisp ("One thread");
WASM is not.

## What proclaims a name special

`SpecialVarCollector` (`am.ik.rontolisp`, the shared AST layer, so the interpreter -- which must not
depend on `compiler` -- can use it): `defvar`/`defparameter`/`defconstant`, `(declaim (special
...))`, `(proclaim '(special ...))`. `LispNames.SPECIAL` is NOT registered as a cl symbol (it would
perturb pinned introspection counts). Earmuffs are style.

- A local `(declare (special x))` is SCOPED (CLHS 3.3.4; "Local special declarations"):
  `collectProclaimed` / `collectLocallyDeclared` keep the two kinds apart; `collect` (the compile
  paths' set) unions them, because `compiler/SpecialDeclarationScoping` has renamed every other
  binding of a locally declared name apart by then.
- `declare`/`special` heads match package-insensitively (`splitQualified`): under `(in-package p)`
  they are spelled `p::declare`.
- The interpreter collects proclamations at the top-level `eval(expr)` entry BEFORE evaluating.
- **A special is NEVER bound lexically, on any backend**: a closure reads the binding active when it
  runs (the global after the extent), as SBCL. A special-named parameter binds dynamically only.
- `collectBoundForm` reads lambda lists (every section, supplied-p, defaults), so
  `(defun f (*standard-output*) ...)` binds the special.
- A special is always ALSO a global (unioned into `GlobalVarCollector`'s set).

## Interpreter (`LispEvaluator`) -- full fidelity, thread-scoped

- `DynamicBindings`: per-evaluator `ThreadLocal<Map<String, Deque<LispVal>>>`. `specialVars` holds
  PROCLAIMED names; `localSpecials` the names local declarations have named so far
  (`declaredSpecials`, `SpecialDeclarations.leading`).
- `evalLetIn` evaluates all inits, then binds dynamically when proclaimed or declared at the body's
  head (no lexical twin); `finally` pops. A declared name gets `Environment.SPECIAL` in the body's
  scope (free declarations too), so a reference reads the dynamic binding even past an outer lexical
  one. `let*` (`expandLetStar`) keeps each declaration on its own `let`.
- `evalSymbolRef` / `assignVariable`: a proclaimed special reads `DynamicBindings` then the global;
  any other name its innermost LEXICAL binding, and on a miss or a mark the dynamic store -- gated on
  `progvUsed || localSpecials.contains(name)` -- so a lexical read never touches the thread-local.
  `progv` and a `make-thread` binding alist set `progvUsed`; progv-bound names need not be special.
  Restore fires on EVERY exit.

## JVM (`JvmLetCompiler`) -- shallow on one thread, thread-scoped where another can run Lisp

- A special NEVER dynamically bound keeps the bare `_g$*` static field.
- One that IS bound (`SpecialVarCollector.collectDynamicallyBound`, over the fully expanded program,
  single-stepping built-in binding macros via `LispMacroExpander.expandBuiltinMacro`) takes one of
  two shapes, chosen program-wide by `lispOnOtherThreads` (`Ctx.threadScopedSpecials`):
  - **One thread: shallow, WASM's shape.** Save `_g$*` into a slot, set it; reads are `getstatic`,
    `setq` `putstatic`. No `_d$`, no helper.
  - **Another thread can run Lisp: thread-scoped.** A `private static ThreadLocal _d$*` created in
    `<clinit>`, **NEVER lazily** (a racy first bind mints two), holding a one-element `Object[]`
    CELL -- **a cell, not the value**, because `nil` is Java `null` and could not mark "no binding
    here". Helpers `_dget`, `_dbind`, `_dset` (`JvmDynVarRuntimeBuilder`; `_dset` answers 0 with no
    binding so the site falls through to `putstatic _g$*`). Over-collection costs a read;
    under-collection throws in `JvmLetCompiler` at compile time.
- The save slot is all a binding has (`JvmLetCompiler.bindingHome`): never in `Ctx.locals`, never
  captured. A bound special is never a raw global (`JvmRawGlobals` excludes the set; `bindingHome`
  throws if one slips).
- Compiled backends bind a `let`'s variables one at a time, so a later init running code after a
  special binding is staged (`.kb/parallel-let.md`).
- Every read goes through `JvmExprCompiler.compileSpecialRead` (then `_bound` for a valueless
  special); `setq` writes the active binding (`JvmSetqCompiler.emitGlobalStore`).
- **The body is a PROTECTED REGION of the unwind-protect machinery** (`JvmUnwindProtectCompiler.Region`)
  whose cleanups are internal `(%dyn-restore homeField saveSlot)` forms (`LispNames.DYN_RESTORE_INTERNAL`),
  so the restore rides every exit channel -- normal, catch-any (an error, `%nlx-throw`,
  `catch`/`throw`), the `return`/`return-from`/`go` inlining -- interleaved with user cleanups
  innermost-first, since a binding IS an unwind scope. One mechanism, no separate exit-restore list.
  Restores are stack-neutral (no result slot). A body-less `let` has no region. A special `let` on the
  tail spine still joins it: `JvmBodyOutliner.readyToSplit` ignores a scope whose cleanups are all
  internal.
- Non-special globals stay lexical under `let` (ci-spec `lexical-global-let-stays-lexical`).
- A spawned thread does NOT inherit the spawner's bindings; `rontolisp:make-thread`'s alist is the
  hand-over, and a thread-primitive program forces EVERY special into the bound set (`.kb/threads.md`).
- `progv`'s `%progv-dyn-bind` / `%progv-dyn-unbind` follow the same choice (`JvmProgvCompiler`).

### One thread: when a binding may be shallow

**Invariant: a JVM program binds a special shallow only when no thread but the one running it can run
its Lisp code.** `lispOnOtherThreads` is true for a thread primitive (`usesThreads`), an async body or
served request (`usesAsyncSpawn`: `http-handler`, the `%http-server-*` seam, a war), a `jvm-export`,
the `java:` bridge (its `Proxy`), a generated `java:` implementation's callback (predicted by
`JvmJavaSites.needsApply`), `objc:` and `ffi:` (upcalls). The same set roots the tree shake besides
`main`: every entry from a thread of the host's choosing. The one prediction is checked: an attempt
that generated implementation callbacks with the gate off is redone with `GROUP_OTHER_THREADS`
forced. Not entries: `<clinit>` and the sized `main` worker run in sequence; a fetch settles on the
HTTP client's thread in Java (`RontoFetch`); a pull stream runs its thunk on the reader's thread.

- Shallow binding is what SBCL does on one thread and WASM always; no binding shape (callee, closure
  in or after the extent, `setq`, non-local exits, `unwind-protect`, `progv`, special parameter,
  `boundp` of a valueless special, `set` / `symbol-value`) answers differently, checked against SBCL
  2.2.9 on all four backends.
- The ci-spec program stays thread-scoped (it holds `async-defun`s), as does every served, threaded
  or exported program.
- The array runtime takes the same gate ([adjustable-arrays.md](adjustable-arrays.md), "Where another
  thread can run the program's Lisp").
- Why: a closure inside a binding reads specials as cheaply as lexicals when shallow; cl-ppcre (every
  scan binds `*string*`, `*start-pos*`, ...) speeds up markedly.

## WASM (`WasmLetCompiler`) -- shallow binding over the module global

Deliberately NOT thread-scoped: a served component's tasks interleave on ONE stack without
preempting inside a synchronous handler body. Not a claim of general concurrency safety.

- JSPI re-entry (`.kb/wasm-import.md`): answered by the RE-ENTRY GUARD (every export of a module that
  can suspend traps a second entry).
- `--reentrant` brings a PER-TASK store (`codegen.wasm/WasmDynVars`): `collectDynamicallyBound` names
  get a slot in a per-call TASK RECORD, created by every export wrapper, seeded by `_start`,
  saved/restored around the suspending host call. JVM hybrid's rules exactly; non-reentrant modules
  byte-identical.
- Otherwise the JVM's shape over `(mut (ref null eq))` globals: save/set (`Ctx.boundSpecials` lets
  reads in the binding's own function skip the UNBOUND test), `setq` of the global
  (`WasmSetqCompiler`), the same protected region (`WasmUnwindProtectCompiler.compileRegion` with
  `%dyn-restore`, one `UnwindScope` kind that exists outside EH mode too): in EH mode a landing pad
  that restores and rethrows, refreshing only SAVE slots (`wasm-landing-pad-refresh.md`); in every
  mode the `return` trampoline cascade and `return-from`/`go` inlining. Outside EH mode with no
  enclosing `return` boundary the emission is bare (body then restores). `--no-gc` rejects
  `defvar`/`declaim` at top level.

## progv on the compile paths

Bound symbols are runtime values but the candidate SPECIALS are static -- that asymmetry is the design
(`LispMacroExpander.expandProgvForCompile`, both compilers; the interpreter keeps `evalProgv`).

- A loop over the runtime symbols dispatching each over the special set; an arm is
  `(%progv-dyn-bind NAME value)` (`WasmDynVars.emitProgvBind` under `--reentrant`). Previous state
  flows as a VALUE consed onto a save list (bind and restore are in different iterations, so no save
  slot). **No site pays for the special set**: a site is
  `(let ((saved (%progv-bind syms vals))) (unwind-protect (progn body) (%progv-unbind saved)))`, the
  loops living once in `LispMacroExpander.progvRuntime` (dispatches `%progv-bind-name` /
  `%progv-unbind-name`, segmented). Injected when the program CALLS `progv` (`programCallsProgv`). If
  a user defun took a runtime name, the site spells the loops inline.
- `collectDynamicallyBound` returns EVERY special of a progv program. The restore is an
  `unwind-protect` cleanup, so `progv` FORCES EH MODE on WASM (`usesEhForm`).
- A name in NO arm is bound in the eval mirror (`_genv`/`GLOBAL_ENV`) via
  `%progv-genv`/`%progv-genv-set`, only when the eval runtime exists (progv does NOT force it); both
  flags are carried by EVERY compile context.
- **`symbol-value` of a special reads the VARIABLE** in every program with specials
  (`LispMacroExpander.dynamicFirstSymbolValue`), else `%symbol-value-raw`. So cl-json's
  `(progv vars (mapcar #'symbol-value vars) ...)` sees values its decoder `setq`s, and `symbol-value`
  answers what `set` wrote (`.kb/symbol-runtime-api.md`). `#'symbol-value` is a reference-gated
  `BuiltinFunctionWrappers` entry. No site pays for the set:
  - a LITERAL name folds (special -> the variable read, else `%symbol-value-raw`);
  - a computed name calls `%symbol-value-dynamic`, the read half of the shared accessor
    `%global-access`, cut into `NAME_DISPATCH_SEGMENT_ARMS` (128) arm segments
    (`segmentedNameDispatch`, shared with progv), each miss tail-calling the next. **Why segments**:
    under the 64 KB method limit and HotSpot's 8,000-byte `HugeMethodLimit` (a larger method is
    never JIT-compiled). Injected after the global set is final when anything can reach a computed
    site (`programUsesComputedSymbolValue`); otherwise a site spells the inline chain.
  - Inline chains at every site overflowed 64 KB per form (ci-spec
    `top-level-forms-answer-like-defun-bodies` holds four sites a form to pin it).
- **The name test is the internal `(%symbol-is n 'S)`** (`nameChain`): JVM `"S".equals(n)`, wasm a
  string-table-offset compare. The structural `equal` it replaced was wasm's ~11 us per lookup. The
  quoted name is not a spelled designator (`compileUnspelledLiteral`). Compiled as a search ("Name
  dispatch").
- **The shared dispatches are CALL-ONLY** (`dispatchableFuncIds`' `callOnly`): a `_invoke_N` /
  `_lookup` row would keep the whole runtime alive behind a `set`/`progv` site the shaker dropped
  (cl-ppcre's, ningle's). `JvmLispCompilerTest#theSharedNameDispatchesAreNeverDispatcherTargets`.
- **Deep else-chains compile iteratively** (`WasmIfCompiler.compile` walks the else spine in a loop):
  one Java frame per level overflowed a 1 MiB compile stack cold on CI only (JIT-dependent margin, cf.
  `interpreter-stack.md`). `WasmLispCompilerTest.aDeepElseChainCompilesOnAMegabyteStack` (a thousand
  specials through the INLINE dispatches).
- Measured with `ProgvSetSiteFixture` (300 specials, four sites): build failure -> compiled; lookups
  several-fold faster on JVM, an order of magnitude on wasm; computed `set` on wasm is dominated by the
  mirror's `_store` walk.
- The literal-`boundp` fold refuses progv programs (`CompileTimeBoundp.fold`).
- A symbol past the values is bound to the UNBOUND marker ("A progv short of values").
- Deliberate divergence: a non-symbol in the symbols list is not detected.

## Compile-path limitations (interpreter unaffected)

1. Exit restores are covered on EVERY channel on all four backends: an error caught outside (same
   frame or across a callee), `catch`/`throw`, `go`, a `return` through the wasm trampoline in either
   nesting order against `unwind-protect`, a `return-from` across a lambda (cl-ppcre's failing
   register scan leaked `*reg-starts*`). Pinned by `specialLetRestoresOnEveryExit` on
   `LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` (P1 + component),
   ci-spec `special-let-restores-on-every-exit` and `special-let-restores-across-nested-exits`, and
   `ClPpcreE2eTest`'s failing-one-register scan between two zero-register scans. NOT an exit: a wasm-GC
   RAW trap (`(car 5)`) ends the module. The mechanism is thrower-side (each frame restores its own
   on the way out), which is why no catch-site save stack is needed.
2. `boundp` of a special a definer gives a value answers the eval mirror's entry ("a global store
   happened"), which differs from the variable only for a binding made before the definer ran. A
   valueless special answers its variable ("Bound-ness of a special without a value"). VALUES by name
   read the variable ("One home").

## One home for a special's value by name (all four backends)

**Invariant: a read of a special BY NAME -- `symbol-value`, `eval` -- answers its variable (active
binding, else global), never the eval mirror; an `eval`'d assignment of any compiled global stores
where a compiled `setq` does.**

- One shared accessor `(%global-access name default store value)`
  (`LispMacroExpander.globalAccessRuntime`), a segmented dispatch. Read: a special's arm answers the
  variable, else `default`. Store: `%global-store-set` (`_dset` / module global / task record). One
  chain serves `symbol-value`, `set` (`%set-global` = `%set-mirror` + store) and the eval runtime, so
  the global set is paid once. Arms are all globals when something stores (`set`, `programUsesEval`,
  `load`, `--dynamic`), the specials alone when only `symbol-value` reads.
- The eval runtime (`Jvm/WasmEvalRuntimeBuilder`) reads a variable no eval'd binding holds through
  the accessor first with a fresh unique object as `default`, then the mirror; its
  `setq`/`setf`/`push`/`pop` call the store half, then `_store`. The compiled mirror
  (`mirrorGlobal`) still writes every store: it is the value of every non-special global and the
  `boundp` witness of every name but a tracked one.
- Cost: byte-identical except programs running `eval`; searched ("Name dispatch"), an eval'd access is
  cheaper than the old mirror walk.

## Name dispatch: a search, not a walk (JVM and both WASM)

**Invariant: a dispatch of a runtime name over a static name set costs a key and a search, not a test
per name.** The AST keeps `nameChain`'s else-chain (every pass reads plain `if`s); both compilers
recognise it (`compiler/NameDispatch.match`; a repeated name's arm is dead) wherever an `if` compiles.

- wasm (`WasmNameDispatchCompiler`, from 2 arms): the canonical offset into an i64 local (a non-`$str`
  misses), binary search (`NameDispatch.searchTree`), leaves of <= 16 arms. **The leaf constant must
  stay an `i32.const`**: it keeps the name's string and intern row through the shaker
  (`WasmTreeShaker` probes `i32.const`s), and a run-time `intern` of a cut name would get an offset no
  arm tests. Not in state-machine mode.
- JVM (`JvmNameDispatchCompiler`, past 4 arms): `String.hashCode` once, an `if_icmpge` search, leaves
  of <= 4 arms keeping `equals` -- `searchTree` never splits equal keys (`*XO*` / `*Y0*` collide;
  `NameDispatchFixture`). Rejected: keying on 15 hash bits (`sipush` pivots) ran random lookups twice
  as slow. JVM wants small leaves; wasm is flat across leaf widths.
- Programs without a dispatch are byte-identical. Pins:
  `WasmLispCompilerTest#aNameDispatchArmIsAnOffsetCompare`, the
  `aNameDispatchAnswersEveryNameItsChainDoes` four, `NameDispatchTest`.

## Bound-ness of a special without a value (all four backends)

**Invariant: `boundp` of a special the program declares without a value and binds answers its
variable: T for the extent of any binding (`let`, parameter, `progv`, `make-thread` hand-over), NIL
after it, T for good after a global store.** The idiom: `(defvar *request*)` bound per request,
probed by code that may run outside one. The mirror is wrong here (no binding writes it; a store
inside one writes it for good).

- TRACKED specials (`SpecialVarCollector.collectProbedValueless`): no valued `defvar`,
  `defparameter` or `defconstant` names it (scope-blind), not a `cl` symbol, and a `(boundp 'S)`
  names it -- or some `boundp` computes its argument (then all are tracked). A program with none is
  byte-identical.
- Representation: an UNBOUND marker in the GLOBAL cell until a global store; a binding saves/restores
  it like a value. EVERY valueless special carries it ("A read of a special without a value"); the
  tracked set is what `boundp` answers from the variable (`UnboundMarker.probed`,
  `Ctx.probedUnboundGlobals`). JVM `JvmDynVarRuntimeBuilder.unboundMarker`: `_unbound` set in
  `<clinit>`, `_g$` seeded from it, `_dbound(tl, global)`. Wasm: the raw-local sentinel
  (`Ctx.unboundGlobals`), stored by `_start`.
- `boundp` (`LispMacroExpander.dynamicFirstBoundp`): literal tracked name -> `(%global-boundp 'S)`;
  computed -> call-only `%boundp-dynamic` (segmented, miss on `%boundp-raw`); others the raw probe.
- **The eval gate**: a program whose every `boundp` is a literal probe of a tracked special carries
  no eval runtime and no mirror writes (`LispMacroExpander.boundpReachesMirror`; a computed site,
  `#'boundp`, quoted data, a macro template keeps it). It runs before injection, so it reads the set
  off the program (`GlobalVarCollector.collectProbedUnboundBeforeInjection`);
  `requireBoundpOffMirror` re-checks the final set. Pins:
  `aProgramWhoseEveryBoundpReadsAVariableCarriesNoEvalRuntime` (`JvmLispCompilerTest`,
  `WasmLispCompilerTest`), `BoundpInBindingFixture.LITERAL_SOURCE` on all four,
  `SpecialVarCollectorTest`. ci-spec cannot pin it (its program calls `eval`).
- Without the mirror the tracked set also takes every literally probed global without a definer's
  value (`.kb/compile-time-boundp.md`, "A probe the fold leaves open").
- `#'boundp` is a reference-gated wrapper, so `(mapcar #'boundp names)` is a computed probe. Pins:
  `BoundpFunctionValueFixture` on `boundpAndFboundpAreFunctionValues` (`LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`), ci-spec
  `boundp-and-fboundp-as-function-values`.

## A read of a special without a value (all four backends)

**Invariant: a read of a special that has no value -- outside every binding and before any store --
signals the `unbound-variable` naming it, as the interpreter and SBCL do.**

- The set (`SpecialVarCollector.collectValueless`): every special no valued `defvar`, `defparameter`
  or `defconstant` names (scope-blind) and not a `cl` symbol, plus, without the mirror, the literally
  probed globals. Each starts as the marker. Valued specials read as before, byte for byte.
- `boundp` keeps the narrower probed set (the mirror agrees for a special no binding changes).
- JVM: the read passes through `_bound(value, name)` (`JvmDynVarRuntimeBuilder.boundCode`): the value
  or `RuntimeException("The variable NAME is unbound")`, the text the landing pad recovers class and
  name from. `_bound` has no line table, so the uncaught report names the reading form
  (`UncaughtReportParityTest`). Never a raw global.
- Wasm (`WasmExprCompiler.emitCheckedRead`): test BEFORE read (`global.get g; global.get sentinel;
  ref.eq; if ... unreachable; end; global.get g`). EH mode: `_unbound_variable(off, len)` throws
  `UnboundVariableReport`'s condition (typed where `usedLayoutTags` bakes `unbound-variable`); outside
  EH mode the read traps, as `error` does there. Reads in the binding's frame skip the test
  (`Ctx.boundSpecials`).
- Chosen shapes, measured: JVM `_bound` (JIT-inlined, so an inline test buys nothing and costs bytes
  per site); wasm test-then-read ending in `unreachable` (a shared check or read-then-test is slower in
  EH modules). A shared check at `--optimize=size`: rejected (a second emission, a new type, a call
  per read, marginal gain).

## A read of a global before its first store (all four backends)

**Invariant: a read of a global no definer declares -- one a `setq` assigns -- that runs before its
first store signals the `unbound-variable` naming it, as the interpreter and SBCL do; a global nothing
can read before that store keeps the plain variable and read.**

- The set: `compiler/ReadBeforeStore.collect`, joined to the valueless set (`unboundGlobals`; JVM
  `UnboundMarker.globals`, wasm `Ctx.unboundGlobals`), same marker and checked read. A checked global
  is never a JVM raw global (it loses the unboxed integer pair).
- Candidates: collected globals less specials, nested-defun names, compiler-own names (`%mv-spill`,
  cluster stacks, stream variables) and `cl` symbols -- and only those something READS free
  (`FreeVarAnalyzer`); the collectors are scope-blind, so most are `let` variables.
- Exempt: the first top-level form naming it free is an unconditional `setq`/`setf`/`psetq`/`psetf`
  of it, and nothing that can run first reads it -- earlier forms, the store's own value, and the
  functions those can call: while that code calls only defuns, local functions and
  `ReadBeforeStore.INERT` operators, the defuns its text names (incl. `%setf-NAME`, `#'(setf NAME)`,
  `satisfies` through a `deftype`), transitively; past any other operator (printing, `make-instance`,
  `error`, a host call, an undefined function) every defun and condition report counts. Directives
  run nothing.
- Wasm decides it before `usedLayoutTags` (`readsUnboundGlobal` bakes `unbound-variable`).
- Census: across size-report, bench-report and all example compiles, 7 globals end up checked, in 2
  programs (streams.scm, cffi-sqlite); every other compile is unchanged.
- Not covered: per global, so post-store reads pay too; a first store under `let`/`progn` is not
  recognised; `--no-gc` keeps nil.

## A progv short of values (all four backends)

**Invariant: a symbol a `progv` has no value for is unbound for the extent -- a read signals
`unbound-variable`, `boundp` answers NIL, an assignment gives the binding a value -- and its previous
binding is back after the extent, as in SBCL.**

- Interpreter: `DynamicBindings.pushUnbound`, a private sentinel `get` answers as null.
- Compile paths: past the values the bind loop binds `(%progv-unbound)` (the marker). Any special can
  be named, so in a progv program every non-`cl` special is read-checked
  (`SpecialVarCollector.collectUnbindable`); only valueless ones are SEEDED with the marker. A `cl`
  symbol's arm binds nil instead: the runtimes read standard variables outside any compiled read.
- `boundp`: tracked specials answer from the variable (JVM `_dbound` tests the cell VALUE); others
  from the mirror, so the bind loop takes the name OUT of the mirror for the extent and the restore
  links it back. **Relies on the mirror holding at most one binding per name** (`_store` and the bind
  loop prepend only for an absent name).
- Wasm: a `progv` inside a `let` of the special can bind the marker there, so the expansion compiles
  with `Ctx.boundSpecials` empty.
- Cost: no size-report/bench/example program calls `progv`; the ci-spec program checks ~2,480 more
  JVM read sites through `_bound`; cl-json pays the same.
- Not covered: a `cl` special short of values is nil on the compile paths. Pins:
  `ProgvShortOfValuesFixture` on `aProgvShortOfValuesLeavesTheExtraSymbolsUnbound`
  (`LispEvaluatorTest`, `JvmLispCompilerTest` with a thread-scoped twin,
  `WasmLispCompilerIntegrationTest`), ci-spec `a-progv-short-of-values-leaves-the-extra-symbols-unbound`,
  `BoundpInBindingFixture`'s `UNBOUND_READ_SOURCE`, `SpecialVarCollectorTest`.

## makunbound (all four backends)

**Invariant: `(makunbound 'S)` takes the value of S's CURRENT binding (the active dynamic binding,
restored at its exit, else the global); `boundp` answers NIL and every read signals
`unbound-variable` until a store, as in SBCL.**

- Interpreter: `DynamicBindings.unbindCurrent` inside a binding, else `Environment.undefine`.
- Compile paths (`LispMacroExpander.expandMakunboundForCompile`): `%global-store-set S
  (%progv-unbound)`, plus unlinking from the mirror. A program naming `makunbound`
  (`programCallsMakunbound`) is gated like progv (`programUnbindsSpecials`): every non-`cl` special
  read-checked, literal-`boundp` fold refused, wasm binding-frame reads keep the test
  (`Ctx.unbindsSpecials`), and every special a `collectProbedValueless` candidate (the mirror, which
  no binding restores, would stay NIL after a `makunbound` inside one), seeded with the marker until
  its definer runs.
- Not covered: a non-special global is only taken out of the mirror; a `cl` special likewise. Pins:
  ci-spec `makunbound-and-compiler-macro-function`, `SpecialVarCollectorTest`.

## A defvar's "only if unbound" (all four backends)

**Invariant: a valued `defvar` stores only while the variable -- the active binding -- has no value,
each time it runs, wherever it stands (function body, conditional, loop, a `let` of the name); a
`defparameter` stores into the active binding.** A compile-time decision (`ctx.definedGlobals`,
"first one compiled stores") holds for straight-line top-level forms only.

- `compiler/DefvarGuards.collect` names the rest: a non-`cl` name with a valued `defvar` when one is
  below top level; a `makunbound` of it exists (a computed one takes all); or a top-level form before
  its first top-level `defvar` holds a STORE of it at any depth, or a computed-name store
  (`programUsesSet`) -- a `defun` holding one counts only when a form that can call it (`runsCode`)
  stands between. **STORES, not mentions**: counting mentions guarded yason's `true`/`false`, which a
  method reads before their `defvar`.
- A guarded name joins the unbound globals and every valued `defvar` of it compiles
  `DefvarGuards.guardedStore`, `(if (%global-boundp 'N) nil (setq N init))`. JVM
  `UnboundMarker.guardedDefvars` (thread-scoped needs `_dbound`, so built in `boundSpecialVars`); wasm
  `Ctx.guardedDefvars`.
- A nested `defvar`/`defparameter` proclaims its name program-wide (`collectNestedDefiners`).
- **JVM trap**: an unguarded `defvar`/`defparameter` must store through
  `JvmSetqCompiler.emitGlobalStore`; a bare `putstatic` misses a thread-scoped binding's `_d$` cell
  (`(let ((*p* 5)) (defparameter *p* 2) *p*) *p*` printed `(5 2)` in a threaded program). Pinned in
  `JvmThreadTest`.
- Interpreter (`evalDefvar`): test and store reach the active dynamic binding.
- Not revisited: hoisted class-allocated cells (`.kb/clos.md`, "A class defined below the top level")
  stay top-level `defvar`s.
- Pins: `DefvarGuardsTest`, `SpecialVarCollectorTest#aLocalSpecialDeclarationIsNoProclamation`,
  `GlobalVarCollectorTest#aLiteralProbeOfAGlobalWithoutAValueIsReadOffItsVariable`, ci-spec
  `defvar-below-the-top-level-binds-only-while-unbound`.

## Local special declarations; a special is never captured (all four backends)

**Invariant: a reference to a special reads the binding active WHEN IT RUNS -- never one a closure
captured -- and a local `(declare (special x))` covers the binding it names and the references in its
body only (CLHS 3.3.4): any other binding of `x` is lexical unless declared too, and an inner one
shadows the declaration.** Every backend answers SBCL's (`SpecialBindingScopeFixture`).

- Rejected: "active binding first, else the capture" (a lexical twin per special binding) -- not
  SBCL's rule even with a binding active; the program-wide reading of local declarations was the
  root cause.
- Interpreter: `specialVars` = proclaimed only; `declaredSpecials` reads a form's head declarations.
  `LambdaLists.desugared` repeats a special declaration over a `let*` prologue, and
  `do`/`do*`/`dotimes`/`dolist`/`prog`/`prog*`/`labels` hoist it onto their binding `let`
  (`SpecialDeclarations.hoisted`); `locally` with one is a binding-less `let`.
- Compile paths: `compiler/SpecialDeclarationScoping.scope`, right before
  `LambdaLists.desugarProgram`, renames apart every binding of a name only local declarations make
  special (`SpecialVarCollector.collectLocallyDeclaredOnly`) that no declaration covers (`NAME%N`; a
  key parameter keeps its keyword). A program without such names is returned as the same list. Then
  every occurrence of a `collect` name is special and a closure reads it like any code.
- Cost: on the JVM a closure's read is now `_dget` (thread-scoped programs) where the capture was a
  field load; one-thread programs bind shallow ("One thread").

## Parameters named like a special (all four backends)

**Invariant: a parameter whose name is special -- proclaimed, or declared at the head of the body;
any section, supplied-p included, of a `defun`, `lambda`, `flet`/`labels` local or built-in
expansion's lambda (uiop's `with-*`, `async-lambda`) -- binds it dynamically, restored on every
exit.**

- Interpreter: `LispEvaluator.apply` binds a special required/rest parameter dynamically
  (`bindParameter`; `lexicalLambdaScope` declines, keeping a Java frame); the `let*` prologue binds
  the rest. Declarations are read through the `block` a `defun`/`defmethod` wraps.
- Compilers: `LambdaLists.toNative(..., specials)` renames a special required or rest parameter to
  `__ll_sp_<position>` and wraps the WHOLE body, prologue included, in `(let ((*x* __ll_sp_N)) ...)`
  (`bindSpecialParameters`), inside a lone `%fn-block` (idempotent). Every extraction point passes the
  set: Pass 1 (`extractSetqLambda`), `Jvm`/`WasmLambdaCompiler`, `WasmAsyncEmit`.
- **Trap: collect the set AFTER `injectMvSpillGlobal`** (wasm: after the arity bundler), which
  declares the standard variables a program reads. Collected before, cl-json's progv covered fewer
  specials -- a size "win" that was a semantic change. `desugarProgram` gets only
  `SpecialVarCollector.collectDeclared` so `testSuppliedPInPlace` keeps a special supplied-p binding.
- The bound-set collection walks injected forms too (`injectedForms`): a `(defvar |x|)` could match
  one, and an uncollected lowered name throws in `JvmLetCompiler`.
- **No tail call through the binding**: the `let` is not tail-transparent (`JvmTailGroup.ofDefuns`
  stops there, `ofLabels` drops such a member, no wasm `return_call`;
  `JvmLispCompilerTest#aClosuresValueTailInsideASpecialParametersBindingStaysACall`). Restore-then-jump
  would be transparent only for a self call and was not taken: CL promises nothing, and no corpus
  function recurses through a special parameter.
- `--component`: a special parameter of an async function whose body awaits hits `WasmLetCompiler`'s
  "dynamic binding around `rontolisp:await`" refusal.
- wasm's `WasmArityBundler` binds a >10-parameter defun via a `let` from its rest list.
- Programs without a special parameter are byte-identical.

## The two hand-rolled precedents

- `*package*` has TWO faces: resolution-time (`PackageResolver`, `in-package`,
  `%push-package`/`%pop-package`) and run-time (kept in step by resolving `in-package` to
  `(setq *package* :P)`). On the compile paths the run-time face is an ordinary special; on the
  interpreter the faces are ONE cell, so it is the one special NOT thread-scoped through
  `DynamicBindings`; a SWANK request thread holds its own (`PackageResolver.bindThreadPackage`,
  `.kb/swank.md`). `.kb/packages.md`.
- Macro-time setf replay: `UserMacroExpander` replays a top-level `(setf (PLACE) ...)` under a
  deny-by-default purity judgment (`isPureConfigSetf`/`isPure`). `.kb/asdf.md`.
- A macro-time global's VALUE is demand-driven, its SPECIAL proclamation is not:
  `LispEvaluator.registerLazyGlobal` proclaims at once and parks the value as a thunk
  (`Environment.defineLazy`). **Two couplings fail SILENTLY**: `Environment.isBound` must count a
  pending thunk, and `Environment.set` must DISCARD one, or a replayed `(setf *html-mode* :html5)` is
  overwritten by the `defvar` default.

## Cost of the every-exit restore

A program binding no special is byte-identical; a wasm program binding one outside EH mode and any
loop block too. Per region the JVM pays an exception-table entry, a handler and two frames (~70 B);
wasm only the trampoline block (and the pad in EH mode).

## Tests

`LispEvaluatorTest` (`specialVar*`/`progv*`/`defparameter`/`declaim`/`proclaim`/thread-scoped,
`specialLetRestoresOnEveryExit`), `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`
(`specialVar*` and `progv*` groups, `specialLetRestoresOnEveryExit`; JVM adds
`specialVarBindingIsThreadScoped`, `specialVarSetqOutsideAnyBindingReachesTheGlobal`),
`JvmThreadTest` `spawnedThreadDoesNotInheritTheSpawnersDynamicBindings`,
`NoGcWasmCompilerTest.rejectsSpecialVariableBinding` (`--no-gc` binds no special),
`WasmReentrantE2eTest.overlappedCallsEachReadTheirOwnDynamicBinding`,
`WasmReentrantCompilerTest`, `ClJsonE2eTest`, `ClPpcreE2eTest`, ci-spec
`special-variable-dynamic-binding`, `progv-compiles-on-every-backend`,
`special-let-restores-on-every-exit`, `top-level-forms-answer-like-defun-bodies`.

JVM shallow vs thread-scoped (ci-spec cannot pin it: its program holds `async-defun`s):
`JvmLispCompilerTest#aSpecialIsBoundShallowUnlessAnotherThreadCanRunLispCode` (no `_d$` on one
thread; every gate entry keeps them),
`#aSpecialWithoutAValueCarriesTheUnboundMarkerAndOnlyAProbedBoundOneAsksDbound`,
`#compileAndRunWriteToStringKeywordAloneBindsThePrinterVariable`; threads released at once, each
binding and reading its own value: `JvmExportTest#aSpecialAnExportBindsIsBoundPerCallingThread`,
`JvmAsyncCompilerTest#concurrentAsyncBodiesEachSeeTheirOwnBindingOfASpecial`,
`HttpHandlerJvmTest#concurrentRequestsEachSeeTheirOwnBindingOfASpecial`, and the `java:` callbacks
of `specialVarBindingIsThreadScoped` / `boundpOfASpecialWithoutAValueIsThreadScoped` (with the gate
cut to the thread primitives, all three crossed on every run).

By-name reads and eval'd assignments: `SpecialReadByNameFixture` on
`aSpecialReadByNameAnswersTheActiveBinding` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest` -- Preview 1 and component), ci-spec
`eval-and-symbol-value-read-the-binding`.

set inside an active binding: `SetInDynamicBindingFixture` on `setWritesTheActiveDynamicBinding`
(`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`), ci-spec
`set-writes-the-active-dynamic-binding`,
`WasmReentrantE2eTest#overlappedCallsEachSetTheirOwnDynamicBinding` (`.kb/symbol-runtime-api.md`).

progv and set sites: `ProgvSetSiteFixture` (300 specials, four `progv` and four `set` sites a defun
and a top-level form) on `LispEvaluatorTest#progvAndSetSitesBindAndStoreByName`,
`JvmLispCompilerTest#progvAndSetSitesDoNotEachPayForTheSpecialSet` (site methods < 1,000 / 300 B,
every segment < 8,000 B), `WasmLispCompilerIntegrationTest#progvAndSetSitesBindAndStoreByName`
(Preview 1 and component), `WasmLispCompilerTest#progvAndSetSitesDoNotPayForTheSpecialSet` (four more
of each < 2,000 B of module).

symbol-value sites: `SymbolValueSiteFixture` (300 specials, eight-site defun and top-level form) on
`LispEvaluatorTest#symbolValueSitesReadTheActiveBinding`,
`JvmLispCompilerTest#symbolValueSitesDoNotEachPayForTheSpecialSet` (site method < 500 B, literal sites
call nothing, every segment < 8,000 B) and
`WasmLispCompilerIntegrationTest#symbolValueSitesReadTheActiveBinding` (Preview 1 and component);
`WasmLispCompilerTest#aSymbolValueSiteDoesNotPayForTheSpecialSet` (eight more sites < 1,000 B).

Name dispatch: `NameDispatchFixture` (154 specials, two hash-colliding pairs) on
`aNameDispatchAnswersEveryNameItsChainDoes` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest` -- Preview 1 and component),
`WasmLispCompilerTest#aNameDispatchArmIsAnOffsetCompare` (a 128-arm accessor < 32 B an arm),
`NameDispatchTest`.

Bound-ness without a value: `BoundpInBindingFixture` on
`boundpAnswersInsideABindingOfASpecialWithoutAValue` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest` -- Preview 1 and component), ci-spec
`boundp-of-a-special-inside-its-binding`;
`JvmLispCompilerTest#aSpecialWithoutAValueCarriesTheUnboundMarkerAndOnlyAProbedBoundOneAsksDbound`,
`#computedBoundpSitesDoNotEachPayForTheTrackedSpecials`, `#boundpOfASpecialWithoutAValueIsThreadScoped`;
`WasmReentrantE2eTest#overlappedCallsEachSeeTheirOwnBindingOfASpecialWithoutAValue`;
`SpecialVarCollectorTest#aProbedSpecialWithoutADefinersValueCarriesItsBoundnessInItsVariable`.

A read of a special without a value: `UnboundVariableNameFixture` on
`anUnboundVariableCarriesItsNameInTheCellErrorNameSlot` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest` -- Preview 1 and component) and ci-spec
`unbound-variable-carries-its-name`; `aSpecialWithoutAValueIsUnboundInAThreadThatDoesNotBindIt`
(`ThreadTest`, `JvmThreadTest`);
`WasmReentrantE2eTest#overlappedCallsEachReadTheirOwnBindingOfASpecialWithoutAValue`;
`WasmLispCompilerIntegrationTest#anUncaughtReadOfASpecialWithoutAValueEndsTheProgram` (trap outside EH
mode, report in it); `UncaughtReportParityTest#aReadOfASpecialWithoutAValueReportsWhereItIsRead`;
`SpecialVarCollectorTest#everySpecialWithoutADefinersValueStartsUnbound`.

A read of a global before its first store: `ReadBeforeStoreFixture` on
`aGlobalReadBeforeItsFirstStoreSignalsUnboundVariable` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest` -- Preview 1 and component) and ci-spec
`a-global-read-before-its-first-store-signals`;
`JvmLispCompilerTest#aGlobalCarriesTheUnboundMarkerOnlyWhereAReadCanComeBeforeItsFirstStore`,
`WasmLispCompilerTest#aGlobalCarriesTheUnboundCheckOnlyWhereAReadCanComeBeforeItsFirstStore`;
`ReadBeforeStoreTest`.

Parameters: `aParameterNamedLikeASpecialBindsItDynamically` on `LispEvaluatorTest`,
`JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` (Preview 1 and component)
(`SpecialParameterFixture`), ci-spec `special-parameters-bind-dynamically`;
`JvmLispCompilerTest#aTailCallInsideASpecialParametersBindingStaysACall`;
`LambdaListsTest#aParameterNamedLikeASpecialIsBoundByALetAroundTheWholeBody`,
`#aSpecialParametersLetGoesInsideTheFunctionBlock`, `#aSpecialSuppliedPKeepsItsBinding`;
`SpecialVarCollectorTest#aParameterNamedLikeASpecialIsADynamicBinding`.

Local declarations, no capture: `specialBindingsAreNeverCapturedAndLocalDeclarationsAreScoped` on
`LispEvaluatorTest`, `JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` (Preview 1 and
component) (`SpecialBindingScopeFixture`), ci-spec
`special-bindings-are-dynamic-and-declarations-scoped`; `SpecialDeclarationScopingTest`;
`SpecialVarCollectorTest#aLocalSpecialDeclarationIsNoProclamation`; the closure leg of
`spawnedThreadDoesNotInheritTheSpawnersDynamicBindings` (`ThreadTest`, `JvmThreadTest`).
