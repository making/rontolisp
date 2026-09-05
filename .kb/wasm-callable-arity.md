# WASM backend: the callable-type arity limit, and the two ways past it

Scope: GC WASM backend (`codegen.wasm`), Preview 1 and `--component`; JVM and interpreter
have no such limit and their answers must not change.

**Invariant: a wasm DEFUN/LAMBDA takes at most `WasmLispCompiler.MAX_CALLABLE_ARITY` (10)
wasm parameters, a CALL SITE at most `callArityCeiling()` (10 + what the program asked for,
capped at `MAX_EXTRA_CALL_ARITY` = 4), and no program may observe either.** Fixed per-arity
dispatchers are `FUNC_DISPATCH_BASE + 0..10`. Rewrites happen at AST level in
`WasmArityBundler`, before the apply-runtime scan.

- DEFUN >10 params: bundled -- surplus params become one list param, direct call sites pass
  `(list ...)`. Taking a function VALUE of a bundled function is a clear compile error.
- CALL SITE 11..14 args through a function value: its own appended per-arity dispatcher.
- Past that: spread -- `(funcall f a1 .. a15)` -> `(apply f (list ...))`, which `_apply`
  hands to `FUNC_DISPATCH_SPREAD` (`WasmArityBundler.spreadOverArityFuncalls`).
- Call-site count != callee param count: keywords pass VERBATIM, so 3 required + 4 keywords =
  ELEVEN args. Without the spread rule such a site compiles to
  `LispMacroExpander.overArityFuncallStub` -- a call-time signal, a bare `unreachable` in a
  non-EH module, no compile-time warning.

## Traps
- `MAX_CALLABLE_ARITY` is an index ORIGIN, never raised: the extra tier is APPENDED, at
  `extraDispatchFuncBase()` (shifts `userFuncBase()` only) and `extraCallableTypeBase()`
  (shifts `fixedTypeCount()` only). `extraCallArity` comes from
  `WasmArityBundler.widestDispatchArity`; past the cap the WHOLE program falls back to 10.
- `mapcar`/`mapc`/`mapcan` pick a dispatcher by LIST COUNT via
  `WasmLispCompiler.mapDispatchFuncIndex`, which holds their ceiling check; an unchecked
  `FUNC_DISPATCH_BASE + nLists` addresses the next runtime helper and emits a non-validating
  module. A literal `#'name`/`'name` designator of compatible arity is a direct call
  (`WasmDesignatorCall`,
  [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)) and skips all this.
- The spread pass must run BEFORE `LispMacroExpander.needsApplyRuntime`
  ([eval-runtime.md](eval-runtime.md)), which builds the spread dispatcher's BODY only when it
  sees `apply`. The injected designator is a VARIABLE, caught by its computed-designator arm.
- The ceiling must be known before Pass 2 (`userFuncBase()`-relative indices) -- hence the AST
  pre-scan; a `funcall` synthesized DURING Pass 2 is invisible to it.
- `WasmAsyncEmit.freshCtx` rebuilds `Ctx` field by field and also builds the SYNCHRONOUS top
  level, so it must carry the ceiling (same trap as `instanceTypeIndex`/`layoutAddresses`).

## Tests
- `WasmLispCompilerIntegrationTest`: `compileFuncallWiderThanTheCallableLimitGoesThroughApply`,
  `compileFuncallEitherSideOfTheDerivedArityCeilingAnswersTheSame` (the `freshCtx` pin),
  `compileMapcarOverMoreListsThanTheFixedDispatcherBlockWorks`
- `WasmLispCompilerTest.aFuncallPastTheFixedDispatcherBlockCostsALadderAndNotTheSpreadDispatcher`
- ci-spec `fill-and-over-arity-funcall`; `ChipzE2eTest`.
