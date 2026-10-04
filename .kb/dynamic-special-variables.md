# Dynamic (special) variable binding

A variable proclaimed *special* is bound with dynamic extent by `let`/`let*`/`progv`, by SHALLOW
BINDING -- the value lives in the ordinary global cell and a binding is save/set/restore over it.
On the interpreter and the JVM that cell is THREAD-scoped; WASM is not. Docs:
`doc/en/reference/special-forms/{progv,let,defvar,defparameter}.md`.

## What proclaims a name special

`SpecialVarCollector` (`am.ik.rontolisp`, the shared AST layer, so the interpreter -- which must
not depend on `compiler` -- can use it): `defvar`/`defparameter`/`defconstant`,
`(declaim (special ...))`, `(proclaim '(special ...))`. `LispNames.SPECIAL` is NOT registered as
a cl symbol (registering it would perturb pinned introspection counts). Earmuffs are style.

- Local `(declare (special x))` IS honored PESSIMISTICALLY: `collectForm` recurses into every
  form (skipping `quote`) and a name declared special anywhere is special program-wide
  (cl-ppcre's convert phase).
- `declare`/`special` heads are matched package-insensitively (`splitQualified`) -- under
  `(in-package p)` the resolver spells them `p::declare`/`p::special`.
- The interpreter collects at the top-level `eval(expr)` entry BEFORE evaluating.
- Symbol reads consult the dynamic store BEFORE the lexical chain, so a lambda or macro parameter
  whose name is special must ALSO bind dynamically (`LispEvaluator.apply` / `expandUserMacro`
  push/pop `DynamicBindings`; the compile paths: "Parameters" below).
- A parameter is a binding the collector sees: `collectBoundForm` reads a `lambda`/`defun`
  lambda list (every section, supplied-p variables, default forms walked in order), so
  `(defun f (*standard-output*) ...)` makes the stream special special, as a `let` of it does.
- A special is always ALSO a global; on the compile path specials are unioned into the
  `GlobalVarCollector` set.

## Interpreter (`LispEvaluator`) -- full fidelity, thread-scoped

- `DynamicBindings` (`eval`): per-evaluator `ThreadLocal<Map<String, Deque<LispVal>>>`;
  `specialVars` = `ConcurrentHashMap.newKeySet()`.
- `evalLet` is two-phase when `specialVars` is non-empty (all inits in the outer env, then push
  specials / bind lexicals), `finally` pops; `let*` reuses it via `expandLetStar`. A fast
  lexical-only path runs when `specialVars` is empty.
- `evalSymbolRef`, `setq`, `symbol-value`, `boundp` consult `DynamicBindings` first, gated on
  `!specialVars.isEmpty() || progvUsed`. `evalProgv` sets `progvUsed`; extra symbols -> nil;
  progv-bound names need not be declared special. Restore fires on EVERY exit.

## JVM (`JvmLetCompiler`) -- thread-scoped, hybrid representation

- A special NEVER dynamically bound keeps the bare `_g$*` static field (one `getstatic`).
- A special that IS bound -- decided by `SpecialVarCollector.collectDynamicallyBound`, which
  walks the fully expanded program and single-step-expands built-in binding macros via
  `LispMacroExpander.expandBuiltinMacro` -- also gets a `private static ThreadLocal _d$*`,
  created in `<clinit>`, **NEVER lazily** (a racy first bind would mint two ThreadLocals),
  holding a one-element `Object[]` CELL. **A cell, not the value**: `nil` is Java `null`, so the
  value cannot mark "no binding on this thread". Over-collection costs a read;
  under-collection throws in `JvmLetCompiler` at compile time.
- Helpers (`JvmDynVarRuntimeBuilder`): `_dget`, `_dbind`, `_dset` (answers 0 when no binding, so
  the call site falls through to `putstatic _g$*`). `Ctx.dynVars` carries the fields.
- A special binding in `let` is a DUAL-BIND: `_dbind` the old cell into a temp AND store the
  value into a lexical slot (boxed when captured). The lexical slot exists ONLY so a closure
  built in the body can read the entry value after the extent ended (cl-ppcre's `end-string`).
- Read rule is DYNAMIC-FIRST (`JvmExprCompiler.compileSpecialRead`) so a callee's rebinding is
  visible; inside a closure the CAPTURE wins. `setq` of a dual-bound name writes BOTH
  (`JvmSetqCompiler.emitGlobalStore`); with no active binding it lands in `_g$*`.
- **The body is a PROTECTED REGION of the unwind-protect machinery**
  (`JvmUnwindProtectCompiler.Region`, opened by `JvmLetCompiler`) whose cleanups are the
  internal `(%dyn-restore tlField saveSlot)` forms (`LispNames.DYN_RESTORE_INTERNAL`), innermost
  first. So the restore rides every exit channel that machinery covers: normal completion, the
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
  (`JvmLispCompilerTest.lexicalGlobalLetStaysLexical`).
- A spawned thread does NOT inherit the spawner's bindings; `rontolisp:make-thread`'s bindings
  alist is the hand-over, and a program using the thread primitives forces EVERY special into
  the dynamically-bound set (`.kb/threads.md`).

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
  `Ctx.specialVars`, dual-bind, dual `setq` (`WasmSetqCompiler`), and the same protected region
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
    tail-calling the next segment (`%global-access-1`, ...). ~22 B of JVM bytecode a read arm, so
    a segment is under the 64 KB limit for any special count and under HotSpot's 8,000-byte
    `HugeMethodLimit`, so the JIT compiles it. Injected by both backends after the global set is
    final, when the program, the injected runtime or a condition report can reach a computed site
    (`programUsesComputedSymbolValue`: any `SYMBOL-VALUE` but `(symbol-value 'x)`). A site whose
    program lacks it (a user defun took a name, or a site no scan saw) spells the old inline
    chain: correct, at the old size.
  - Before: every site, literal or computed, inlined the whole chain -- ~12 KB a site at
    ci-spec's ~359 specials, so a defun or top-level form with a few sites overflowed 64 KB
    (ci-spec's `top-level-forms-answer-like-defun-bodies` was cut to one site a form;
    restored to four a form). A method over 8,000 B is never JIT-compiled, so a computed
    site also ran interpreted: 2M lookups over 300 specials 10.8 s -> 2.1 s on the JVM; wasm
    unchanged at ~22 s (the `equal` test itself: 1.5 s after `.todo/c81`, below).
  - Measured 2026-10-04, the ci-spec program as of `HEAD` (before -> after): JVM classes
    7,846,439 -> 7,538,586 B (-3.9%), wasm 6,881,325 -> 6,749,210 (-1.9%), component
    7,094,646 -> 6,961,175 (-1.9%); with the restored four-site case the JVM build failed
    before (`_top$55`, 107,364 B) and is 7,539,091 after. size-report, bench-report: every
    program byte-identical on all three outputs (none uses progv). examples: byte-identical
    but for build-info strings, except `jvm/cffi-sqlite.lisp` +264 B (2,353,838 ->
    2,354,102): its one computed site (cffi's `mini-eval`, 3,818 -> 200 B) now pays the
    shared runtime (3,483 B) and its name-table entries -- a one-site program is the
    break-even's losing side. It counts as progv-using only because a code walker quotes
    `PROGV` (`programUsesSymbol` counts quoted data).
- The name test of every dispatch over the special or global set is the internal
  `(%symbol-is n 'S)` (`nameChain`): the JVM `"S".equals(n)` straight into the branch, wasm a
  `ref.test` + string-table-offset compare (the canonical offset `eq` already relies on). The
  `equal` it replaced built the symbol and called the structural `_equal` on wasm, then boxed
  the answer -- that, not the chain length, was wasm's ~11 us a lookup. The quoted name is not a
  spelled designator (`compileUnspelledLiteral` / `stringTable.addString`).
- The shared dispatches are CALL-ONLY: neither backend gives them a `_invoke_N` /
  `_lookup` row (`dispatchableFuncIds`' `callOnly`), even when names resolve at run time. A
  library holding a `set` or `progv` site the shaker later drops (cl-ppcre's, ningle's) would
  otherwise keep the whole runtime alive through the dispatchers: +12 to +20 KB of wasm on the
  str / ningle / postgres examples before this rule. Pinned by
  `JvmLispCompilerTest#theSharedNameDispatchesAreNeverDispatcherTargets`.
- Before the shared progv runtime, each site spelled both chains inline: ~36 KB of JVM bytecode a
  site at ci-spec's ~359 specials, 600 specials overflowed one top-level `progv` form
  (`_top$0`, 72,093 B), and four sites over 300 specials 129,765 B. A 315-special program
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
- Measured 2026-10-04 (`.todo/c81`, before -> after). ci-spec program: JVM classes 7,539,127 ->
  7,294,185 B (-3.2%), wasm 6,749,167 -> 6,635,076 (-1.7%), component 6,961,133 -> 6,846,025,
  `--optimize=size` 5,578,881 -> 5,468,746; largest method 54,767 -> 28,088 B, `tlf-bound`
  36,341 -> 317 B. `ProgvSetSiteFixture` (300 specials): the four-progv-site top-level form
  129,765 B (failed) -> compiles, `pvs-four` 741 B, `pvs-set-four` 222 B; wasm module 280,816 ->
  60,244. Run time over 300 specials, half the names on the last arm: 2M computed
  `symbol-value` JVM 2.36 -> 1.28 s, wasm 22.5 -> 1.5 s; 1M two-name `progv` JVM 22.9 -> 2.35 s,
  wasm 54.2 -> 2.9 s; 2M computed `set` JVM 5.2 -> 2.2 s, wasm 2.9 -> 3.2 s (the mirror's
  `_store` walk dominates `set`; the per-arm `ref.test` and the call cost the rest).
  size-report, bench-report: byte-identical on P1, `--optimize=size`, component and JVM.
  examples: byte-identical but for build-info strings, except `jvm/cffi-sqlite.lisp` JVM
  2,424,232 -> 2,422,098 and the twelve holding a DEAD `set` site (str-demo, the ningle
  workers, postgres/postmodern/bbs-api) at +70 to +147 B wasm, +3 B JVM: same strings, a
  different string-table order (the runtime compiles where the shaken site did not).
- The literal-`boundp` fold refuses progv programs (`CompileTimeBoundp.fold` gate).
- Deliberate divergences: a non-symbol in the symbols list is not detected, and a closure that
  CAPTURED a special reads its capture under a LITERAL `(symbol-value '*x*)` (it folds to the
  variable read, as `*x*` itself reads); a computed name reads in the shared dispatch's own
  frame, so it answers the dynamic binding, as the interpreter does (before 2026-10-04 both
  read the capture).

## Compile-path limitations (interpreter unaffected)

1. Exit restores are covered on EVERY channel on all four backends since 2026-09-12 -- an error
   caught outside the `let` (same frame or across a callee's), `catch`/`throw`, `go`, a plain
   `return` through the wasm trampoline cascade in either nesting order against an
   `unwind-protect`, and a `return-from` crossing a lambda boundary (cl-ppcre's scanner shape:
   a failing register scan used to leak `*reg-starts*` into every later zero-register scan).
   Pinned by `specialLetRestoresOnEveryExit` on `LispEvaluatorTest` / `JvmLispCompilerTest` /
   `WasmLispCompilerIntegrationTest` (P1 + component), ci-spec
   `special-let-restores-on-every-exit`, and `ClPpcreE2eTest`'s failing-one-register scan
   between two zero-register scans. What is NOT an exit: a wasm-GC RAW trap (`(car 5)`, a failed
   cast) ends the module, restore moot. The mechanism is thrower-side (each binding frame
   restores its own on the way out), which is why no catch-site save stack was needed: the
   `.todo/192` sketch's objection -- the slots live in the thrower's dead frames -- holds only
   for a CATCHER doing the restore.
2. `boundp` of a special with no global value answers nil inside a binding of it (SBCL: t),
   and t after a callee's `setq` inside a binding gave the mirror an entry the extent does not
   remove. Its answer is the `_genv`/`GLOBAL_ENV` mirror's entry, a witness of "a global store
   happened", because neither variable representation has an unbound state (`nil` is Java
   `null` / a null ref). VALUES are right since 2026-10-04: `symbol-value` and `eval` read a
   special through its variable (the one-home rule below). Until then they read the mirror,
   which the shallow save/restore never updates: the global default inside a binding, and after
   a store from a frame not holding the binding's lexical slot (a callee's `setq`, any `set`)
   the binding's value long after the extent. `.todo/c95`.

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
  `boundp`, `symbol-value`, `fboundp` and, on the JVM, the Java, Objective-C and FFI bridges --
  those modules stay byte-identical. The compiled mirror (`mirrorGlobal`) still writes every
  store: it is the value of every non-special global and the `boundp` witness of every name.
- Measured 2026-10-04 (before -> after). size-report, bench-report: byte-identical on P1,
  `--optimize=size`, component and JVM, except the Cloudflare Worker rows, whose libraries run
  `eval`: hello-clack 697,130 -> 698,493 (+1,363; gzip 185,160 -> 185,513), tiny-routes +1,518 to
  +2,993, hello-ningle / httpbin-ningle +16.4 KB (+0.7%; gzip +4.7 KB). examples: byte-identical
  except the eval / `symbol-value` programs: calc +474 B JVM / +246 wasm, cffi-sqlite +3,269
  JVM, the clack / tiny-routes / ningle programs +0.8 to +21 KB (ningle's accessor is ~200 arms,
  9 KB of wasm, plus the names it needs in the string table). ci-spec program: JVM 7,334,695 ->
  7,336,257, wasm 6,647,482 -> 6,641,111, component 6,857,938 -> 6,851,479, `--optimize=size`
  5,480,695 -> 5,474,319 (one accessor where `%symbol-value-dynamic` and `%set-global-store` were
  two chains). A wasm arm is ~44 B (`ref.test` + cast + offset compare per arm, `.todo/c94`).
- Run time: 2M computed `symbol-value` over 300 specials JVM 1.22 -> 1.19 s, wasm 1.39 -> 1.46 s
  (the default cons and the wrapper call). An eval'd variable access walks the accessor before
  the mirror: 200k `(eval '(setq *ex* (+ *ex* *e99*)))` over 101 specials, the names last in
  declaration order, JVM 125 -> 370 ms, wasm 43 -> 170 ms (the mirror found both at its head).

## Parameters named like a special (all four backends, 2026-10-03)

**Invariant: a parameter whose name is special -- any section of the lambda list, a supplied-p
variable included, of a `defun`, a `lambda`, an `flet`/`labels` local or a built-in expansion's
lambda (uiop's `with-*`, `async-lambda`) -- binds it dynamically, restored on every exit.**
Landed 2026-10-03 (`.todo/c06`). Before, the compilers bound a required or rest one lexically:
`(defvar *x* 1) (defun show () *x*) (defun f (*x*) (show)) (f 2)` answered 1 on the JVM and
both WASM, 2 on the interpreter, and a `setq` of such a parameter wrote the global.

- Interpreter: `LispEvaluator.apply` dual-binds a special required/rest parameter
  (`lexicalLambdaScope` declines the lambda, so the call keeps a Java frame); the `let*` prologue
  binds the other sections.
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
  BEFORE the desugaring missed those standard variables -- cl-json's progv fallback covered 42
  instead of 53 specials (class 424,786 -> 410,383 B), ci-spec lost 65 KB. A size "win" that
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
- A closure capturing such a parameter reads the `let`'s capture: after the extent, with no
  other binding active, every backend answers the argument (SBCL: the global value -- the dual
  binding's deliberate departure); called inside ANOTHER binding of the name, the interpreter
  answers that binding (dynamic first, as SBCL does) and the compilers the capture -- the
  divergence a special `let` already had (`.todo/c14`).
- `--component`: a special parameter of an async function whose body awaits is
  `WasmLetCompiler`'s "dynamic binding around `rontolisp:await`" refusal.
- wasm's `WasmArityBundler` binds a defun of more than 10 parameters through a `let` from its rest
  list, so cl-ppcre's 12-parameter `create-scanner-aux` bound its special parameters dynamically
  on wasm before this change and lexically on the JVM.
- Census (2026-10-03, every lowered parameter printed during a JVM compile): ci-spec,
  clojure-spec, scheme-spec: none. Vendored libraries: cl-ppcre only (rove and uax-15 load it):
  `create-scanner-aux` (`starts-with`, `end-anchored-p`, `end-string-offset`, `reg-num`) and
  `maybe-split-repetition` (`reg-seen`), special only through the program-wide reading of a local
  `(declare (special ...))`, both called when a regex compiles, not when it matches (wasm lowers
  the second only: the bundler took the first's twelve parameters).
- Cost: a program without a special parameter compiles byte-identically on the JVM, wasm and
  the component (ci-spec, its build-info strings aside, clojure-spec, scheme-spec and 13
  vendored libraries loaded alone), so it runs at the same speed. cl-ppcre: JVM 734,954 ->
  736,159 B (+0.16%), wasm 559,537 -> 559,455, component 564,059 -> 563,919 (wasm loses a
  docstring `maybe-split-repetition` used to evaluate for effect); 3,000 scanner creations
  JVM 1,067-1,089 -> 1,048-1,126 ms, wasm 1,005-1,149 -> 1,000-1,127 ms, i.e. noise, and the
  scan path's code is unchanged.

## The two hand-rolled precedents

- `*package*` has TWO faces: resolution-time (`PackageResolver`, `in-package`,
  `%push-package`/`%pop-package` markers) and run-time (a genuine variable kept in step by
  resolving `in-package` to `(setq *package* :P)`). On the compile paths the run-time face is an
  ordinary special of this file; on the interpreter the two faces are ONE cell, which is why it
  is the one special the interpreter does NOT thread-scope. `.kb/packages.md`.
- Macro-time setf replay is separate: `UserMacroExpander` replays a top-level
  `(setf (PLACE) ...)` into its macro-time evaluator under a deny-by-default purity judgment
  (`isPureConfigSetf`/`isPure`). `.kb/asdf.md`.
- A macro-time global's VALUE is demand-driven; its SPECIAL proclamation is not.
  `LispEvaluator.registerLazyGlobal` adds the name to `specialVars` at once but parks the value
  as a thunk (`Environment.defineLazy`). Two couplings fail SILENTLY (wrong value, not a crash):
  `Environment.isBound` must count a pending thunk, and `Environment.set` must DISCARD one, or
  a replayed `(setf *html-mode* :html5)` is later overwritten by the original `defvar` default.

## Cost of the every-exit restore (2026-09-12)

Minimal programs, JVM `.class` / wasm Preview 1 bytes, before -> after (the `.class` is the
bigger mover because each region adds an exception-table entry, a handler and two stack-map
frames; wasm adds only the trampoline block, and in EH mode the pad):

| program | JVM | wasm |
|---|---|---|
| `(print (+ 1 2))` | 3,948 -> 3,948 | 489 -> 489 |
| `(defvar *x* 1) (print (let ((*x* 2)) *x*))` | 4,468 -> 4,522 | 11,175 -> 11,175 |
| the same `let` inside a `dolist` | 4,815 -> 4,847 | 11,383 -> 11,397 |
| the same `let` under a `handler-case` (EH mode) | 11,011 -> 11,074 | 13,732 -> 13,761 |
| `(unwind-protect (+ 1 2) (print *x*))`, no special `let` | 4,105 -> 4,105 | 12,375 -> 12,375 |
| cl-ppcre (`asdf:load-system` + three scans) | 725,206 -> 755,211 (+4.1%) | 693,247 -> 700,485 (+1.0%) |

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

Parameters: `aParameterNamedLikeASpecialBindsItDynamically` on `LispEvaluatorTest`,
`JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` (Preview 1 and component), one
program and one expected text (`SpecialParameterFixture`), and ci-spec
`special-parameters-bind-dynamically`; `JvmLispCompilerTest#aTailCallInsideASpecialParametersBindingStaysACall`
(no jump, no group, values); `LambdaListsTest#aParameterNamedLikeASpecialIsBoundByALetAroundTheWholeBody`,
`#aSpecialParametersLetGoesInsideTheFunctionBlock`, `#aSpecialSuppliedPKeepsItsBinding`;
`SpecialVarCollectorTest#aParameterNamedLikeASpecialIsADynamicBinding`.
