# Dynamic (special) variable binding

Common Lisp's second binding discipline (alongside lexical): a variable proclaimed
*special* is bound with **dynamic extent** by `let`/`let*`/`progv` -- the binding
is visible to every function called during the body (not just lexically nested
code) and restored on exit. rontolisp implements this via **shallow binding**: a
special's value lives in its ordinary global cell, and a dynamic binding is a
save/set/restore over that cell -- on the interpreter and the JVM backend the
cell a binding writes is THREAD-scoped (see the per-backend sections), so
concurrent HTTP-handler requests do not clobber each other. Landed 2026-07-06
(todo-084); JVM thread-scoping 2026-07-27. Reference doc:
`doc/en/guides/missing-features.md` ("Dynamic (special) variable binding"),
`doc/en/reference/special-forms/{progv,let,defvar,defparameter}.md`.

## What proclaims a name special

`SpecialVarCollector` (`am.ik.rontolisp`, shared AST layer so the interpreter --
which must not depend on the `compiler` package -- can use it too) collects the
names: `defvar`/`defparameter`/`defconstant`, plus `(declaim (special ...))` /
`(proclaim '(special ...))`. `LispNames.SPECIAL` is the `special` declaration
identifier (NOT registered as a cl symbol -- it only appears inside a declaration
specifier, and registering it would perturb the pinned introspection counts). The
earmuffs (`*x*`) are a style hint, not the mechanism. Local `(declare (special
x))` IS honored, PESSIMISTICALLY: `SpecialVarCollector.collectForm` recurses
into every form (skipping `quote`) and a name declared special anywhere becomes
special program-wide -- the same treatment a `declaim` would give it (cl-ppcre's
convert phase threads its whole state through let-bound locally-declared
specials). The `declare` head and the `special` clause head are matched
package-insensitively (`splitQualified`): neither is a registered cl symbol, so
under `(in-package p)` the resolver spells them `p::declare`/`p::special`. The
interpreter collects at the top-level `eval(expr)` entry, BEFORE evaluating, so
a defun's local declares take effect for everything defined after it. Because
symbol reads consult the dynamic store BEFORE the lexical chain, a lambda or
macro parameter whose name is special must ALSO bind dynamically
(`LispEvaluator.apply`/`expandUserMacro` push/pop `DynamicBindings` for such
params) -- otherwise an active outer dynamic binding shadows the parameter. A
special is always ALSO a global (its default value when unbound); on the
compile path specials are unioned into the `GlobalVarCollector` set so each
gets a backing store.

## Interpreter (`LispEvaluator`) -- full fidelity, thread-scoped

- `DynamicBindings` (`eval` pkg): a per-evaluator `ThreadLocal<Map<String,
  Deque<LispVal>>>` -- per-name value stacks, thread-scoped. **Thread-scoping is
  the point**: the HTTP handler serves one virtual thread per request sharing the
  single `globalEnv`, so concurrent requests must not clobber each other's
  dynamic bindings (`LispEvaluatorTest.specialVariablesAreThreadScoped`).
- `specialVars` = `ConcurrentHashMap.newKeySet()` on the evaluator, filled by
  `evalDefvar` and the `declaim`/`proclaim` cases (via `SpecialVarCollector`).
- `evalLet`: two-phase when `specialVars` is non-empty (evaluate ALL inits in the
  outer env first -- parallel `let` -- then push specials / bind lexicals),
  `finally` pops. `let*` reuses `evalLet` via `expandLetStar` (nested single-binding
  lets). A fast lexical-only path (no allocation, no `finally`) runs when
  `specialVars` is empty, so the pure-lexical common case is untouched.
- Read (`evalSymbolRef`), `setq`, `symbol-value`, `boundp` consult
  `DynamicBindings` first (gated on `!specialVars.isEmpty() || progvUsed` so the
  hot path skips `ThreadLocal.get` -- and the HashMap it would allocate -- when no
  special/progv is ever used).
