# Core representation

Encoding and pipeline invariants shared by the interpreter and both compilers.

## Encoding

- JVM class version 61 (Java 17): emitters write frame-free v50-style code;
  `am.ik.jvm.StackMapAugmenter` (post-pass at the end of `JvmLispCompiler.compile()`, after
  the optional shake) adds the frames and stamps the version.
  [stackmap-augmenter.md](stackmap-augmenter.md).
- WASM function types stay OUTSIDE the rec group (wasmtime's WASI host needs plain
  `(func ...)` for imports); only the cons struct is in one.
- symbolp/stringp: quoted symbols and string literals share one representation, split by a
  leading `"`.
- consp on the JVM: cons cells and function references are both `Object[]`, split by
  `arr[0] instanceof Integer`. A two-field class measured ~4% on a `cdr` walk and was
  rejected; a typed cdr is impossible (an improper list's cdr is any object) and the cast is
  not removable (`aaload` yields `Object`).
- General arrays on the JVM start PACKED: plain `(make-array n)` (no fill pointer /
  adjustability / displacement, initial element nil or integer) is an `ArrayList` holding a
  length-6 header whose last slot is a flat `long[]`, `Long.MIN_VALUE` the nil sentinel; the
  first non-packable store widens in place. `_rmGet`/`_rmSet`, `_arrayWiden`:
  [adjustable-arrays.md](adjustable-arrays.md).
- `%` prefix: internal helpers outside the public API (`%remf-tail`).
- `JvmLispCompiler.mangleMethodName()`: `/ < > : .` ->
  `$div`/`$lt`/`$gt`/`$le`/`$ge`/`$colon`/`$dot`, `%` -> `$pct`. Trap: `%` is legal in a JVM
  method name but OpenJDK's JVMCI treats it as a *format string*, so a hot `%`-prefixed defun
  aborts JIT compilation and warns into stdout.
- Template-class embedding is a last resort: prefer (1) macro expansion, (2) a
  `Jvm/Wasm<Name>RuntimeBuilder`, then (3) an embedded Java template class (`java:` interop).
  [template-class-embedding.md](template-class-embedding.md).

## String quotes are STORAGE; escaping belongs to the printer

Frame quotes are part of the stored value on both compile backends, so `*print-escape*`
escaping happens at PRINT time on the content between them.

- `LispString.literal()` (raw `"`+content+`"`) is what every compile-path STORAGE site emits:
  `Jvm/WasmExprCompiler`'s `LispString` case, `Jvm/WasmQuoteCompiler`, the keyword-name path
  of `Jvm/WasmSymbolApiCompiler`. Trap: `print()` at a storage site bakes the escapes into
  the value, so `length`/`char` see them.
- `LispString.print()` adds `\` before each embedded `"` and `\` (`LispString.escape`). Per
  CLHS 22.1.3.4 only terminator and single-escape escape; a newline prints LITERALLY. The
  reader also accepts `\n`/`\t`, so the writer deliberately covers less.
- Interpreter: `LispString.print()`, inherited by `Environment.printString`,
  `prin1-to-string`, `write-to-string`, `format ~s`, `LispCons`/`LispArray`/`LispInstance`
  element rendering, `%print-object-str`.
- JVM: `_strEsc` (`JvmRuntimeBuilder.buildStrEscBody`) from `_lispToString`'s `String` branch
  and `emitArrayBranch`'s character-vector prin1 branch; `_lispToString` is also the
  hash-table key function, hence `_strEsc`'s no-op fast path.
- WASM GC/component: `_write_str_gc(str, from, to, esc)` with `esc = 1`
  (`WasmStringRuntimeBuilder`) from `_print_val`'s string branch, which makes `_princ_val`'s
  leading-`"` test and passes the CONTENT range so its frame quotes are not escaped.
  [wasm-gc-strings.md](wasm-gc-strings.md).
- `--no-gc`: `NoGcWasmCompiler.emitWriteStringEscaped`, run-based at the `(print <string>)`
  site, no allocation (`print` must not move the bump heap).
  [no-gc-scalar-wasm.md](no-gc-scalar-wasm.md).
- `princ` / `~A` / `princ-to-string` / `write-line` are the no-escape half BY DEFINITION.
  Un-escaping mirror: `WasmReadRuntimeBuilder`'s string scanner, `LispReader`.
- Pins: `prin1EscapesQuotesAndBackslashesInStrings`, `prin1OutputReadsBackAsTheSameString` in
  `LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`; ci-spec
  `prin1-escapes-quotes-and-backslashes`.

## Three-pass compilation

Pass 1 collects defuns; 2a defun bodies, 2b top-level, 2c iteratively lambda bodies
(top-level must compile before lambda iteration).

**`compiler/FreeVarAnalyzer` must walk EVERY subform.** Backends use `findFreeVars` /
`collectCapturedVars`; the interpreter captures its whole `Environment` and never asks. Trap:
a skipped subform is no compile error but a THREE-WAY divergence -- interpreter right, JVM
reads a fresh copy (silently wrong), WASM refuses with `Cannot find variable for closure:
<NAME>`. Rule for a new head: consume the form's FULL argument list (`setq` takes place/value
PAIRS). Pins: `JvmLispCompilerTest#compileAndRunMultiPairSetqBuildsAClosureInALaterPair`,
`WasmLispCompilerIntegrationTest#multiPairSetqBuildsAClosureInALaterPair` (move together).

