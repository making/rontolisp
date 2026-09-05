# Core representation

Encoding and pipeline invariants shared by the interpreter and both compilers.

## Encoding

- JVM class version 61: emitters write frame-free v50-style code; `am.ik.jvm.StackMapAugmenter`
  (post-pass at the end of `JvmLispCompiler.compile()`, after the optional shake) adds the
  frames and stamps the version. [stackmap-augmenter.md](stackmap-augmenter.md).
- WASM function types stay OUTSIDE the rec group (wasmtime's WASI host needs plain `(func ...)`
  for imports); only the cons struct is in one.
- symbolp/stringp: quoted symbols and string literals share one representation, split by a
  leading `"`.
- consp on the JVM: cons cells and function references are both `Object[]`, split by
  `arr[0] instanceof Integer`. A two-field class measured ~4% slower on a `cdr` walk.
- General arrays on the JVM start PACKED: plain `(make-array n)` (no fill pointer /
  adjustability / displacement, initial element nil or integer) is an `ArrayList` holding a
  length-6 header whose last slot is a flat `long[]`, `Long.MIN_VALUE` the nil sentinel; the
  first non-packable store widens in place (`_rmGet`/`_rmSet`, `_arrayWiden`;
  [adjustable-arrays.md](adjustable-arrays.md)).
- `%` prefix: internal helpers outside the public API (`%remf-tail`).
- `JvmLispCompiler.mangleMethodName()`: `/ < > : .` ->
  `$div`/`$lt`/`$gt`/`$le`/`$ge`/`$colon`/`$dot`, `%` -> `$pct`. Trap: `%` is legal in a JVM
  method name but OpenJDK's JVMCI treats it as a *format string*, so a hot `%`-prefixed defun
  aborts JIT compilation and warns into stdout.
- Template-class embedding is a last resort: prefer (1) macro expansion, (2) a
  `Jvm/Wasm<Name>RuntimeBuilder`, then (3) an embedded Java template class
  ([template-class-embedding.md](template-class-embedding.md)).

## String quotes are STORAGE; escaping belongs to the printer

Frame quotes are part of the stored value on both compile backends, so `*print-escape*`
escaping happens at PRINT time on the content between them.

- `LispString.literal()` (raw `"`+content+`"`) is what every compile-path STORAGE site emits:
  `Jvm/WasmExprCompiler`'s `LispString` case, `Jvm/WasmQuoteCompiler`, the keyword-name path of
  `Jvm/WasmSymbolApiCompiler`. **Trap: `print()` at a storage site bakes the escapes into the
  value**, so `length`/`char` see them.
- `LispString.print()` adds `\` before each embedded `"` and `\` (`LispString.escape`). Per
  CLHS 22.1.3.4 only terminator and single-escape escape; a newline prints LITERALLY (the
  reader also accepts `\n`/`\t`, so the writer deliberately covers less).
