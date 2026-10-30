# Dynamic (special) variable binding

A variable proclaimed *special* is bound with dynamic extent by `let`/`let*`/`progv`, by SHALLOW
BINDING -- the value lives in the ordinary global cell and a binding is save/set/restore over it.
On the interpreter that cell is THREAD-scoped, on the JVM wherever another thread can run the
program's Lisp code ("One thread" below); WASM is not. Docs:
`doc/en/reference/special-forms/{progv,let,defvar,defparameter}.md`.

## What proclaims a name special

`SpecialVarCollector` (`am.ik.rontolisp`, the shared AST layer, so the interpreter -- which must
not depend on `compiler` -- can use it): `defvar`/`defparameter`/`defconstant`,
`(declaim (special ...))`, `(proclaim '(special ...))`. `LispNames.SPECIAL` is NOT registered as
a cl symbol (registering it would perturb pinned introspection counts). Earmuffs are style.

- A local `(declare (special x))` is SCOPED (CLHS 3.3.4; "Local special declarations" below):
  it makes the binding it names special and the references in its body, and an inner binding
  of the name without its own declaration is lexical. `collectProclaimed` /
  `collectLocallyDeclared` keep the two kinds apart; `collect` (the compile paths' set) still
  unions them, because `compiler/SpecialDeclarationScoping` has renamed every other binding
  of a locally declared name apart by then.
- `declare`/`special` heads are matched package-insensitively (`splitQualified`) -- under
  `(in-package p)` the resolver spells them `p::declare`/`p::special`.
- The interpreter collects proclamations at the top-level `eval(expr)` entry BEFORE evaluating.
- A special is NEVER bound lexically, on any backend: a closure over one reads the binding
  active when it runs (the global after the extent), as in SBCL. A lambda or macro parameter
  whose name is special binds dynamically only (`LispEvaluator.bindParameter`; the compile
  paths: "Parameters" below).
- A parameter is a binding the collector sees: `collectBoundForm` reads a `lambda`/`defun`
  lambda list (every section, supplied-p variables, default forms walked in order), so
  `(defun f (*standard-output*) ...)` makes the stream special special, as a `let` of it does.
- A special is always ALSO a global; on the compile path specials are unioned into the
  `GlobalVarCollector` set.

## Interpreter (`LispEvaluator`) -- full fidelity, thread-scoped

- `DynamicBindings` (`eval`): per-evaluator `ThreadLocal<Map<String, Deque<LispVal>>>`;
  `specialVars` = `ConcurrentHashMap.newKeySet()`.
- `specialVars` holds the PROCLAIMED names (and the seeded standard ones); `localSpecials` the
  names a local declaration has named so far, recorded as each binding form reads its
  declarations (`declaredSpecials`, `SpecialDeclarations.leading`).
- `evalLetIn` evaluates all inits in the outer env, then binds: dynamic when the name is
  proclaimed or the body's head declares it (`DynamicBindings.push`, no lexical twin), else
  lexical; `finally` pops. A declared name gets `Environment.SPECIAL` in the body's scope --
  a free declaration too -- so a reference there reads the dynamic binding even where an outer
  lexical binding of the name is visible, and an inner binding shadows the mark. `lexicalLet`
  (the tail-transparent fast path) takes a body with only free declarations; `let*` reuses it
  via `expandLetStar`, which keeps each declared variable's declaration on its own `let`.
- `evalSymbolRef` / `assignVariable`: a proclaimed special reads `DynamicBindings` first, the
  global behind it; any other name its innermost LEXICAL binding (`Environment.lookupLexical`,
  never the root scope), and on a miss or a mark the dynamic store -- gated on
  `progvUsed || localSpecials.contains(name)` -- then the global. So a lexical read never
  touches the thread-local store. `symbol-value`, `boundp` stay dynamic-first by name.
  `evalProgv` and a `make-thread` binding alist set `progvUsed`; an extra symbol is bound
  WITHOUT a value ("A progv short of values" below); progv-bound names need not be declared
  special. Restore fires on EVERY exit.

## JVM (`JvmLetCompiler`) -- shallow on one thread, thread-scoped where another can run Lisp

- A special NEVER dynamically bound keeps the bare `_g$*` static field (one `getstatic`).
- A special that IS bound -- decided by `SpecialVarCollector.collectDynamicallyBound`, which
  walks the fully expanded program and single-step-expands built-in binding macros via
  `LispMacroExpander.expandBuiltinMacro` -- is bound one of two ways, chosen for the whole
  program by `lispOnOtherThreads` in `JvmLispCompiler` (`Ctx.threadScopedSpecials`; "One
  thread" below):
  - **One thread: shallow, WASM's shape.** The binding saves `_g$*` into a slot and sets it;
    every read is the plain `getstatic` (plus `_bound` for a special without a value), `setq`
    the plain `putstatic`. No `_d$` field, no helper.
  - **Another thread can run Lisp: thread-scoped.** The special also gets a
    `private static ThreadLocal _d$*`, created in `<clinit>`, **NEVER lazily** (a racy first
    bind would mint two ThreadLocals), holding a one-element `Object[]` CELL. **A cell, not the
    value**: `nil` is Java `null`, so the value cannot mark "no binding on this thread".
    Over-collection costs a read; under-collection throws in `JvmLetCompiler` at compile time.
    Helpers (`JvmDynVarRuntimeBuilder`): `_dget`, `_dbind`, `_dset` (answers 0 when no binding,
    so the call site falls through to `putstatic _g$*`). `Ctx.dynVars` carries the fields.
- Either way the save slot is all a `let` binding has (`JvmLetCompiler.bindingHome` names the
  field it saves: `_g$*` or `_d$*`): no lexical slot, so the name is never in `Ctx.locals` and
  never captured. A shallow-bound special can never be a raw global (`JvmRawGlobals` excludes
  the collected set; `bindingHome` throws if one slips through).
- Both compiled backends bind a `let`'s variables one at a time, so a `let` whose later init
  runs code after a special binding is staged first (`.kb/parallel-let.md`).
- Every read goes through `JvmExprCompiler.compileSpecialRead` (thread-scoped: `_dget`, this
  thread's binding, else `_g$*`; then `_bound` for a special without a value, "A read of a
  special without a value" below) -- in the binding method, a callee and a closure alike. `setq`
  writes the active binding (`JvmSetqCompiler.emitGlobalStore`); with none it lands in `_g$*`.
- **The body is a PROTECTED REGION of the unwind-protect machinery**
  (`JvmUnwindProtectCompiler.Region`, opened by `JvmLetCompiler`) whose cleanups are the
  internal `(%dyn-restore homeField saveSlot)` forms (`LispNames.DYN_RESTORE_INTERNAL`; a
  `putstatic` or a `ThreadLocal.set`), innermost first. So the restore rides every exit channel that machinery covers: normal completion, the
  catch-any handler (an error caught in this frame or across a callee's, a cross-lambda
  `%nlx-throw`, `catch`/`throw`), and the `return`/`return-from`/`go` inlining of escaped scopes'
  cleanups -- interleaved with user cleanups in CL's innermost-first order, since a binding IS an
  unwind scope. One mechanism, no separate exit-restore list (the former `Ctx.specialBindScopes`
  covered the direct-branch exits only and leaked `*reg-starts*` on the rest). The restores are
  stack-neutral, so the value stays on the operand stack under them (no result slot). A
  body-less `let` has no region. A special `let` on the tail spine keeps joining it:
  `JvmBodyOutliner.readyToSplit` ignores a scope whose cleanups are all compiler-internal, because
  the region's exception range covers the continuation CALL and nothing in a continuation can
  leave the region lexically.
- Non-special globals stay lexical under `let`
  (ci-spec `lexical-global-let-stays-lexical`).
- A spawned thread does NOT inherit the spawner's bindings; `rontolisp:make-thread`'s bindings
  alist is the hand-over, and a program using the thread primitives forces EVERY special into
  the dynamically-bound set (`.kb/threads.md`).
- `progv`'s `%progv-dyn-bind` / `%progv-dyn-unbind` follow the same choice
  (`JvmProgvCompiler`): the previous value or the previous cell flows through the save list.

### One thread: when a binding may be shallow

**Invariant: a JVM program binds a special shallow only when no thread but the one running it
can run its Lisp code.** `lispOnOtherThreads` is true for a thread primitive (`usesThreads`), an
async body or a served request (`usesAsyncSpawn`, which covers `http-handler`, the
`%http-server-*` seam and so a war), a `jvm-export` (a `--no-main` class has one), the `java:`
bridge (its `Proxy` turns any function into a callback), a generated `java:` implementation's
callback (predicted by `JvmJavaSites.needsApply`), `objc:` (a method, a block, the main-thread
hand-over) and `ffi:` (an upcall). The same set is what the tree shake roots besides `main`:
every edge from a thread of the host's choosing. The one prediction is checked: an attempt that
generated implementation callbacks while the gate was off is redone with `GROUP_OTHER_THREADS`
forced (a gate cut to the thread primitives alone still passed the two `java:` callback tests
through that check, and failed them without it). Not an entry: `<clinit>` and the sized `main`
worker run one after the other; a fetch settles its future on the HTTP client's thread in Java
(`RontoFetch`), a pull stream runs its thunk on the reader's thread.

- Shallow binding is what SBCL does on one thread and what WASM does always; it is also exactly
  as observable: none of the shapes a binding takes (a callee, a closure called inside or after
  the extent, `setq` in it, `throw` / `return-from` / `go` / a caught error across it,
  `unwind-protect`, `progv`, a special parameter, `boundp` of a valueless special, `set` /
  `symbol-value`) answers differently, measured against SBCL 2.2.9 on all four backends
  (2026-10-06).
- The ci-spec program keeps the thread-scoped store (it holds `async-defun`s), as does every
  served, threaded or exported program; their emission is the one before.
- Checked 2026-10-06: a closure built inside a binding of two specials now reads them about as
  cheaply as lexicals do on the JVM -- on par with the same program spelled with `setq`
  save/restore in place of the binding, i.e. no dynamic binding at all -- and a callee's reads
  with it. cl-ppcre scanners, whose every scan binds `*string*`,
  `*start-pos*`, `*end-pos*` and friends (a `_dbind` allocated a cell and did a
  `ThreadLocal.get`/`set`) and whose advance closures read them (`_dget`), speed up markedly
  on the JVM.

## WASM (`WasmLetCompiler`) -- shallow binding over the module global

Deliberately NOT thread-scoped: a served component's concurrent tasks interleave on ONE
instance's single stack without preempting inside a synchronous handler body. Not a claim that
the wasm backends are concurrency-safe in general.

- JSPI already broke that: a `--no-wasi` reactor suspending through `WebAssembly.Suspending`
  (`.kb/wasm-import.md`) may re-enter the export before the first call resumes. The answer is
  the RE-ENTRY GUARD -- every export wrapper of a module that can suspend traps a second entry.
- `--reentrant` relaxes the guard and brings a PER-TASK store (`codegen.wasm/WasmDynVars`): only
  the specials `collectDynamicallyBound` names get a slot in a per-call TASK RECORD (a
  `TYPE_HASH_BUCKETS` array of nullable `TYPE_CELL`s in a module global), created by every
  export wrapper on entry, seeded by `_start`, and saved/restored by the import wrapper around
  the suspending host call. The JVM hybrid's rules carry over exactly. Every non-reentrant
  module is byte-identical.
- Base shape otherwise mirrors the JVM's over module globals (`(mut (ref null eq))`):
  `Ctx.specialVars`, a binding that is the global's save/set alone (`Ctx.boundSpecials` names
  the specials a `let` of the function being compiled holds, whose reads skip the UNBOUND
  test), `setq` of the global (`WasmSetqCompiler`), and the same protected region
  (`WasmUnwindProtectCompiler.compileRegion` with `%dyn-restore` cleanups, one `UnwindScope`
  kind that exists outside EH mode too): in EH mode a `try_table` landing pad that restores and
  rethrows the payload on its tag, whose refresh keeps only the SAVE slots
  (`wasm-landing-pad-refresh.md`); in every mode the plain-`return` trampoline cascade, and the
  `return-from`/`go` inlining of escaped scopes. Outside EH mode with no enclosing
  plain-`return` boundary the emission is bare -- body then restores, byte-identical to before.
  Same for `--component`. `--no-gc` `NoGcWasmCompiler` rejects `defvar`/`declaim` at top level
  outright.

## progv on the compile paths

`progv` compiles on the JVM and both WASM backends. Bound symbols are runtime values but the
candidate SPECIALS are static -- that asymmetry is the design
(`LispMacroExpander.expandProgvForCompile`, shared by both compilers; the interpreter keeps
native `evalProgv`).

- Lowers to a loop over the runtime symbol list dispatching each name over the program's
  special set; a matching arm is `(%progv-dyn-bind NAME value)` (`Jvm/WasmProgvCompiler`,
  `WasmDynVars.emitProgvBind` under `--reentrant`). Previous binding state flows as a VALUE
  consed onto a save list -- bind and restore sit in different loop iterations, so a save slot
  cannot work. No site pays for the special set (2026-10-04, `.todo/c81`): a site is
  `(let ((saved (%progv-bind syms vals))) (unwind-protect (progn body) (%progv-unbind saved)))`;
  the loops (mirror maintenance included) live once in `LispMacroExpander.progvRuntime`, whose
  per-name dispatches `%progv-bind-name` / `%progv-unbind-name` are segmented like the
  symbol-value one below. Injected when the program CALLS `progv` (`programCallsProgv`, quoted
  data skipped). Without the runtime (a user defun took a `%PROGV-BIND`/`%PROGV-UNBIND` name)
  the loops and chains are spelled inline at the site, as before.
- `collectDynamicallyBound` returns EVERY special of a progv-using program. The restore loop is
  the cleanup form of an `unwind-protect`, so `progv` FORCES EH MODE on WASM (`usesEhForm`).
- A name in NO arm is bound in the eval runtime's global env mirror (`_genv`/`GLOBAL_ENV`) via
  `%progv-genv`/`%progv-genv-set`. Mirror maintenance is included only when the eval runtime
  exists (`Ctx.evalStoreRef != null` / `Ctx.usesEval` -- progv does NOT force it); both flags are
  carried by EVERY compile context, not just the top-level one.
- `symbol-value` of a special reads the VARIABLE in every program with specials
  (`LispMacroExpander.dynamicFirstSymbolValue`; until 2026-10-04 only where progv or `set` was
  spelled, `.todo/c89` -- the one-home rule below), falling back to `%symbol-value-raw` for any
  other name. This is what makes cl-json's `(progv vars (mapcar #'symbol-value vars) ...)` see
  values its decoder `setq`s in the enclosing extent, and `symbol-value` answer the binding a
  `set` wrote (`.kb/symbol-runtime-api.md`). A program without specials keeps the raw emission.
  `#'symbol-value` has a REFERENCE-GATED `BuiltinFunctionWrappers` entry. No site pays for the
  special set (2026-10-04, `.todo/c78`):
  - a LITERAL name folds: a special -> the variable read, any other quoted datum ->
    `%symbol-value-raw`;
  - a computed name calls `%symbol-value-dynamic` (`symbolValueDynamicRuntime`), the read half of
    the one shared accessor `%global-access` (below) with a fresh cons as its default, which
    falls to `%symbol-value-raw`. The accessor is cut into segments of
    `NAME_DISPATCH_SEGMENT_ARMS` (128) arms (`segmentedNameDispatch`, shared with progv), each miss
    tail-calling the next segment (`%global-access-1`, ...). A read arm is a few bytecode
    instructions, so
    a segment is under the 64 KB limit for any special count and under HotSpot's 8,000-byte
    `HugeMethodLimit`, so the JIT compiles it. Injected by both backends after the global set is
    final, when the program, the injected runtime or a condition report can reach a computed site
    (`programUsesComputedSymbolValue`: any `SYMBOL-VALUE` but `(symbol-value 'x)`). A site whose
    program lacks it (a user defun took a name, or a site no scan saw) spells the old inline
    chain: correct, at the old size.
  - Before: every site, literal or computed, inlined the whole chain, so a defun or top-level
    form with a few sites overflowed 64 KB
    (ci-spec's `top-level-forms-answer-like-defun-bodies` was cut to one site a form;
    restored to four a form). A method over the `HugeMethodLimit` is never JIT-compiled, so a
    computed site also ran interpreted; segmented, such lookups are several times faster on
    the JVM, and wasm is unchanged (the `equal` test itself dominates there; `.todo/c81`,
    below).
  - Checked 2026-10-04, the ci-spec program as of `HEAD`: the shared accessor shrinks the JVM,
    wasm and component builds; with the restored four-site case the JVM build had failed
    before on an oversized `_top$N` and builds after. size-report, bench-report: every
    program byte-identical on all three outputs (none uses progv). examples: byte-identical
    but for build-info strings, except `jvm/cffi-sqlite.lisp`: its one computed site (cffi's
    `mini-eval`) now pays the
    shared runtime and its name-table entries -- a one-site program is the
    break-even's losing side. It counts as progv-using only because a code walker quotes
    `PROGV` (`programUsesSymbol` counts quoted data).