**One owner decides "this name needs a cell"; every BINDER asks it.** The EMITTER
(`JvmLambdaCompiler.compile` / `WasmLambdaCompiler.compileValue`) takes captures from
`findFreeVars`; the BINDER picks value vs `Object[1]` / `$cell` and must ask
`FreeVarAnalyzer.findCapturedVars`. Trap: a binder that skips it hands the closure a FRESH
cell holding a COPY -- no crash; the two sides mutate different cells and the program answers
the initial value forever. Binders: `Jvm`/`WasmLetCompiler`; the defun/lambda prologues in
`Jvm`/`WasmLispCompiler` (captured parameters); `Jvm`/`WasmLambdaCompiler.compileCall` (an
inline `((lambda (p) ...) a)` binds `p` in the CALLER's frame). A non-top-level `defun` lowers
to `(setq name (lambda ...))` called through the variable
(`LispMacroExpander.expandCallThroughVariable`), so the walk descends its body with its own
parameters removed, as for a `lambda`. No JVM fallback: an unboxed free variable is an
`IllegalStateException` naming the name. Pins:
`JvmLispCompilerTest#nestedDefunsShareTheEnclosingLetsBindingRatherThanEachTakingACopy`,
`#anInlineLambdaCallBoxesAParameterItsBodyClosesOver`, their
`WasmLispCompilerIntegrationTest` twins, ci-spec `closure-binders-share-one-cell`.

**Every non-top-level `defun` is a global VARIABLE** and must reach `GlobalVarCollector`,
which mints the JVM static field / WASM global. `collect` sees only top-level non-defun
forms, so a `defun` nested in a `defun` BODY needs
`GlobalVarCollector.collectNestedInDefunBodies(program)`, unioned in by both
`JvmLispCompiler` and `WasmLispCompiler` right after `collect`. CL semantics: the definition
does not exist until the enclosing function is CALLED, and calling it twice rebinds; `defun`
is in `CompileTimeBoundp`'s `DEFERRING` set, so the name is POISONED (no `(boundp 'name)`
fold).

**A nested `defun` REDEFINING a top-level one** goes through
`compiler/NestedDefunRedefinition`, run by both compilers right after
`ShadowedBuiltins.process` (after every pass that can mint a top-level defun, before Pass 1):

```lisp
(defun over () 'top)  ->  (defun %top-defun$over () 'top)
                          (setq over (function %top-defun$over))
```

The name is then a global variable only: calls dispatch through it, `#'over` reads it, LAST
executed assignment wins, arities need not match. Programs where the two spellings never meet
are unchanged. Three REFUSALS: the name is also a top-level
`defvar`/`defparameter`/`defconstant` (one cell cannot hold both); it is exported
(`rontolisp:jvm-export` / `rontolisp:wasm-export` binds ONE static definition and no defun
method survives); `--dynamic`, whose late binding resolves the runtime FUNCTION namespace that
`(setq name (lambda ...))` never enters -- both call sites ask the variable first for exactly
`Ctx.nestedDefunNames` (`GlobalVarCollector.collectAllNestedDefunNames`, copied by
`WasmAsyncEmit.freshCtx`) BEFORE the dynamic branch. Pins:
`JvmLispCompilerTest#aDefunNestedInADefunBodyIsReachableByName`,
`#aDefunNestedInADefunBodyRedefinesAnExistingTopLevelDefun`,
`#aRedefinedDefunIsAlsoRedefinedForFunctionReferencesAndCallers`,
`#aNestedDefunIsReachableByNameUnderDynamicMode`,
`#aTopLevelDefunRedefinedByANestedOneMayNotAlsoBeAGlobalVariable`,
`#aTopLevelDefunRedefinedByANestedOneMayNotBeExported`, the
`WasmLispCompilerIntegrationTest` twins of the first three, second half of ci-spec
`closure-binders-share-one-cell`.

