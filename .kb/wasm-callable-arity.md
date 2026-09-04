# WASM backend: the callable-type arity limit, and the two ways past it

Scope: GC WASM backend (`codegen.wasm`), Preview 1 and `--component`. JVM and interpreter
have no such limit and their answers must not change.

## Invariant

A wasm DEFUN/LAMBDA takes at most `WasmLispCompiler.MAX_CALLABLE_ARITY` (10) wasm
parameters; a CALL SITE at most `callArityCeiling()` (10 + what the program asked for,
capped at `MAX_EXTRA_CALL_ARITY` = 4); no program may observe either. Fixed per-arity
dispatchers are `FUNC_DISPATCH_BASE + 0..10`, one wasm parameter per Lisp argument.
Rewrites happen at AST level in `WasmArityBundler`, before the apply-runtime scan.

- DEFUN >10 params (split-sequence's `split-list`): bundled -- surplus params become one
  list param, direct call sites pass `(list ...)`. Only DIRECT calls are rewritten, so
  taking a function VALUE of a bundled function is a clear compile error.
- CALL SITE 11..14 args through a function value: its own per-arity dispatcher, appended
  after the fixed block.
- CALL SITE past that: spread -- `(funcall f a1 .. a15)` -> `(apply f (list a1 .. a15))`,
  which `_apply` hands to `FUNC_DISPATCH_SPREAD` (one function over every callable, taking
  the arg list as one cons). `WasmArityBundler.spreadOverArityFuncalls` lets `funcall` reach
  it.

Call-site count != callee param count: keywords pass VERBATIM for the callee's dispatcher to
parse, so 3 required + 4 keywords = ELEVEN args (chipz `%inflate`, 7-param lambda list).
Without the spread rule such a site compiles to `LispMacroExpander.overArityFuncallStub` --
a call-time signal, a bare `unreachable` in a non-EH module, no compile-time warning.

## Ceiling is DERIVED, not raised

`MAX_CALLABLE_ARITY` is an index ORIGIN (`FUNC_DISPATCH_SPREAD = FUNC_DISPATCH_BASE +
MAX_CALLABLE_ARITY + 1`; every later `FUNC_*` and every type index after
`TYPE_CALLABLE_BASE + MAX_CALLABLE_ARITY` derive from it). Raising it moves indices in every
module. The extra tier is APPENDED instead: dispatchers `_dispatch_11..` at
`extraDispatchFuncBase()` (after the `--simd`/async function blocks, shifts `userFuncBase()`
only), extra callable signatures at `extraCallableTypeBase()` (after the `--simd`/async/
instance type blocks, shifts `fixedTypeCount()` only). A program whose widest call fits the
fixed block is byte-identical to a build without the tier.

`extraCallArity` is derived in `compile` from `WasmArityBundler.widestDispatchArity` (a
pre-scan for the widest `funcall` arg count or `mapcar`/`mapc`/`mapcan` list count). Past the
cap the WHOLE program falls back to the fixed ceiling.

Why 4: an extra ladder is one `br_table` over that arity's callables (975 B on zlib, 41 B in
a two-callable program); the spread dispatcher covers EVERY callable at every width (12,156 B
on zlib = 7.3% of the `--optimize=size` artifact, 2,405 B in the two-callable program).
Across 122 Quicklisp systems the widest `funcall` is uiop's 13-arg `ensure-pathname`, chipz's
11 next; every program measured wants exactly ONE extra arity.

## Map family shares the ceiling

`mapcar`/`mapc`/`mapcan` pick a dispatcher by LIST COUNT via
`WasmLispCompiler.mapDispatchFuncIndex`, which holds their ceiling check. No per-site
fallback: the unchecked `FUNC_DISPATCH_BASE + nLists` they used to compute silently addressed
the NEXT runtime helper and emitted a non-validating module. They are in
`widestDispatchArity`.

A literal `#'name` / `'name` designator of compatible arity is a direct call
(`WasmDesignatorCall`, `.kb/optimize-dead-code-elimination.md`) and is outside all of this --
`WasmFunctionCallCompiler.compileFuncall` asks for it BEFORE the ceiling check and
`mapDispatchFuncIndex` is never called. Only computed designators and spread-rewritten sites
take the wide path.

## Ordering traps

- The spread pass must run BEFORE `LispMacroExpander.needsApplyRuntime`
  (`.kb/eval-runtime.md`): that scan builds the spread dispatcher's BODY only when it sees
  `apply`. Rewriting in codegen instead leaves an `unreachable` stub. The injected designator
  is a VARIABLE, caught by the scan's computed-designator arm.
- The ceiling must be known before Pass 2, which writes `userFuncBase()`-relative indices --
  hence the AST pre-scan. A `funcall` a macro synthesizes DURING Pass 2 is invisible to it and
  still compiles to the call-time signal if past the ceiling. An extra dispatcher's body is
  the unused-arity stub when Pass 2 emitted no call to it.
- A `Ctx` that does not carry the ceiling: `WasmAsyncEmit.freshCtx` rebuilds `Ctx` field by
  field and also builds the SYNCHRONOUS top level, so a wide top-level funcall got the
  call-time signal while the same form in a defun reached the dispatcher (same trap as
  `instanceTypeIndex`/`layoutAddresses`).

## Tests

- `WasmLispCompilerIntegrationTest.compileFuncallWiderThanTheCallableLimitGoesThroughApply`
- `WasmLispCompilerIntegrationTest.compileFuncallEitherSideOfTheDerivedArityCeilingAnswersTheSame`
  (10/11/14/15 at top level -- the `freshCtx` pin)
- `WasmLispCompilerIntegrationTest.compileMapcarOverMoreListsThanTheFixedDispatcherBlockWorks`
- `WasmLispCompilerTest.aFuncallPastTheFixedDispatcherBlockCostsALadderAndNotTheSpreadDispatcher`
- ci-spec `fill-and-over-arity-funcall`; `ChipzE2eTest` (real-library consumer).