- `evalProgv`: runtime-computed symbol/value lists; binds each dynamically (extra
  symbols -> nil), sets `progvUsed`. progv-bound names need not be declared special.
- Restore fires on EVERY exit: normal return, non-local exit (`BlockReturnSignal`),
  error unwind (`LispEvalException`) -- all unchecked, so the `finally` runs.

## JVM (`JvmLetCompiler`) -- thread-scoped shallow binding (hybrid representation)

**Thread-scoped since 2026-07-27** (interpreter parity; before that the binding
was shallow save/restore over the one process-global static field, so two
HTTP-handler requests binding the same special clobbered each other -- the
real-world bite was cl-postgres' `initiate-connection` binding
`*connection-params*`: concurrent `open-database` calls filled each other's
hash table and died on `(string= (gethash "integer_datetimes" ...) "on")` with
a null; 11 of 12 concurrent requests answered 500).

The representation is **hybrid, paid only where the semantics need it**:

- A special that is NEVER dynamically bound keeps the bare `_g$*` static field;
  its reads stay a single `getstatic` and such programs compile byte-identically
  to the pre-thread-scoped output.
- A special that IS bound somewhere -- decided by
  `SpecialVarCollector.collectDynamicallyBound`, which walks the fully expanded
  program for `let`/`let*` binding names, single-step-expanding built-in binding
  macros (`do`/`dolist`/`loop`/`multiple-value-bind`/`with-*`/...) via
  `LispMacroExpander.expandBuiltinMacro` so their generated lets are seen --
  additionally gets a `private static ThreadLocal _d$*` (created in `<clinit>`,
  never lazily: a racy first bind would mint two ThreadLocals) holding the
  thread's innermost binding as a one-element `Object[]` cell. A CELL, not the
  value: `nil` compiles to Java `null`, so the value itself cannot mark "no
  binding on this thread". Over-collection is only a small read cost;
  under-collection throws in `JvmLetCompiler` at compile time (never a silent
  process-global binding). Three shared helpers are emitted
  (`JvmDynVarRuntimeBuilder`): `_dget(tl, global)` (dynamic-first read),
  `_dbind(tl, v)` (push a binding, answer the previous cell), `_dset(tl, v)`
  (write the active binding, answer 0 when none so the call site falls through
  to `putstatic _g$*`).

The pre-existing rules keep working on top of that store:

- `Ctx.specialVars` threaded from `SpecialVarCollector.collect` (unioned into
  `globals` in `JvmLispCompiler` so each special has a `_g$*` static field --
  the global default `_dget` falls back to); `Ctx.dynVars` carries the
  ThreadLocal fields + helper refs (null when no special is ever bound).
- A special binding in `let` is a **DUAL-BIND** (2026-07-19, the cl-ppcre
  compile-path work): compile init, `DUP`, `_dbind` old cell into a temp (the
  dynamic set a called function reads), AND store the same value into a lexical
  slot (boxed when captured). The lexical slot exists ONLY so a closure built in
  the body CAPTURES the entry value and can read it after the extent ended
  (cl-ppcre's `end-string`, read by matcher closures at match time).
- **Read rule (dynamic-first)**: in the binding method, a read of the special
  resolves through `_dget`, not the slot (`JvmExprCompiler.compileSpecialRead`)
  -- so a callee's rebinding/`setq` is visible (cl-ppcre's `starts-with`
  accumulation threads state through callees). Inside a closure the CAPTURE
  wins. A `setq` of a dual-bound name writes BOTH the slot/capture and the
  dynamic store (`JvmSetqCompiler.emitGlobalStore`); with no binding active on
  the thread the store lands in `_g$*` -- the CL rule that `setq` of a special
  assigns the current dynamic binding.