- The name test of every dispatch over the special or global set is the internal
  `(%symbol-is n 'S)` (`nameChain`): the JVM `"S".equals(n)`, wasm a string-table-offset compare
  (the canonical offset `eq` already relies on). The `equal` it replaced built the symbol and
  called the structural `_equal` on wasm, then boxed the answer -- that, not the chain length,
  was wasm's ~11 us a lookup. The quoted name is not a spelled designator
  (`compileUnspelledLiteral` / `stringTable.addString`). The compilers emit the chain as a
  search ("Name dispatch" below).
- The shared dispatches are CALL-ONLY: neither backend gives them a `_invoke_N` /
  `_lookup` row (`dispatchableFuncIds`' `callOnly`), even when names resolve at run time. A
  library holding a `set` or `progv` site the shaker later drops (cl-ppcre's, ningle's) would
  otherwise keep the whole runtime alive through the dispatchers on the
  str / ningle / postgres examples. Pinned by
  `JvmLispCompilerTest#theSharedNameDispatchesAreNeverDispatcherTargets`.
- Before the shared progv runtime, each site spelled both chains inline, and enough specials
  overflowed one top-level `progv` form past the 64 KB method limit. A 315-special program
  nested 315 `if`s. That is fine
  for the EMISSION (linear, small constants) but the wasm backend used to compile an
  else-chain with one Java frame per level, so it overflowed a 1 MiB compile stack
  cold on the ci-spec corpus (2026-09-25, `WasmTreeShakerCorpusTest » StackOverflow`
  on CI while every local box stayed green -- the same JIT-dependent margin
  `interpreter-stack.md` records for the interpreter). Else-chains now compile
  iteratively (`WasmIfCompiler.compile` descends the else spine in a loop and closes
  the deferred then-arms on the way back out), so depth costs no Java stack: the
  corpus compiles cold at `-Xss384k` on fresh JVMs, and the emission is byte-identical
  (the corpus compiles to the same bytes at all three optimize levels as before).
  Pinned by `WasmLispCompilerTest.aDeepElseChainCompilesOnAMegabyteStack` (a thousand
  specials through the INLINE progv and symbol-value dispatches -- user defuns take the
  runtimes' names -- on a 1 MiB thread; fails with `StackOverflow` without the loop).
- Fixed 2026-10-04 (`.todo/c81`). The shared, segmented runtime shrinks the ci-spec program's
  JVM, wasm and component builds, the largest method with it, and brings the
  `ProgvSetSiteFixture` top-level form (300 specials, four progv sites) from a build failure
  to a compiled method. Lookups over 300 specials, half the names on the last arm, speed up
  several-fold on the JVM and an order of magnitude on wasm; computed `set` improves on the
  JVM and stays put on wasm (the mirror's
  `_store` walk dominates `set`; the per-arm `ref.test` and the call cost the rest).
  size-report, bench-report: byte-identical on P1, `--optimize=size`, component and JVM.
  examples: byte-identical but for build-info strings, except `jvm/cffi-sqlite.lisp` and the
  twelve holding a DEAD `set` site (str-demo, the ningle
  workers, postgres/postmodern/bbs-api), which move by a few bytes: same strings, a
  different string-table order (the runtime compiles where the shaken site did not).
- The literal-`boundp` fold refuses progv programs (`CompileTimeBoundp.fold` gate).
- A symbol past the end of the values is bound to the UNBOUND marker (`%progv-unbound`), and in
  a program that calls `progv` every special's read is checked ("A progv short of values" below).
- Deliberate divergence: a non-symbol in the symbols list is not detected.

## Compile-path limitations (interpreter unaffected)

1. Exit restores are covered on EVERY channel on all four backends since 2026-09-12 -- an error
   caught outside the `let` (same frame or across a callee's), `catch`/`throw`, `go`, a plain
   `return` through the wasm trampoline cascade in either nesting order against an
   `unwind-protect`, and a `return-from` crossing a lambda boundary (cl-ppcre's scanner shape:
   a failing register scan used to leak `*reg-starts*` into every later zero-register scan).
   Pinned by `specialLetRestoresOnEveryExit` on `LispEvaluatorTest` / `JvmLispCompilerTest` /
   `WasmLispCompilerIntegrationTest` (P1 + component), ci-spec
   `special-let-restores-on-every-exit`, and `ClPpcreE2eTest`'s failing-one-register scan
   between two zero-register scans. Ci-spec `special-let-restores-across-nested-exits` adds the
   shapes that case leaves out (a `return` across a `handler-case` with and without an
   `unwind-protect`, a `go` across a `handler-case`, a `handler-bind` handler leaving by `go`, a
   50-deep recursion leaving by error / `throw` / `return-from`, a `return-from` out of an
   `flet` / `labels` function, nested bindings, `progv`); measured 2026-10-07 on SBCL 2.2.9, the
   interpreter, the JVM, Preview 1 and the component, all answering the same text.
   `doc/*/guides/missing-features.md` said until then that the compiled backends did not restore
   on an error, a `go` or a `return` across `unwind-protect` / `handler-case`; it now says every
   exit restores. What is NOT an exit: a wasm-GC RAW trap (`(car 5)`, a failed
   cast) ends the module, restore moot. The mechanism is thrower-side (each binding frame
   restores its own on the way out), which is why no catch-site save stack was needed: the
   `.todo/192` sketch's objection -- the slots live in the thrower's dead frames -- holds only
   for a CATCHER doing the restore.
2. `boundp` of a special whose definer gives it a value still answers the eval mirror's entry,
   a witness of "a global store happened" that no binding writes. It differs from the variable
   only for a binding made before that definer ran (where SBCL, reading the file in order,
   would not even bind it dynamically). A special declared without a value answers its
   variable ("Bound-ness of a special without a value" below). VALUES by name read the
   variable for every special ("One home" below).

## One home for a special's value by name (all four backends, 2026-10-04)

**Invariant: a read of a special BY NAME -- `symbol-value`, `eval` -- answers its variable (the
active dynamic binding, else the global), never the eval mirror; an `eval`'d assignment of any
compiled global stores where a compiled `setq` does.** Landed with `.todo/c89`. Before, after
`(let ((*dw* 2)) (f))` where `f` does `(setq *dw* 3)`, `(symbol-value '*dw*)` and `(eval '*dw*)`
answered 3 on the JVM and both WASM (the interpreter and SBCL: 1), and `(eval '(setq g 2))`
wrote the mirror only, so compiled code kept reading 1.

- One shared accessor, `(%global-access name default store value)`
  (`LispMacroExpander.globalAccessRuntime`), a segmented dispatch over the globals. Read
  (`store` nil): a special's arm answers the variable, every other name `default`. Store: the
  global's arm is `%global-store-set` (a non-lexical `setq`'s store: `_dset` / the module global
  / the task record). One chain serves `symbol-value`, `set` (`%set-global` = `%set-mirror` +
  the store half) and the eval runtime, so a program pays for the global set once. Its arms are
  the globals when something stores (a `set` site, or a program that runs forms through eval:
  `programUsesEval` / `load` / `--dynamic`), the specials alone when only `symbol-value` reads.
- The eval runtime (`Jvm/WasmEvalRuntimeBuilder`) reads a variable no eval'd binding holds
  through the accessor first, with a fresh object nothing else can hold as `default` (JVM
  `new Object[0]`, wasm a fresh cons), the mirror after (and the JVM's case-flipped retry the
  same way); its `setq`/`setf`/`push`/`pop` of such a variable call the store half, then `_store`
  as before. Only where the program runs forms through eval: the runtime is also switched on by
  `boundp` (but a literal probe of a tracked global), `symbol-value`, `fboundp` and, on the JVM, the Java, Objective-C and FFI bridges --
  those modules stay byte-identical. The compiled mirror (`mirrorGlobal`) still writes every
  store: it is the value of every non-special global and the `boundp` witness of every name
  but a tracked global ("Bound-ness of a special without a value" below).
- Size effect (2026-10-04). size-report, bench-report: byte-identical on P1,
  `--optimize=size`, component and JVM, except the rows whose libraries run
  `eval` (the Cloudflare Workers; the ningle ones most, whose accessor is ~200 arms plus the
  names it needs in the string table) and the eval / `symbol-value` programs among the
  examples. The ci-spec program pays the accessor too -- one accessor where
  `%symbol-value-dynamic` and `%set-global-store` were two chains.
- Run time: an eval'd variable access goes through the accessor before the mirror. As a linear
  walk with the names last in
  declaration order that cost more than the mirror read alone; searched ("Name dispatch"), it
  costs less on both backends.

## Name dispatch: a search, not a walk (JVM and both WASM, 2026-10-04)

**Invariant: a dispatch of a runtime name over a static name set costs a key and a search, not
a test per name.** The AST keeps `nameChain`'s else-chain -- every pass reads plain `if`s --
and both compilers recognise it (`compiler/NameDispatch.match`: `if`s testing
`(%symbol-is v 'S)` on one variable; a repeated name's arm is dead and dropped) wherever an
`if` compiles, the head of a shared runtime's segment or an inline chain.

- wasm (`WasmNameDispatchCompiler`, from 2 arms, value and effect position): the name's
  canonical offset once into an i64 scratch local (a non-`$str` branches to the miss), a binary
  search of `i64.lt_s` over the arms' offsets (`NameDispatch.searchTree`), leaves of at most 16
  arms each `local.get` / `i32.const OFF` / `i64.extend_i32_u` / `i64.eq` / `if ARM br $out`.
  The leaf's constant must stay an `i32.const`: it is what keeps the name's string and intern
  row through the shaker (`WasmTreeShaker` probes a live body's `i32.const`s), and a run-time
  `intern` of a cut name would get a fresh offset no arm tests. Not in state-machine mode.
- JVM (`JvmNameDispatchCompiler`, past 4 arms): `String.hashCode` once (a non-`String` goes to
  the miss), an `if_icmpge` search over the arms' hashes, leaves of at most 4 arms keeping the
  `equals` test, which separates colliding hashes -- `searchTree` never splits equal keys
  (`*XO*` / `*Y0*` collide; `NameDispatchFixture`). Keying on the hash's low 15 bits makes
  every pivot a `sipush` but ran a random lookup over 300
  names twice as slow under Graal (C2: the same). Arms carry the tail and exit marks like an
  `if`'s.
- Leaf size, measured: wasm time is flat across the swept leaf widths; the JVM wants a small
  leaf (wider leaves lose; see the `.todo` record).
- Checked 2026-10-04 (linear -> search). Computed `symbol-value` and two-name `progv` over
  300 specials drop from a linear walk to a search, by far the most on the JVM; a wasm
  accessor arm shrinks to the arm's access alone. Sizes: every program without a name
  dispatch byte-identical (size-report,
  bench-report, examples, P1 / `--optimize=size` / component / JVM). With one, wasm shrinks
  and the JVM grows by the pivots.
  The gzip rows barely move because an arm's information -- its offset and its global -- is
  the same either way. Pins: `WasmLispCompilerTest#aNameDispatchArmIsAnOffsetCompare`, the
  `aNameDispatchAnswersEveryNameItsChainDoes` four, `NameDispatchTest`.

## Bound-ness of a special without a value (all four backends, 2026-10-04)

**Invariant: `boundp` of a special the program declares without a value and binds answers its
variable: T for the extent of any binding (`let`, a parameter, `progv`, a `make-thread`
hand-over), NIL again after it, T for good after a global store.** The idiom is
`(defvar *request*)` bound per request and probed by code that may run outside one. Before, the
compile paths answered from the mirror, which no binding writes and a store inside one writes
for good: NIL inside `(let ((*x* 1)) ...)`, and T forever once a callee `setq`'d it there.

- The TRACKED specials (`SpecialVarCollector.collectProbedValueless`, kept where dynamically
  bound): no `defvar` with a value, `defparameter` or `defconstant` names one anywhere
  (scope-blind), not a `cl` symbol, and a `(boundp 'S)` names it -- or some `boundp` computes
  its argument, which makes every such special tracked. A self-evaluating argument probes
  nothing. A program with none compiles byte-identically.
- Representation: an UNBOUND marker in the GLOBAL cell until a global store overwrites it; a
  binding saves and restores it like any value. Since 2026-10-07 EVERY special without a value
  carries it, probed or not ("A read of a special without a value" below); the tracked set is
  the one `boundp` answers from the variable (`UnboundMarker.probed`,
  `Ctx.probedUnboundGlobals`). JVM (`JvmDynVarRuntimeBuilder.unboundMarker`): `_unbound`, a
  `new Object()` set in `<clinit>` with the `_g$` fields seeded from it, `_dbound(tl, global)` t
  for this thread's `_d$` cell or a non-marker global. Wasm: the raw-local sentinel
  (`Ctx.unboundGlobals`), stored by `_start` before user code; `--reentrant` reads the task
  cell first as before.
- `boundp` (`LispMacroExpander.dynamicFirstBoundp`): a literal tracked name is
  `(%global-boundp 'S)`; a computed name calls the shared, call-only `%boundp-dynamic`
  (`boundpDynamicRuntime`, a segmented name dispatch onto `%global-boundp`, the miss on
  `%boundp-raw`, the old mirror probe); any other name keeps the raw probe.
- The eval gate: a program whose every occurrence of `boundp` is a literal probe of a tracked
  special carries no eval runtime and no mirror writes (`LispMacroExpander.boundpReachesMirror`;
  any other occurrence -- a computed site, `#'boundp`, quoted data, a macro template, a literal of
  an untracked name -- keeps the arm). The gate runs before the runtime is injected, so it reads
  the set off the program (`GlobalVarCollector.collectProbedUnboundBeforeInjection`); the injected runtime
  spells no probe the program does not, and `requireBoundpOffMirror` checks the final set again.
  Checked 2026-10-04: the gate sheds most of the eval runtime from a program whose only
  `boundp` is `(defvar *x*)` probed in a callee of a binding or the
  `BoundpInBindingFixture.LITERAL_SOURCE` shape, landing near the size of the same program
  that reads `*x*` instead.
  size-report, bench-report, examples and Workers: byte-identical (P1, `--optimize=size`,
  component, JVM; none probes only tracked specials). Pins:
  `aProgramWhoseEveryBoundpReadsAVariableCarriesNoEvalRuntime` (`JvmLispCompilerTest`,
  `WasmLispCompilerTest`), the fixture's literal program on all four backends,
  `SpecialVarCollectorTest`. ci-spec cannot pin it: its concatenated program calls `eval`.
- Without the eval mirror the tracked set also takes every global a literal probe names and no
  definer gives a value, bound or not, special or not (`.kb/compile-time-boundp.md`, "A probe
  the fold leaves open"); the probe is `(%global-boundp 'G)` for all of them.
- `#'boundp` is a reference-gated wrapper (`BuiltinFunctionWrappers`, beside `#'symbol-value`),
  so `(mapcar #'boundp names)` is a computed probe: the injected body is among the forms
  `boundpProbes` reads, which tracks every valueless special exactly as a computed call does.
  Checked 2026-10-04: size-report, bench-report and the examples naming neither wrapper are
  byte-identical (P1, `--optimize=size`, component, JVM class); a program naming `#'boundp` pays
  the call's own cost over the same program with the call spelled out.
  Pins: `BoundpFunctionValueFixture` on `boundpAndFboundpAreFunctionValues` (`LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`), ci-spec
  `boundp-and-fboundp-as-function-values`.
- A read of such a special while unbound signals ("A read of a special without a value"
  below), and so does one inside a `progv` short of values ("A progv short of values" below).
- Size and speed effect (2026-10-04). size-report, bench-report: byte-identical on P1,
  `--optimize=size`, component and JVM. Workers and examples shift only where lack's /
  alexandria's computed `boundp` or the postmodern / bbs-api shape track locally declared
  specials and their read sites; the mirror probe the tracked representation replaces becomes
  a variable read, so a `boundp` of a tracked special drops from an alist walk to a
  compare; reads of a tracked special keep their speed inside a binding and pay little
  outside it.

## A read of a special without a value (all four backends, 2026-10-07)

**Invariant: a read of a special that has no value -- outside every binding of it and before
any store -- signals the `unbound-variable` naming it, as the interpreter and SBCL do.** Landed
with `.todo/d85`. Before, the compile paths read NIL there: only a `boundp`-tracked special had an
unbound state at all, and its read turned the marker into nil.

- The set (`SpecialVarCollector.collectValueless`): every special no `defvar` with a value,
  `defparameter` or `defconstant` names anywhere (scope-blind) -- a `(defvar x)`, a `declaim` /
  `proclaim` `special`, a local `(declare (special x))` alone -- and not a `cl` symbol; plus,
  without the eval mirror, the literally probed globals ("Bound-ness" above). Each starts as the
  UNBOUND marker. A special a definer gives a value reads as before, byte for byte; a global that
  is no special: next section.
- `boundp` keeps the narrower probed set: every other name's `boundp` reads the mirror, which
  agrees for a special no binding changes, so the eval gate and the computed `boundp` dispatch
  are unchanged.
- JVM: the read -- `getstatic`, or thread-scoped `_dget`, which hands the marker back -- passes
  through `_bound(value, name)` (`JvmDynVarRuntimeBuilder.boundCode`; `ldc` + `invokestatic`, +5-6 B
  a site): the value, or `RuntimeException("The variable NAME is unbound")`, the text the landing
  pad recovers the class and name from. `_bound` has no line table, so the uncaught report names
  the reading form (`UncaughtReportParityTest`). A special without a value is never a raw global.
- Wasm (`WasmExprCompiler.emitCheckedRead`): a plain module global is tested BEFORE it is read --
  `global.get g; global.get sentinel; ref.eq; if; [i32.const off; i32.const len; call
  _unbound_variable;] unreachable; end; global.get g` (17-20 B in EH mode, 11 outside). EH mode
  only: `_unbound_variable(off, len)` (`TYPE_WRITE_STR`'s signature, after `_undefined_function`)
  builds the symbol and throws `UnboundVariableReport`'s condition -- typed where `usedLayoutTags`
  bakes `unbound-variable` (a landing pad and a special without a value or a literal probe), the
  message-only payload elsewhere. Outside EH mode the read traps, as `error` does there. A read in
  the binding's own frame skips the test (`Ctx.boundSpecials`); `--reentrant` tests its per-task
  read through a temp.
- Checked 2026-10-07. Premise on SBCL 2.2.9 / interpreter / JVM / P1 /
  component: `(defvar *dv*)` then `*dv*` and `(symbol-value '*dv*)` caught for `cell-error-name`:
  `*DV*` / `*DV*` / NIL / NIL / NIL. The per-site alternatives were weighed and the shared
  shapes taken: on the JVM `_bound` (which the JIT inlines, so an inline `dup; getstatic
  _unbound; if_acmpne; ldc; invokestatic` test buys nothing and costs more per site), and on
  wasm the test-then-read arm ending in `unreachable` (a shared check without the length, and
  read-then-test variants, are slower in an EH module; outside EH mode it makes no
  difference). A shared check at
  `--optimize=size` was not taken: it costs a second emission
  and a new function type, and a call per read, for a marginal size change.
- Byte identity: size-report and bench-report outputs are identical across their levels.
  examples: all but the ones holding a special without a value -- cl-ppcre's local declarations under
  rove or ningle (roman, minesweeper-core-test, the three Workers checks, httpbin-ningle), jzon's
  `*writer*` (httpbin-jzon), cffi's (cffi-sqlite) -- compile identically, and
  every one that runs prints what it did before. ci-spec: all cases on all four backends.

## A read of a global before its first store (all four backends, 2026-10-07)

**Invariant: a read of a global no definer declares -- one a `setq` assigns, at top level or in a
function body -- that runs before its first store signals the `unbound-variable` naming it, as the
interpreter and SBCL do; a global nothing can read before that store keeps the plain variable and
the plain read.** Landed with `.todo/e03`. Before, its variable started as nil on the compile paths
and such a read answered NIL.

- The set: `compiler/ReadBeforeStore.collect`, joined to d85's (`unboundGlobals`; the JVM's
  `UnboundMarker.globals`, wasm's `Ctx.unboundGlobals`), so the representation and the read are
  "A read of a special without a value"'s: the marker seeded first, `_bound` / `emitCheckedRead`.
  A checked global is never a JVM raw global (`JvmRawGlobals` excludes the set): it loses the
  unboxed pair an integer accumulator gets.
- Candidates: the globals the compilers collect less the specials, nested-defun names, the
  compiler's own (`%mv-spill`, the cluster stacks, the stream variables) and `cl` symbols -- and
  only those some form, defun or report READS free (`FreeVarAnalyzer`, the closure-capture walk):
  the collectors are blind to scope, so most of them are `let` variables a `setq` assigns, never
  read as globals.
- Exempt: the first top-level form that names it free is an unconditional `setq` / `setf` /
  `psetq` / `psetf` of it, and nothing that can run before that store reads it -- the forms before
  it, the values the store evaluates first, and the functions those can call. While that code calls
  only defuns, local functions and `ReadBeforeStore.INERT` operators, the functions are the defuns
  its text names (code or quoted data, `%setf-NAME` through a place's accessor or `#'(setf NAME)`, a `satisfies`
  predicate through the `deftype` a type names), closed over their own text; past any other operator
  (printing: a `print-object` method; `make-instance`; `error`; a host call; a function the program
  does not define) every defun and every condition report counts. Directives (`wasm-import`,
  `wasm-export`, `jvm-export`, the component import) run nothing; an imported name is a host call.
- Wasm decides it before `usedLayoutTags` (the `readsUnboundGlobal` gate that bakes
  `unbound-variable`), on the program no pass changes before the globals are collected.
- Checked 2026-10-07. Premise on SBCL 2.2.9 / interpreter / JVM / P1 / component, `(defun g ()
  *nb*)` called under a handler before `(setq *nb* 1)`: `*NB*` / `*NB*` / NIL / NIL / NIL; the same
  for a top-level read before the `setq` and for a global only a function assigns. Census of the
  candidate rules over size-report (hello_world, pi_approx, zlib, dom_reactor), bench-report (10)
  and every compile shape of the examples (297 compiles): globals read as globals 40, in 8 programs
  (the Scheme and Clojure examples -- their `%scheme-false` / `%clojure-false` and `define`d
  globals -- and cffi-sqlite), 161 read sites, 2 of them JVM raw globals; exempt only when no
  function reads it: 22 left checked; the rule above: 7 (1 raw global), in 2 programs --
  streams.scm's `fibs` and `powers-of-two` (a delayed lambda in the store's own value reads them)
  and `computed` (a function reads it, a print comes first), cffi-sqlite's 4 iterate globals
  (`defconst` stores them through `(setf (symbol-value ...))`, which no rule sees as a first
  store). Every other compile's set is empty and compiles as before. Both programs print what they
  did.
- Not covered: the check is per global, so a read after the store pays it too once the global is
  checked; a first store under a `let` or `progn` is not recognised (checked); `--no-gc` keeps
  nil.

## A progv short of values (all four backends, 2026-10-07)

**Invariant: a symbol a `progv` has no value for is unbound for the extent -- a read of it signals
the `unbound-variable` naming it, `boundp` answers NIL, an assignment gives the binding a value --
and its previous binding is back after the extent, as in SBCL.** Landed with `.todo/e09`. Before,
every backend bound it to nil, documented as a divergence.

- Interpreter: `DynamicBindings.pushUnbound`, a binding holding a private sentinel that `get`
  answers as null; `evalSymbolRef` / `symbol-value` report it as the `unbound-variable`, `boundp`
  as NIL.
- Compile paths: the bind loop's value past the values is `(%progv-unbound)`, the marker of "A read
  of a special without a value" (JVM `_unbound`, wasm the raw-local sentinel); nil where no read is
  checked. Any special can be among the symbols, so in a program that calls `progv`
  (`programCallsProgv`) every special but a `cl` symbol is read-checked
  (`SpecialVarCollector.collectProgvUnbindable`, joined to JVM `UnboundMarker.globals` / wasm
  `Ctx.unboundGlobals`). Only the valueless ones are SEEDED with the marker; a special a definer
  gives a value keeps its plain start. A `cl` symbol's `%progv-dyn-bind` arm binds nil in place of
  the marker: the runtimes read the standard variables outside any compiled read.
- `boundp`: a tracked special answers from its variable; JVM `_dbound` tests the thread's cell
  VALUE (a cell can now hold the marker), a few bytes more in every thread-scoped program with a
  probed valueless special. Every other name answers from the eval mirror, so with the mirror the
  bind loop takes the name OUT of it for the extent (record `(name prev entry nil t)`), and the
  restore drops any binding a store made meanwhile and links the taken entry back. That relies on
  the mirror holding at most one binding a name: `_store` and the bind loop prepend only for a
  name it does not hold.
- Wasm: a read in the frame of a `let` of the special skips the test (`Ctx.boundSpecials`); a
  `progv` inside can bind the marker there, so the expansion compiles with that set empty.
- Checked 2026-10-07. Premise: the todo program (`(defvar *pa* 1)`, `(progv '(*pa*) '() ...)`
  read through a handler, plus `boundp`) SBCL 2.2.9 `((:UNBOUND *PA*) NIL)` then `1`;
  interpreter, JVM, P1, component `(NIL T)` then `1`. A name no declaration makes special: SBCL
  `(NIL (:UNBOUND NAME))`, every backend `(T NIL)`. Census of the checked set: size-report,
  bench-report and every example's compile (142 JVM compiles): no program calls `progv`, so the
  set is unchanged in each. The ci-spec program: 541 specials, 475 not `cl` symbols, 58 checked
  before, 452 newly checked, ~2,480 JVM read sites newly passing through `_bound`. cl-json's
  `progv` sites (never short) make a cl-json program pay the same.
- Not covered: a `cl` special short of values is nil on the compile paths (unbound in the
  interpreter). Pins: `ProgvShortOfValuesFixture` on `aProgvShortOfValuesLeavesTheExtraSymbolsUnbound`
  (`LispEvaluatorTest`, `JvmLispCompilerTest` with a thread-scoped twin,
  `WasmLispCompilerIntegrationTest`), ci-spec
  `a-progv-short-of-values-leaves-the-extra-symbols-unbound`, `BoundpInBindingFixture`'s
  `UNBOUND_READ_SOURCE`, `SpecialVarCollectorTest`.

## Local special declarations; a special is never captured (all four backends, 2026-10-05)

**Invariant: a reference to a special reads the binding active WHEN IT RUNS -- never one a
closure captured -- and a local `(declare (special x))` covers the binding it names and the
references in its body only (CLHS 3.3.4): any other binding of `x` is lexical unless it is
declared too, and an inner one shadows the declaration.** Every backend answers SBCL's
(`SpecialBindingScopeFixture`).

- Before: a local declaration made its name special PROGRAM-WIDE, and to keep cl-ppcre's
  matcher closures working (they capture a LEXICAL `end-string` the program-wide reading made
  special) every special binding was DUAL -- the dynamic store plus a lexical twin a closure
  captured. The interpreter read dynamic-first everywhere and the twin only with no binding
  active; the compilers' closures read the twin. The planned fix ("active binding first, else
  the capture" on every backend, the interpreter's rule) was overturned by measurement: that
  rule is not SBCL's even with a binding active.
- Checked 2026-10-05 (SBCL 2.2.9 / interpreter / JVM, P1, component), the fixture's probes:
  a `defvar`'d special captured in its binding and called after it `:TOP` / `:INNER` /
  `:INNER`, inside another binding `:OUTER` / `:OUTER` / `:INNER`; a special parameter
  captured `:TOP` / `:ARG` / `:ARG`; trivia's `assoc` pattern (a handler built in one binding
  of its flag runs inside a second) `:INNER` / `:INNER` / `:OUTER`; a LEXICAL binding of a
  locally declared name captured, called inside a special binding of it, `:CAPTURED` /
  `:REBOUND` / `:CAPTURED`; CLHS's `declare-eg` `(T NIL)` / `(T T)` / `(T T)`; a `setq` through
  a closure after the extent `:SET` / `:TOP` / `:SET`; a lexical binding inside a `progv` of
  the name `:LEX` / `:DYN` / `:LEX`. The twin patched the program-wide reading; it was the root.
- Interpreter: `specialVars` holds the proclaimed names only; `declaredSpecials` reads a binding
  form's head declarations ("Interpreter" above). `LambdaLists.desugared` repeats a body's
  special declaration over a `let*` prologue, `expandLetStar` keeps each declared variable's
  declaration on its own `let`, and `do`/`do*`/`dotimes`/`dolist`'s result/`prog`/`prog*`/
  `labels`' body hoist it onto their binding `let` (`SpecialDeclarations.hoisted`); `locally`
  with one is a binding-less `let`. A body declaring nothing special expands as it always did.
- Compile paths: `compiler/SpecialDeclarationScoping.scope`, run by both compilers right
  before `LambdaLists.desugarProgram`, renames apart every binding of a name only local
  declarations make special (`SpecialVarCollector.collectLocallyDeclaredOnly`) that no
  declaration covers, with the references it scopes (`NAME%N`; a key parameter keeps its
  keyword, spelled out). The core binding forms and the forms with unevaluated symbol
  positions are walked as written; any other built-in macro naming a tracked name through its
  expansion, which replaces it only where a binding was renamed. A program no local
  declaration names a variable in is returned as the same list. Then every occurrence of a
  name in `collect`'s set is special, so a special binding is the dynamic store's save/set
  alone (JVM/WASM sections above) and a closure reads it like any other code.
- Size effect (2026-10-05): size-report and bench-report
  byte-identical on P1, `--optimize=size`, component and JVM but zlib's JVM class
  (chipz binds a special). The examples that use cl-ppcre's locally declared specials
  (ningle, postgres,
  postmodern, bbs-api, str-demo) shrink on the JVM and wasm; nothing grows. cl-ppcre's E2E
  exercise shrinks on the JVM and stays put on the wasm legs.
- Time (same day): the interpreter is unchanged within noise (fib 27, reads of a special,
  special bindings);
  a closure built inside a binding and called in it no longer allocates a cell on wasm, and on
  the JVM its read is now `_dget`, a `ThreadLocal` lookup,
  where the capture was a field load -- the one visible cost of the change. cl-ppcre scanner
  creation and scans are within noise on both backends. The JVM scan cost is the `labels`
  advance function the scanner
  builds inside its `let*` of `*end-pos*` and friends: its lambdas now make `ThreadLocal`
  lookups, a few a step of the scan. A
  program that runs Lisp on one thread now binds shallow and reads a field again ("One thread:
  when a binding may be shallow" above).

## Parameters named like a special (all four backends, 2026-10-03)

**Invariant: a parameter whose name is special -- proclaimed, or declared special at the head
of the body; any section of the lambda list, a supplied-p variable included, of a `defun`, a
`lambda`, an `flet`/`labels` local or a built-in expansion's lambda (uiop's `with-*`,
`async-lambda`) -- binds it dynamically, restored on every exit.**
Landed 2026-10-03 (`.todo/c06`). Before, the compilers bound a required or rest one lexically:
`(defvar *x* 1) (defun show () *x*) (defun f (*x*) (show)) (f 2)` answered 1 on the JVM and
both WASM, 2 on the interpreter, and a `setq` of such a parameter wrote the global.

- Interpreter: `LispEvaluator.apply` binds a special required/rest parameter dynamically
  (`bindParameter`; `lexicalLambdaScope` declines the lambda, so the call keeps a Java frame);
  the `let*` prologue binds the other sections. A declaration naming a required or rest
  parameter is read at the head of the body, through the `block` a `defun`/`defmethod` wraps it
  in; `LambdaLists.desugared` repeats a body's special declaration over the prologue
  (`SpecialDeclarations.hoisted`), which would otherwise move it off the head.
- Compilers: `LambdaLists.toNative(..., specials)` renames a special required or physical rest
  parameter to `__ll_sp_<position>` and wraps the WHOLE body, `let*` prologue included (a default
  sees the binding), in `(let ((*x* __ll_sp_N)) ...)` -- this file's special `let`, so the
  save/restore, the protected region and every exit channel come with it
  (`bindSpecialParameters`). A body that is one `%fn-block` takes the `let` inside it, keeping
  the wrap idempotent. Every extraction point passes the program's set: Pass 1
  (`extractSetqLambda`, program defuns and injected runtime alike), `Jvm`/`WasmLambdaCompiler`
  (`ctx.specialVars`), `WasmAsyncEmit`.
- `desugarProgram` does NOT lower: it runs before `injectMvSpillGlobal` declares the standard
  variables a program reads (`*print-base*`, `*package*`, `*features*`, `char-code-limit`, ...).
  It gets `SpecialVarCollector.collectDeclared(program)` only so `testSuppliedPInPlace` keeps
  the binding of a special supplied-p variable (a callee reads it; a test in place cannot
  answer -- the binding used to be dropped on the compile paths). The full set is collected
  right after `injectMvSpillGlobal` (wasm: after the arity bundler), before Pass 1, on the
  program the old site saw, so its order is unchanged. Trap, the first cut: collecting the set
  BEFORE the desugaring missed those standard variables -- cl-json's progv fallback covered
  fewer specials than it must, and the ci-spec program shrank for it. A size "win" that
  was a semantic change.
- The dynamically-bound collection walks the injected wrappers and helpers beside the program
  (`injectedForms`): their parameters are lowercase or `%`/`__` names that never match a user
  special, but a `(defvar |x|)` would, and an uncollected lowered name throws in
  `JvmLetCompiler`.
- No tail call through the binding: the `let` is not tail-transparent, so a self or sibling
  call inside it is a call -- no JVM jump (`JvmTailGroup.ofDefuns` stops at the lowered body's
  special `let`, `ofLabels` drops a member whose physical parameter is special), no JVM bounce
  of a call through a value (the callee reads the binding,
  `JvmLispCompilerTest#aClosuresValueTailInsideASpecialParametersBindingStaysACall`), no wasm
  `return_call`. The interpreter keeps a frame per such call too. Depth of
  `(defun deep (*y* n) (if (= n 0) ... (deep *y* (- n 1))))`: interpreter passes 10,000,
  overflows at 20,000; JVM `java Prog` passes 50,000, overflows at 100,000; wasm passes 8,000,
  overflows at 9,000 (a plain non-tail recursion there: 10,000 / 20,000). Restore-then-jump is
  transparent only for a SELF call (a callee that does not rebind the name would see the outer
  value) and was not taken: CL promises nothing here, and no corpus function recurses through
  a special parameter (census below).
- A closure built in the body reads the binding active when it runs, as SBCL does: the global
  after the extent, another binding inside one ("Local special declarations" below).
- `--component`: a special parameter of an async function whose body awaits is
  `WasmLetCompiler`'s "dynamic binding around `rontolisp:await`" refusal.
- wasm's `WasmArityBundler` binds a defun of more than 10 parameters through a `let` from its rest
  list, so cl-ppcre's 12-parameter `create-scanner-aux` bound its special parameters dynamically
  on wasm before this change and lexically on the JVM.
- Census (2026-10-03, every lowered parameter printed during a JVM compile): ci-spec,
  clojure-spec, scheme-spec: none. Vendored libraries: cl-ppcre only (rove and uax-15 load it):
  `create-scanner-aux` (`starts-with`, `end-anchored-p`, `end-string-offset`, `reg-num`) and
  `maybe-split-repetition` (`reg-seen`) -- special then only through a program-wide reading of
  local `(declare (special ...))` that is gone: no declaration names them in those functions, so
  they are lexical parameters again, renamed apart ("Local special declarations" below).
- Cost: a program without a special parameter compiles byte-identically on the JVM, wasm and
  the component (ci-spec, its build-info strings aside, clojure-spec, scheme-spec and the
  vendored libraries loaded alone), so it runs at the same speed. cl-ppcre pays a little on
  the JVM for the wrapped bindings and loses on wasm a
  docstring `maybe-split-repetition` used to evaluate for effect; scanner creation and scan
  times are within noise, and the
  scan path's code is unchanged.

## The two hand-rolled precedents

- `*package*` has TWO faces: resolution-time (`PackageResolver`, `in-package`,
  `%push-package`/`%pop-package` markers) and run-time (a genuine variable kept in step by
  resolving `in-package` to `(setq *package* :P)`). On the compile paths the run-time face is an
  ordinary special of this file; on the interpreter the two faces are ONE cell, which is why it
  is the one special the interpreter does NOT thread-scope through `DynamicBindings`. A thread a
  SWANK request runs on holds that cell of its own instead (`PackageResolver.bindThreadPackage`,
  `.kb/swank.md`). `.kb/packages.md`.
- Macro-time setf replay is separate: `UserMacroExpander` replays a top-level
  `(setf (PLACE) ...)` into its macro-time evaluator under a deny-by-default purity judgment
  (`isPureConfigSetf`/`isPure`). `.kb/asdf.md`.
- A macro-time global's VALUE is demand-driven; its SPECIAL proclamation is not.
  `LispEvaluator.registerLazyGlobal` adds the name to `specialVars` at once but parks the value
  as a thunk (`Environment.defineLazy`). Two couplings fail SILENTLY (wrong value, not a crash):
  `Environment.isBound` must count a pending thunk, and `Environment.set` must DISCARD one, or
  a replayed `(setf *html-mode* :html5)` is later overwritten by the original `defvar` default.

## Cost of the every-exit restore (2026-09-12)

Per protected region, the JVM `.class` moves more than the wasm module, because each region
adds an exception-table entry, a handler and two stack-map
frames; wasm adds only the trampoline block, and in EH mode the pad. Concretely: a program
that binds no special is unchanged; a special-binding `let` costs a few bytes on the JVM and
nothing on wasm, a little more inside a `dolist` or under a `handler-case` (EH mode); an
`unwind-protect` around a non-special form costs nothing.

A program that binds no special is byte-identical; a wasm program binding one outside EH mode and
outside any loop block is too. cl-ppcre is the outlier because `(declare (special ...))` is
honored program-wide, so hundreds of its `let`s bind specials and each pays ~70 B on the JVM.

## Tests

`LispEvaluatorTest` (`specialVar*`/`progv*`/`defparameter`/`declaim`/`proclaim`/thread-scoped,
`specialLetRestoresOnEveryExit`), `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`
(`specialVar*` and `progv*` groups, `specialLetRestoresOnEveryExit`; JVM adds
`specialVarBindingIsThreadScoped`, `specialVarSetqOutsideAnyBindingReachesTheGlobal`),
`JvmThreadTest` `spawnedThreadDoesNotInheritTheSpawnersDynamicBindings`,
`NoGcWasmCompilerTest.rejectsSpecialVariableDeclaration`,
`WasmReentrantE2eTest.overlappedCallsEachReadTheirOwnDynamicBinding`,
`WasmReentrantCompilerTest`, `ClJsonE2eTest`, `ClPpcreE2eTest`, ci-spec
`special-variable-dynamic-binding`, `progv-compiles-on-every-backend`,
`special-let-restores-on-every-exit`, `top-level-forms-answer-like-defun-bodies`.

JVM shallow vs thread-scoped (ci-spec cannot pin it: its program holds `async-defun`s, so it is
thread-scoped): `JvmLispCompilerTest#aSpecialIsBoundShallowUnlessAnotherThreadCanRunLispCode`
(no `_d$`/ThreadLocal on one thread; every entry of the gate keeps them),
`#aSpecialWithoutAValueCarriesTheUnboundMarkerAndOnlyAProbedBoundOneAsksDbound` (both shapes
of `boundp`),
`#compileAndRunWriteToStringKeywordAloneBindsThePrinterVariable` (the collector, thread-scoped);
threads released at once, each binding and reading its own value:
`JvmExportTest#aSpecialAnExportBindsIsBoundPerCallingThread`,
`JvmAsyncCompilerTest#concurrentAsyncBodiesEachSeeTheirOwnBindingOfASpecial`,
`HttpHandlerJvmTest#concurrentRequestsEachSeeTheirOwnBindingOfASpecial`, and the `java:`
callbacks of `specialVarBindingIsThreadScoped` / `boundpOfASpecialWithoutAValueIsThreadScoped`.
With the gate cut to the thread primitives, every one of those three crossed on every run (the
async test 16 of 16 bodies).

By-name reads and eval'd assignments: `SpecialReadByNameFixture` (a program without `set` or
`progv`, one with both; SBCL's answers) on `aSpecialReadByNameAnswersTheActiveBinding`
(`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` -- Preview 1 and
component), ci-spec `eval-and-symbol-value-read-the-binding`.

set inside an active binding: `SetInDynamicBindingFixture` on
`setWritesTheActiveDynamicBinding` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest`), ci-spec `set-writes-the-active-dynamic-binding`,
`WasmReentrantE2eTest#overlappedCallsEachSetTheirOwnDynamicBinding`
(`.kb/symbol-runtime-api.md`).

progv and set sites: `ProgvSetSiteFixture` (300 specials, four `progv` and four `set` sites a
defun and a top-level form) on `LispEvaluatorTest#progvAndSetSitesBindAndStoreByName`,
`JvmLispCompilerTest#progvAndSetSitesDoNotEachPayForTheSpecialSet` (site methods < 1,000 /
300 B, every segment < 8,000 B), `WasmLispCompilerIntegrationTest#progvAndSetSitesBindAndStoreByName`
(Preview 1 and component), `WasmLispCompilerTest#progvAndSetSitesDoNotPayForTheSpecialSet`
(four more of each < 2,000 B of module; 108,514 B before).

symbol-value sites: `SymbolValueSiteFixture` (300 specials, eight-site defun and top-level
form) on `LispEvaluatorTest#symbolValueSitesReadTheActiveBinding`,
`JvmLispCompilerTest#symbolValueSitesDoNotEachPayForTheSpecialSet` (site method < 500 B,
literal sites call nothing, every segment < 8,000 B) and
`WasmLispCompilerIntegrationTest#symbolValueSitesReadTheActiveBinding` (Preview 1 and
component); `WasmLispCompilerTest#aSymbolValueSiteDoesNotPayForTheSpecialSet` (eight more sites
< 1,000 B of module).

Name dispatch: `NameDispatchFixture` (154 specials, two hash-colliding pairs) on
`aNameDispatchAnswersEveryNameItsChainDoes` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest` -- Preview 1 and component),
`WasmLispCompilerTest#aNameDispatchArmIsAnOffsetCompare` (a 128-arm accessor < 32 B an arm),
`NameDispatchTest`.

Bound-ness without a value: `BoundpInBindingFixture` (SBCL's answers; a read while unbound,
which signals) on `boundpAnswersInsideABindingOfASpecialWithoutAValue` (`LispEvaluatorTest`,
`JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` -- Preview 1 and component), ci-spec
`boundp-of-a-special-inside-its-binding`; `JvmLispCompilerTest#aSpecialWithoutAValueCarriesTheUnboundMarkerAndOnlyAProbedBoundOneAsksDbound`,
`#computedBoundpSitesDoNotEachPayForTheTrackedSpecials`, `#boundpOfASpecialWithoutAValueIsThreadScoped`;
`WasmReentrantE2eTest#overlappedCallsEachSeeTheirOwnBindingOfASpecialWithoutAValue`;
`SpecialVarCollectorTest#aProbedSpecialWithoutADefinersValueCarriesItsBoundnessInItsVariable`.

A read of a special without a value: `UnboundVariableNameFixture` on
`anUnboundVariableCarriesItsNameInTheCellErrorNameSlot` (`LispEvaluatorTest`,
`JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` -- Preview 1 and component) and ci-spec
`unbound-variable-carries-its-name` (thread-scoped on the JVM: the ci-spec program holds
`async-defun`s); `aSpecialWithoutAValueIsUnboundInAThreadThatDoesNotBindIt` (`ThreadTest`,
`JvmThreadTest`); `WasmReentrantE2eTest#overlappedCallsEachReadTheirOwnBindingOfASpecialWithoutAValue`;
`WasmLispCompilerIntegrationTest#anUncaughtReadOfASpecialWithoutAValueEndsTheProgram` (the trap
outside EH mode, the report in it); `UncaughtReportParityTest#aReadOfASpecialWithoutAValueReportsWhereItIsRead`;
`SpecialVarCollectorTest#everySpecialWithoutADefinersValueStartsUnbound`.

A read of a global before its first store: `ReadBeforeStoreFixture` on
`aGlobalReadBeforeItsFirstStoreSignalsUnboundVariable` (`LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest` -- Preview 1 and component) and ci-spec
`a-global-read-before-its-first-store-signals`;
`JvmLispCompilerTest#aGlobalCarriesTheUnboundMarkerOnlyWhereAReadCanComeBeforeItsFirstStore`,
`WasmLispCompilerTest#aGlobalCarriesTheUnboundCheckOnlyWhereAReadCanComeBeforeItsFirstStore`;
`ReadBeforeStoreTest`.

Parameters: `aParameterNamedLikeASpecialBindsItDynamically` on `LispEvaluatorTest`,
`JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` (Preview 1 and component), one
program and one expected text (`SpecialParameterFixture`), and ci-spec
`special-parameters-bind-dynamically`; `JvmLispCompilerTest#aTailCallInsideASpecialParametersBindingStaysACall`
(no jump, no group, values); `LambdaListsTest#aParameterNamedLikeASpecialIsBoundByALetAroundTheWholeBody`,
`#aSpecialParametersLetGoesInsideTheFunctionBlock`, `#aSpecialSuppliedPKeepsItsBinding`;
`SpecialVarCollectorTest#aParameterNamedLikeASpecialIsADynamicBinding`.

Local declarations, no capture: `specialBindingsAreNeverCapturedAndLocalDeclarationsAreScoped`
on `LispEvaluatorTest`, `JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` (Preview 1
and component), one program and SBCL's text (`SpecialBindingScopeFixture`), and ci-spec
`special-bindings-are-dynamic-and-declarations-scoped`; `SpecialDeclarationScopingTest`;
`SpecialVarCollectorTest#aLocalSpecialDeclarationIsNoProclamation`; the closure leg of
`spawnedThreadDoesNotInheritTheSpawnersDynamicBindings` (`ThreadTest`, `JvmThreadTest`).
