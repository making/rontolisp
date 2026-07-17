# Dynamic (special) variable binding

Common Lisp's second binding discipline (alongside lexical): a variable proclaimed
*special* is bound with **dynamic extent** by `let`/`let*`/`progv` -- the binding
is visible to every function called during the body (not just lexically nested
code) and restored on exit. rontolisp implements this via **shallow binding**: a
special's value lives in its ordinary global cell, and a dynamic binding is a
save/set/restore over that cell. Landed 2026-07-06 (`.todo/084`). Reference doc:
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
x))` is NOT honored (rare). A special is always ALSO a global (its default value
when unbound); on the compile path specials are unioned into the
`GlobalVarCollector` set so each gets a backing store.

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
- Restore fires on EVERY exit: normal return, non-local exit (`LispReturnSignal`),
  error unwind (`LispEvalException`) -- all unchecked, so the `finally` runs.

## JVM (`JvmLetCompiler`) -- shallow binding over the static field

- `Ctx.specialVars` threaded from `SpecialVarCollector.collect` (unioned into
  `globals` in `JvmLispCompiler` so each special has a `_g$*` static field).
- A special binding in `let`: compile init, `GETSTATIC` old into a temp,
  `PUTSTATIC` the init; after the body (result on stack), restore is `ALOAD temp;
  PUTSTATIC field` (stack-neutral). No lexical slot is allocated and the name is
  removed from `ctx.locals`, so body reads resolve to the static field
  (`getstatic`) -- visible to called functions (dynamic extent).
- Reads/`setq`/`defvar` of a special are unchanged (they already hit the static
  field). `let*` -> nested lets. Non-special globals stay lexical under `let`
  (`JvmLispCompilerTest.lexicalGlobalLetStaysLexical`).

## WASM (`WasmLetCompiler`) -- shallow binding over the module global

Same shape: `Ctx.specialVars`, specials unioned into `globals` (module-level
`(mut (ref null eq))`). A special binding saves the global into a temp local
(`global.get; local.set`), sets it, and restores after the body (`local.get;
global.set`). Same for the `--component` path (shared core module). `--no-gc`
`NoGcWasmCompiler` has no globals and rejects `defvar`/`declaim` at top level
outright, so a special can never be declared there
(`NoGcWasmCompilerTest.rejectsSpecialVariableDeclaration`).

## Compile-path limitations (interpreter is unaffected)

1. **`progv` is interpreter-only** -- a clear compile error on JVM/WASM
   (`JvmExprCompiler`/`WasmExprCompiler` PROGV case). The bound symbols are
   runtime-computed, so the compiler cannot name the static fields / wasm globals
   to save/restore. Would need the name-indexable `_genv`/`GLOBAL_ENV` runtime
   store.
2. **Non-local exit across a special-`let` boundary does not restore** on the
   compile path. Restore is emitted inline after the body, so it fires on normal
   exit; error is a trap/exception that aborts the program (moot); but a
   `return`/`return-from` whose `br`/`goto` jumps past the `let` skips the inline
   restore, leaving the global at the dynamic value. Covering it would mean
   threading pending-restores through the `%block`/`return` machinery
   (`JvmReturnCompiler`/`WasmReturnCompiler`) -- deferred. The interpreter's
   `finally` covers it.
3. **`symbol-value`/`boundp`/`eval` see the global default, not a dynamic binding,
   on the compile path.** Those read the `_genv`/`GLOBAL_ENV` eval mirror, which
   the shallow save/restore does not update (it touches only the static field /
   wasm global). Direct reads/`setq` of the special (the common case) are correct.
   Self-heals after the `let` (both stores agree again). Narrow: only bites a
   program that both dynamically binds a special AND introspects it via
   `symbol-value`/`eval` on a compiled backend.
4. **A lambda/defun parameter named like a special is still lexical** (both
   interpreter and compilers). Naming a parameter with a special name and expecting
   the parameter binding to be dynamic is unsupported (rare).

## Introspection

`progv` is in `PackageRegistry.CL_SPECIAL_FORMS`, so `list-special-forms` includes
it on all backends (pinned in `ci-spec.yaml`, the three backend tests, and
`doc/*/reference/functions/rontolisp-list-special-forms.md`). It sorts between
`progn` and `quote`.

## Relationship to the two hand-rolled precedents

- **`*package*` load scoping stays separate** -- a conscious decision.
  `*package*` is resolved at read/compile time by `PackageResolver`
  (`pushPackage`/`popPackage` + `%push-package`/`%pop-package` markers), NOT as a
  runtime variable, so the runtime special-binding mechanism here does not cover
  it. Two distinct models (runtime specials vs compile-time resolver state); see
  `.kb/packages.md`.
- **Macro-time setf replay stays separate too.** cl-who reads `*html-mode*` at
  macro-EXPANSION (compile) time; a runtime special binding is invisible to an
  already-expanded macro, so `UserMacroExpander` replays a top-level `(setf (PLACE)
  ...)` into its macro-time evaluator. The decision to replay is a **static purity
  judgment** (`isPureConfigSetf`/`isPure`): a pure config setter -- one that only
  assigns special/global variables via a side-effect-free allow-list -- is
  auto-detected and replayed, deny-by-default so no external effect double-runs.
  It is a "macro-time configuration" concern, orthogonal to runtime dynamic
  binding. Details: `.kb/asdf.md` (cl-who paragraph).

## Tests

`LispEvaluatorTest` (the `specialVar*`/`progv*`/`defparameter`/`declaim`/`proclaim`
/thread-scoped group), `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`
(the `specialVar*` group + `progvIsRejectedOn*`), `NoGcWasmCompilerTest`
(`rejectsSpecialVariableDeclaration`), and the `special-variable-dynamic-binding`
ci-spec case (all four backends).