- Restore after the body is `GETSTATIC _d$*; ALOAD temp; ThreadLocal.set`
  (stack-neutral), and each binding is pushed on `Ctx.specialBindScopes`
  (`{tlField, saveSlot, blockDepth}`) so a `return`/`return-from` exiting an
  enclosing block ALSO restores it (`JvmReturnCompiler.emitExit`) -- without
  this the scan closure's named exit leaked `*reg-starts*` into the dynamic
  store and the next scan saw stale registers.
- `defvar` of a special is unchanged (writes the `_g$*` global default). `let*`
  -> nested lets. Non-special globals stay lexical under `let`
  (`JvmLispCompilerTest.lexicalGlobalLetStaysLexical`).
- A spawned thread does NOT inherit the spawner's bindings (plain ThreadLocal,
  like the interpreter's `DynamicBindings`): it reads the global default until
  it binds for itself. Pinned by
  `JvmLispCompilerTest.specialVarBindingIsThreadScoped` (Thread.start/join via
  `java:` interop, fully deterministic) and, with user code as the spawner
  since todo-227, `JvmThreadTest.spawnedThreadDoesNotInheritTheSpawnersDynamicBindings`.
  `rontolisp:make-thread`'s bindings alist is the supported way to hand a
  spawned thread dynamic bindings -- and when a program uses the thread
  primitives, EVERY special is forced into the dynamically-bound set (plus the
  three stream specials into `specialVars`) so the runtime `_dtl` name dispatch
  can bind any of them; see `.kb/threads.md`.

## WASM (`WasmLetCompiler`) -- shallow binding over the module global

**Deliberately NOT thread-scoped, unlike the interpreter and the JVM backend
above.** Reason for the divergence (re-evaluate if it stops holding): WASM has
no threads here -- a served component's concurrent tasks interleave on ONE
instance's single stack, never preempting inside a synchronous handler body, so
overlapping requests cannot observe each other's shallow binding; the
`.todo/189` reproduction ran 8/8 clean under both `wasmtime serve` and `wash
dev` while the pre-2026-07-27 JVM lost bindings. If the wasm backends ever gain
real threads (shared-memory threads proposal) or a host that suspends a handler
MID-extent (an `await` inside a special `let` in a served handler), this
divergence becomes the same bug the JVM had and the module global needs a
per-task store. This is NOT a claim that the wasm backends are concurrency-safe
in general -- see `.todo/190` for a wasmCloud concurrency trap with a different
cause.

**That trigger FIRED (2026-08-12), and the answer is a refusal at the export
boundary, plus -- opt-in -- a per-task store.** A `--no-wasi` reactor whose host
answers an import through JSPI (`WebAssembly.Suspending`, `.kb/wasm-import.md`)
suspends the whole wasm stack mid-call and hands control back to the host's
event loop, which may re-enter the export before the first call resumes --
exactly the "suspends a handler MID-extent" condition above, arriving through a
host import rather than through `await`. Reproduced on node 24
`--experimental-wasm-jspi` (todo-337): two overlapped calls each binding the
same special across the suspend read each other's binding back and leaked the
outer value -- the exact pre-2026-07-27 JVM bug. The fix landed as the RE-ENTRY
GUARD (todo-337, `.kb/wasm-import.md`): every export wrapper of a module that
can suspend traps a second entry, so no two extents can interleave on the
module global and the divergence's precondition -- one call at a time on one
stack -- is re-established BY the module instead of assumed of the host.