## A redefined defun binds every call to its LAST definition

Call sites resolve through the per-NAME map built in Pass 1, where a later `put` wins --
unlike the interpreter's sequential redefinition. Only a NON-top-level redefinition is
late-bound.

- JVM: a class may not hold two methods of one name+descriptor (`ClassFormatError: Duplicate
  method name` at LOAD time), so `JvmLispCompiler` drops non-last duplicates from the defuns
  list before funcId assignment. Pin:
  `JvmLispCompilerTest#compileAndRunARedefinedDefunKeepsTheLastDefinition`.
- WASM emits ALL bodies (one module function per defuns-LIST entry), so a function index must
  be reserved from `Ctx.numDefuns` (= defuns list size), never from `functions.size()` (the
  deduplicating name map), or `_start`'s top-level-chunk calls land on arbitrary lambdas.
  Sites: `WasmToplevelEmit.openChunk`, `WasmLambdaCompiler`, `WasmAsyncEmit` x2, copied by
  `freshCtx`. Pin:
  `WasmLispCompilerIntegrationTest#redefinedDefunKeepsTheTopLevelChunkIndicesRight`.

## Built-in function wrappers

`BuiltinFunctionWrappers` synthesizes `(setq name (lambda ...))` defuns so `#'+`/`#'car` work
as first-class values -- internal encoding, not a real user definition (Lisp-2).

- The catalog answers on ALL FOUR backends: `lambdaFor(name)` hands the bare `(lambda ...)`
  to `LispEvaluator.resolveFunction` on the first `#'name` / `(symbol-function 'name)` of a
  built-in `evalCons` lowers but `Environment` never binds as a `LispFunction`. It runs BEFORE
  the special-operator guard (a catalog name IS a function whatever the operator table calls
  it, e.g. `typep`) and cannot recurse.
- Every CL FUNCTION with an operator-position case needs a wrapper; without one the name is
  "undefined" on every backend, or silently asserts false where a test framework rewrites
  forms to `(apply #'op args)`. `BuiltinFunctionWrapperCatalogTest` walks
  `PackageRegistry.clFunctionNames()` and fails on any name with no function value. One
  exclusion: the four standard GENERICS with no built-in definition (`print-object`,
  `initialize-instance`, `reinitialize-instance`, `shared-initialize`), whose value appears
  once the program's own `defmethod` generates the dispatcher defun. Cross-backend pins:
  `LoweredBuiltinValues` (one program, identical expectation in `LispEvaluatorTest` /
  `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`), ci-spec
  `lowered-builtin-function-values`.
- A wrapper reaching a GATED runtime must be reference-gated too: gates scan the SOURCE
  program and wrappers are injected after them, so an ungated wrapper calls a defun the gate
  never emitted -- an `is undefined; compiled as a call-time error` warning on EVERY compiled
  program. In `REFERENCE_GATED_FUNCTIONS`: `#'typep` (body `%typep-runtime`) and `#'map-into`
  (stores through `(setf (elt ...))`, string arm `%schar-set-runtime`). The gates
  `LispMacroExpander.needsRuntimeTypep` and `reachesScharSet` see the `(function name)`
  spelling, and `reachesScharSet` also names `map-into` itself. `#'map` / `#'map-into` force
  the JVM array gate (`JvmLispCompiler.programUsesAnyArrayOp`), the `#'concatenate`
  precedent.
- A wrapper's arity is the OPERATOR's arity, not the convenient shape: the operator sits in
  call position where backends inline it, so anything decided STATICALLY (argument count,
  result type, file mode) is fixed while the caller's arguments are runtime values. Trap: a
  narrower wrapper does not signal -- surplus arguments go nowhere, wrong on the compile
  backends, right on the interpreter. Three answers, ascending cost: a `&rest` fold
  (`variadicIdentity`/`variadicNonEmpty` for `+`/`min`); a `&rest` dispatch onto the literal
  shapes the operator needs (`openWrapper`'s direction/element-type plist,
  `concatenateWrapper`'s result-type family --
  [concatenate-result-families.md](concatenate-result-families.md)); the wrapper implementing
  the general case (`mapFamilyWrapper`, a `do` loop over the list-of-lists for all six of
  `mapcar`/`mapc`/`mapcan`/`maplist`/`mapcon`/`mapl`, whose list COUNT is static in call
  position but a runtime property here -- [map-family.md](map-family.md)). Check the
  operator's CL lambda list before adding a wrapper.