- Sites: interpreter `LispString.print()`; JVM `_strEsc`
  (`JvmRuntimeBuilder.buildStrEscBody`) from `_lispToString` and `emitArrayBranch`
  (`_lispToString` is also the hash-table key function, hence `_strEsc`'s no-op fast path);
  WASM GC/component `_write_str_gc(str, from, to, esc)` with `esc = 1`
  (`WasmStringRuntimeBuilder`), passed the CONTENT range so frame quotes are not escaped
  ([wasm-gc-strings.md](wasm-gc-strings.md)); `--no-gc`
  `NoGcWasmCompiler.emitWriteStringEscaped`, run-based, no allocation
  ([no-gc-scalar-wasm.md](no-gc-scalar-wasm.md)).
- `princ` / `~A` / `princ-to-string` / `write-line` are the no-escape half BY DEFINITION.
  Un-escaping mirror: `WasmReadRuntimeBuilder`'s string scanner, `LispReader`.
- Pins: `prin1EscapesQuotesAndBackslashesInStrings`, `prin1OutputReadsBackAsTheSameString` in
  `LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`; ci-spec
  `prin1-escapes-quotes-and-backslashes`.

## Three-pass compilation

Pass 1 collects defuns; 2a defun bodies, 2b top-level, 2c iteratively lambda bodies
(top-level must compile before lambda iteration).

**`compiler/FreeVarAnalyzer` must walk EVERY subform.** Backends use `findFreeVars` /
`collectCapturedVars`; the interpreter captures its whole `Environment`. Trap: a skipped
subform is no compile error but a THREE-WAY divergence -- interpreter right, JVM reads a fresh
copy (silently wrong), WASM refuses with `Cannot find variable for closure: <NAME>`. Rule for a
new head: consume the form's FULL argument list (`setq` takes place/value PAIRS). Pins:
`JvmLispCompilerTest#compileAndRunMultiPairSetqBuildsAClosureInALaterPair` and its
`WasmLispCompilerIntegrationTest` twin (move together).

**One owner decides "this name needs a cell"; every BINDER asks it.** The EMITTER
(`JvmLambdaCompiler.compile` / `WasmLambdaCompiler.compileValue`) takes captures from
`findFreeVars`; the BINDER picks value vs `Object[1]` / `$cell` and must ask
`FreeVarAnalyzer.findCapturedVars`. Trap: a binder that skips it hands the closure a FRESH cell
holding a COPY -- no crash; the two sides mutate different cells and the program answers the
initial value forever. Binders: `Jvm`/`WasmLetCompiler`; the defun/lambda prologues in
`Jvm`/`WasmLispCompiler`; `Jvm`/`WasmLambdaCompiler.compileCall` (an inline
`((lambda (p) ...) a)` binds `p` in the CALLER's frame). A non-top-level `defun` lowers to
`(setq name (lambda ...))` (`LispMacroExpander.expandCallThroughVariable`). No JVM fallback: an
unboxed free variable is an `IllegalStateException` naming the name. Pins:
`JvmLispCompilerTest#nestedDefunsShareTheEnclosingLetsBindingRatherThanEachTakingACopy`,
`#anInlineLambdaCallBoxesAParameterItsBodyClosesOver` + Wasm twins, ci-spec
`closure-binders-share-one-cell`.

**Every non-top-level `defun` is a global VARIABLE** and must reach `GlobalVarCollector`
(`collect` sees only top-level non-defun forms, so a `defun` nested in a `defun` BODY needs
`collectNestedInDefunBodies(program)`, unioned in by both compilers right after `collect`).
`defun` is in `CompileTimeBoundp`'s `DEFERRING` set, so the name is POISONED (no
`(boundp 'name)` fold).

**A nested `defun` REDEFINING a top-level one** goes through
`compiler/NestedDefunRedefinition`, run by both compilers right after `ShadowedBuiltins.process`
(after every pass that can mint a top-level defun, before Pass 1): the top-level definition
becomes `%top-defun$<name>` plus `(setq <name> (function %top-defun$<name>))`, so the name is a
global variable only and the LAST executed assignment wins. Three REFUSALS: the name is also a
top-level `defvar`/`defparameter`/`defconstant`; it is exported (`rontolisp:jvm-export` /
`rontolisp:wasm-export`); `--dynamic`, whose call sites ask the variable first for exactly
`Ctx.nestedDefunNames` (`GlobalVarCollector.collectAllNestedDefunNames`, copied by
`WasmAsyncEmit.freshCtx`) BEFORE the dynamic branch. Pins: the
`JvmLispCompilerTest#a*DefunNestedInADefunBody*` / `#aTopLevelDefunRedefinedByANestedOne*`
family, Wasm twins, second half of ci-spec `closure-binders-share-one-cell`.

## A redefined defun binds every call to its LAST definition

Call sites resolve through the per-NAME map built in Pass 1, where a later `put` wins -- unlike
the interpreter's sequential redefinition. Only a NON-top-level redefinition is late-bound.

- JVM: a class may not hold two methods of one name+descriptor (`ClassFormatError: Duplicate
  method name` at LOAD time), so `JvmLispCompiler` drops non-last duplicates from the defuns
  list before funcId assignment. Pin:
  `JvmLispCompilerTest#compileAndRunARedefinedDefunKeepsTheLastDefinition`.
- WASM emits ALL bodies, so a function index must be reserved from `Ctx.numDefuns` (= defuns
  list size), never from `functions.size()` (the deduplicating name map), or `_start`'s
  top-level-chunk calls land on arbitrary lambdas. Sites: `WasmToplevelEmit.openChunk`,
  `WasmLambdaCompiler`, `WasmAsyncEmit` x2, copied by `freshCtx`. Pin:
  `WasmLispCompilerIntegrationTest#redefinedDefunKeepsTheTopLevelChunkIndicesRight`.

## Built-in function wrappers

`BuiltinFunctionWrappers` synthesizes `(setq name (lambda ...))` defuns so `#'+`/`#'car` work as
first-class values -- internal encoding, not a real user definition (Lisp-2).

- The catalog answers on ALL FOUR backends: `lambdaFor(name)` hands the bare `(lambda ...)` to
  `LispEvaluator.resolveFunction` on the first `#'name` / `(symbol-function 'name)` of a
  built-in `Environment` never binds. It runs BEFORE the special-operator guard (e.g. `typep`)
  and cannot recurse.
- Every CL FUNCTION with an operator-position case needs a wrapper; without one the name is
  "undefined" on every backend. `BuiltinFunctionWrapperCatalogTest` walks
  `PackageRegistry.clFunctionNames()` and fails on any name with no function value. One
  exclusion: the four standard GENERICS with no built-in definition (`print-object`,
  `initialize-instance`, `reinitialize-instance`, `shared-initialize`). Cross-backend pins:
  `LoweredBuiltinValues` (one program, identical expectation in the three compiler tests),
  ci-spec `lowered-builtin-function-values`.
- A wrapper reaching a GATED runtime must be reference-gated too (gates scan the SOURCE program
  and wrappers are injected after them, so an ungated wrapper yields an `is undefined; compiled
  as a call-time error` warning on EVERY compiled program). In `REFERENCE_GATED_FUNCTIONS`:
  `#'typep` (`%typep-runtime`) and `#'map-into` (string arm `%schar-set-runtime`); the gates
  `LispMacroExpander.needsRuntimeTypep` and `reachesScharSet` see the `(function name)`
  spelling. `#'map` / `#'map-into` force the JVM array gate
  (`JvmLispCompiler.programUsesAnyArrayOp`).
- **A wrapper's arity is the OPERATOR's arity**, not the convenient shape: anything the operator
  decides STATICALLY (argument count, result type, file mode) is fixed while the caller's
  arguments are runtime values. Trap: a narrower wrapper does not signal -- surplus arguments go
  nowhere, wrong on the compile backends, right on the interpreter. Three answers, ascending
  cost: a `&rest` fold (`variadicIdentity`/`variadicNonEmpty`); a `&rest` dispatch onto the
  literal shapes (`openWrapper`, `concatenateWrapper` --
  [concatenate-result-families.md](concatenate-result-families.md)); the wrapper implementing
  the general case (`mapFamilyWrapper`, all six of the map family --
  [map-family.md](map-family.md)). Check the operator's CL lambda list before adding a wrapper.