**The restated trigger fired too (todo-348, 2026-08-15): `--reentrant` relaxes
the guard, and the per-task store landed WITH it, first in the same change.**
The shape is the JVM `_d$` hybrid ported to a per-CALL scope
(`codegen.wasm/WasmDynVars`): only the specials
`SpecialVarCollector.collectDynamicallyBound` names get a slot in a per-call
TASK RECORD -- a `TYPE_HASH_BUCKETS` array of nullable `TYPE_CELL`s in a module
global -- which every export wrapper creates on entry, `_start` seeds for the
load path, and the import wrapper saves into a local and restores around the
suspending host call (the one point another extent can run; a wrapper local
survives the park). The JVM hybrid's rules carry over exactly: dynamic-first
reads with the module global as the default, DUAL-BIND with the lexical slot for
captures, dual `setq` (an active binding's cell, else the global -- the CL
rule), `specialBindScopes` exit restores in the second spelling, over-collection
a read cost and under-collection a compile-time throw at the binding site. The
`.todo/192` unwind holes carry over unchanged -- neither widened nor narrowed.
Every non-reentrant module is byte-identical (the guard and the divergence above
stay its contract); a reentrant module with no dynamically-bound special gains
no task global. Pinned by
`WasmReentrantE2eTest.overlappedCallsEachReadTheirOwnDynamicBinding` -- the
todo-337 reproduction with its expectation inverted -- and
`WasmReentrantCompilerTest`.

Same shape as the pre-thread-scoped JVM design: `Ctx.specialVars`, specials
unioned into `globals` (module-level
`(mut (ref null eq))`). A special binding saves the global into a temp local
(`global.get; local.set`), sets it, dual-binds a lexical slot (cell-boxed when
captured), and restores after the body (`local.get; global.set`); the same
dynamic-first read rule, dual `setq` (`WasmSetqCompiler`) and
`Ctx.specialBindScopes` exit restores (`WasmReturnCompiler` direct-br path and
`WasmReturnFromCompiler`) apply. Same for the `--component` path (shared core
module). `--no-gc`
`NoGcWasmCompiler` has no globals and rejects `defvar`/`declaim` at top level
outright, so a special can never be declared there
(`NoGcWasmCompilerTest.rejectsSpecialVariableDeclaration`).

## progv on the compile paths (2026-08-18, todo-423)

`progv` compiles on the JVM and both WASM backends. The bound symbols are
runtime values, but the set of candidate SPECIALS in a compiled program is
static -- that asymmetry is the whole design
(`LispMacroExpander.expandProgvForCompile`, shared by both compilers; the
interpreter keeps its native `evalProgv`):

- The form lowers to a loop over the runtime symbol list whose body dispatches
  each name through an `equal` chain over the program's special set; a matching
  arm is `(%progv-dyn-bind NAME value)` -- a literal-name internal operator
  (`Jvm/WasmProgvCompiler`) emitting exactly the save-and-set the `let` path
  emits for that special (JVM: `_dbind` over the `_d$` ThreadLocal; WASM: the
  module global, or the per-task slot under `--reentrant`,
  `WasmDynVars.emitProgvBind`). The previous binding state flows as a VALUE
  consed onto a save list (not a save slot: bind and restore sit in different
  loop iterations).
- Because no static walk can name the specials a `progv` touches,
  `SpecialVarCollector.collectDynamicallyBound` returns EVERY special of a
  progv-using program (the make-thread rule; over-collection is a read cost),
  and `collectForm`'s stream-special probe therefore also marks the three
  stream specials special there.
- The restore loop is the cleanup form of an `unwind-protect`, so every exit
  the compilers cover for unwind-protect -- normal completion, an error
  unwinding past the form, a `return-from`/`go` out of the body -- restores
  through the same emitter, and the `.todo/192` holes are neither widened nor
  narrowed. On WASM this is why `progv` FORCES EH MODE (`usesEhForm` lists it;
  run with `-W exceptions=y`).
- A name in NO arm (CL lets `progv` bind an undeclared symbol) is bound in the
  eval runtime's global env mirror (`_genv`/`GLOBAL_ENV`) -- what
  `symbol-value`/`boundp` read on the compile paths -- via
  `%progv-genv`/`%progv-genv-set`, which expose the mirror as a Lisp alist
  (its nodes ARE cons cells on both backends), so the maintenance -- mutate an
  existing binding and restore it, or prepend and unlink -- is plain Lisp in
  the expansion. Mirror maintenance is included only when the eval runtime
  exists (`Ctx.evalStoreRef != null` / `Ctx.usesEval` -- progv does NOT force
  it; everything that can observe the mirror forces it by itself). Both flags
  are carried by EVERY compile context, not just the top-level one, precisely
  so a progv inside a defun still maintains the mirror (the top-level-only
  consumers keep their own `ctx.topLevel` guard).
- **`symbol-value` is DYNAMIC-FIRST in a progv-using program**
  (`LispMacroExpander.dynamicFirstSymbolValue`, gated on `Ctx.usesProgv`): the
  runtime name dispatches over the special set and a match reads the VARIABLE
  (the `_dget`/global/per-task read), falling back to the raw mirror probe
  (`%symbol-value-raw`). This is what makes cl-json's
  `(progv vars (mapcar #'symbol-value vars) ...)` snapshot see the values its
  decoder `setq`s inside the enclosing extent -- the mirror alone cannot,
  because `setq` does not write it (limitation 2 below). Programs without
  progv keep the raw emission byte-identically. `#'symbol-value` also has a
  REFERENCE-GATED `BuiltinFunctionWrappers` entry now: the `(function
  symbol-value)` spelling that injects the wrapper is a symbol occurrence the
  `usesEval` scan counts, so the wrapper's body is always real.
- The literal-`boundp` fold refuses progv programs
  (`CompileTimeBoundp.fold` gate): the lowering can create a runtime-named
  mirror binding, so a literal probe is no longer decidable there.

Deliberate divergences from the interpreter (both narrow): a non-symbol in the
symbols list is not detected (it matches no arm and binds a useless mirror
entry instead of signalling), and a closure that CAPTURED a special reads its
capture even under `symbol-value` (the dual-bind capture rule wins inside a
closure). Consumer that drove this: cl-json's decoder
(`aggregate-scope-progv` re-binds scope variables around every aggregate), so
any program loading cl-json used to fail to compile whole; pinned by
`ClJsonE2eTest` on all four backends.

## Compile-path limitations (interpreter is unaffected)

1. **Exit restores are covered for `return`/`return-from`** since 2026-07-19
   (`Ctx.specialBindScopes`, see above) -- for exits compiled as a DIRECT branch
   inside the binding function. Remaining holes (all tracked in
   `.todo/192`): a WASM plain `return` that ALSO crosses an
   `unwind-protect`/`handler-case` region goes through the trampoline cascade,
   which does not know the save slots and skips the restores; `go` across a
   special `let` does not restore either; an ERROR caught by a
   `handler-case` outside the `let` skips the restore too (measured 2026-07-27:
   `(handler-case (let ((*x* 2)) (error "boom")) (error (e) *x*))` answers 2 on
   the JVM and leaves 2 bound after the handler; on the JVM the leak lands in
   the THREAD's dynamic store, so a per-request virtual thread takes it to the
   grave -- before thread-scoping it leaked into the process global); and a
   `return-from` that crosses a LAMBDA boundary (the block-exit throw/catch
   lowering) skips the restore on the JVM and both wasm-GC backends (found
   2026-08-08; reproducer and the cl-ppcre scanner corruption it causes --
   stale `*reg-starts*` returned by any zero-register scan after a failing
   register-regex loop -- are in `.todo/192`, which also records why the fix
   needs a save STACK, not catch-site slot restores: the slots live in the
   thrower's dead frames). The interpreter's `finally` covers every exit.
2. **`symbol-value`/`boundp`/`eval` see the global default, not a dynamic binding,
   on the compile path** -- EXCEPT `symbol-value` in a progv-using program,
   which is dynamic-first (see above). Those read the `_genv`/`GLOBAL_ENV` eval mirror, which
   the shallow save/restore does not update (it touches only the JVM thread-local
   store / wasm global). Direct reads/`setq` of the special (the common case) are
   correct.
   Self-heals after the `let` (both stores agree again). Narrow: only bites a
   program that both dynamically binds a special AND introspects it via
   `symbol-value`/`eval` on a compiled backend.
   The mirror at least HAS the global default now: the three standard stream
   variables used to be missing from it entirely, so `symbol-value` of one signalled
   "unbound" instead of answering the seeded designator (todo-283,
   `.kb/symbol-runtime-api.md`). That was the global default being absent, not this
   dynamic-scope gap, which is unchanged.
3. **A lambda/defun parameter named like a special is still lexical** (both
   interpreter and compilers). Naming a parameter with a special name and expecting
   the parameter binding to be dynamic is unsupported (rare).

## Relationship to the two hand-rolled precedents

- **`*package*` is a special with TWO faces (since 2026-08-15, todo-255).**
  Resolution-time: `PackageResolver` tracks the current package across the forms
  it walks (`in-package`, `pushPackage`/`popPackage` + the `%push-package`/
  `%pop-package` markers) -- that state decides which package a source symbol
  belongs to. Run-time: `*package*` is a genuine variable holding the package
  keyword; the resolver keeps the two in step by resolving `in-package` (and the
  pop marker) to `(setq *package* :P)`. On the compile paths the run-time face is
  the ordinary special of this file (a `(defvar *package* :cl-user)` injected
  when the program reads it, `let` = shallow binding, dynamic-first reads,
  ThreadLocal on the JVM when bound); on the interpreter the two faces are ONE
  cell (a read of `*package*` answers the resolver's current package, `setq`/`let`
  write it), which is why it is the one special the interpreter does NOT
  thread-scope. Mechanics and pins: `.kb/packages.md`.
- **Macro-time setf replay stays separate too.** cl-who reads `*html-mode*` at
  macro-EXPANSION (compile) time; a runtime special binding is invisible to an
  already-expanded macro, so `UserMacroExpander` replays a top-level `(setf (PLACE)
  ...)` into its macro-time evaluator. The decision to replay is a **static purity
  judgment** (`isPureConfigSetf`/`isPure`): a pure config setter -- one that only
  assigns special/global variables via a side-effect-free allow-list -- is
  auto-detected and replayed, deny-by-default so no external effect double-runs.
  It is a "macro-time configuration" concern, orthogonal to runtime dynamic
  binding. Details: `.kb/asdf.md` (cl-who paragraph).
- **A macro-time global's VALUE is demand-driven; its SPECIAL proclamation is
  not.** `LispEvaluator.registerLazyGlobal` adds the name to `specialVars` at
  once but parks the value expression as a thunk
  (`Environment.defineLazy`), so a `defvar` nobody reads at expansion time never
  runs its init there. Two couplings hold the replay together and both fail
  SILENTLY (wrong value, not a crash) if broken: `Environment.isBound` must count
  a pending thunk -- `isGlobalOrSpecialVariable` is half of the purity walk's
  "is this config state" test, and `defvar` idempotence rides on it -- and
  `Environment.set` must DISCARD a pending thunk, or the replayed
  `(setf *html-mode* :html5)` is later overwritten by the original `defvar`
  default when something forces the stale expression.

## Tests

`LispEvaluatorTest` (the `specialVar*`/`progv*`/`defparameter`/`declaim`/`proclaim`
/thread-scoped group), `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`
(the `specialVar*` group + the `progv*` group -- nested binds, an undeclared
name via `symbol-value`, extra symbols to nil, restores after normal return /
`return-from` / `go` / an error caught outside, and the setq-visible
`symbol-value` snapshot; the JVM group includes
`specialVarBindingIsThreadScoped` -- deterministic Thread.start/join via `java:`
interop -- and `specialVarSetqOutsideAnyBindingReachesTheGlobal`),
`NoGcWasmCompilerTest` (`rejectsSpecialVariableDeclaration`), `ClJsonE2eTest`
(the progv consumer, all four backends), and the
`special-variable-dynamic-binding` + `progv-compiles-on-every-backend` ci-spec
cases (all four backends).
