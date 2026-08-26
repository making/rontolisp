# Core representation

The low-level encoding and pipeline invariants of the value/AST representation
shared across the interpreter and the two compilers. Most of these facts had no
other `.kb` home (they lived inline in CLAUDE.md's "Core representation" bullet
list); the two that do are cross-referenced below rather than duplicated.

## JVM Class Version 61 (Java 17)

Emitters still write frame-free version-50-style code; `am.ik.jvm.StackMapAugmenter`
(an offline post-pass at the end of `JvmLispCompiler.compile()`, after the optional
shake) computes the mandatory StackMapTable frames and stamps the version. Compiled
classes need a Java 17+ JRE.

See [stackmap-augmenter.md](stackmap-augmenter.md) for the full detail.

## WASM function types outside rec group

wasmtime's WASI host requires plain `(func ...)` types for imports; only the cons
struct goes inside a rec group.

## symbolp/stringp

Quoted symbols and string literals share a runtime representation, distinguished by
a leading `"`.

### The quote framing is STORAGE; the escaping belongs to the printer

Because the frame quotes are part of the stored value on both compile backends, the
`*print-escape*` escaping can only be applied at PRINT time, on the content between
them. The split is explicit in the API:

- `LispString.literal()` -- the raw `"` + content + `"` spelling. This is what every
  compile-path STORAGE site emits (`Jvm/WasmExprCompiler`'s `LispString` case,
  `Jvm/WasmQuoteCompiler`, the keyword-name path of `Jvm/WasmSymbolApiCompiler`). A
  storage site that used `print()` would bake the escapes into the value itself, so
  `length`/`char` would see them.
- `LispString.print()` -- the readable form: the frame quotes plus every embedded `"`
  and `\` of the content preceded by a `\` (`LispString.escape`). CLHS 22.1.3.4
  escapes exactly the string terminator and the single-escape character; a newline is
  printed LITERALLY. Our reader also accepts `\n` / `\t` on input, so the writer
  deliberately covers less than the reader -- writing `\n` back would be a different
  string than the one printed.

Per backend the same rule is emitted, and the same discriminator decides string vs
symbol (a symbol never escapes):

| backend | where |
| --- | --- |
| interpreter | `LispString.print()` -- everything above it (`Environment.printString`, `prin1-to-string`, `write-to-string`, `format ~s`, `LispCons`/`LispArray`/`LispInstance` element rendering, the `%print-object-str` seam) inherits it |
| JVM | `_strEsc` (`JvmRuntimeBuilder.buildStrEscBody`), called from the `String` branch of `_lispToString` and from the character-vector prin1 branch of `emitArrayBranch`. `_lispToString` is also the hash-table key function, hence `_strEsc`'s no-op fast path |
| WASM GC / component | `_write_str_gc(str, from, to, esc)` with `esc = 1` (`WasmStringRuntimeBuilder`), from the string branch of `_print_val`; the branch now makes the same leading-`"` test `_princ_val` does, and passes the CONTENT range so the frame quotes it writes are not themselves escaped. [wasm-gc-strings.md](wasm-gc-strings.md) |
| `--no-gc` | `NoGcWasmCompiler.emitWriteStringEscaped`, a run-based writer at the `(print <string>)` site (no allocation: `print` must not move the bump heap). [no-gc-scalar-wasm.md](no-gc-scalar-wasm.md) |

`princ` / `~A` / `princ-to-string` / `write-line` are the no-escape half BY DEFINITION
and must stay untouched. The reader's un-escaping (`\"` / `\\`, plus `\n` / `\t`) is
the mirror: `WasmReadRuntimeBuilder`'s string scanner and `LispReader` on the
interpreter. Pinned by `prin1EscapesQuotesAndBackslashesInStrings` +
`prin1OutputReadsBackAsTheSameString` in `LispEvaluatorTest`, `JvmLispCompilerTest`
and `WasmLispCompilerIntegrationTest`, and the `prin1-escapes-quotes-and-backslashes`
case in `ci-spec.yaml`.

## consp in JVM

Cons cells and function references are both `Object[]`, distinguished by
`arr[0] instanceof Integer`.

## General arrays on the JVM start packed

A plain `(make-array n)` (no fill pointer / adjustability / displacement,
initial element nil or an integer) is an `ArrayList` holding ONLY a length-6
header whose last slot is a flat `long[]` of the elements, `Long.MIN_VALUE` as
the nil sentinel; the first non-packable store widens it in place to the boxed
shape. The header-length tag, the `_rmGet`/`_rmSet` branches and the
`_arrayWiden` contract live in
[adjustable-arrays.md](adjustable-arrays.md) ("A PLAIN general array starts
PACKED").

## Three-pass compilation

Pass 1 collects defuns; 2a compiles defun bodies, 2b top-level, 2c iteratively
compiles lambda bodies (top-level must compile before lambda iteration).

### A closure's captures come from `compiler/FreeVarAnalyzer`, and it must walk EVERY subform

Both compile backends decide what a lambda captures with
`FreeVarAnalyzer.findFreeVars` / `collectCapturedVars`; the interpreter captures
its whole `Environment` and so never consults it. That asymmetry is why a
subform the analyzer skips is not a compile error but a THREE-WAY divergence:
the interpreter answers correctly, the JVM emits a lambda that reads a fresh
copy of the variable (a silently wrong value), and WASM refuses with
`Cannot find variable for closure: <NAME>`. The one found in 2026-08 (jose,
`.kb/asdf.md`) was `setq`: it takes place/value PAIRS and the analyzer looked at
the first pair only, so a closure built by a later value form lost every capture
— cl-json's `set-custom-vars` expands to exactly `(setq v1 (lambda ...) v2
(lambda ...) ...)`. The rule for any new head added to either walk: consume the
form's FULL argument list, not the shape its commonest spelling has. Pinned by
`JvmLispCompilerTest#compileAndRunMultiPairSetqBuildsAClosureInALaterPair` and
`WasmLispCompilerIntegrationTest#multiPairSetqBuildsAClosureInALaterPair`, which
must move together.

## A redefined defun binds every call to its LAST definition (todo-256)

Both compile backends resolve a defun call site through the per-NAME map built
in Pass 1, where a later `put` of the same name wins — so a call between the
two definitions already gets the LAST body, unlike the interpreter's
sequential redefinition (fast-http redefines 11 struct readers as plain defuns
at load time, before any call, so nothing observes the difference). The
emission-side invariants that follow from real duplicate entries:

- **JVM**: a class may not hold two methods of one name and descriptor
  (`ClassFormatError: Duplicate method name` at LOAD time), so
  `JvmLispCompiler` drops every non-last duplicate from the defuns list before
  funcId assignment. Pinned by
  `JvmLispCompilerTest#compileAndRunARedefinedDefunKeepsTheLastDefinition`.
- **WASM**: all bodies are emitted (one module function per defuns-LIST entry),
  so any function index reserved from `functions.size()` — the deduplicating
  name map — sat below the real lambda region by the duplicate count, and
  `_start`'s top-level-chunk calls landed on arbitrary lambdas (an invalid
  module when arities differed). Reservation goes through `Ctx.numDefuns`
  (= the defuns list size; `WasmToplevelEmit.openChunk`, `WasmLambdaCompiler`,
  `WasmAsyncEmit` x2, copied by `freshCtx`). Pinned by
  `WasmLispCompilerIntegrationTest#redefinedDefunKeepsTheTopLevelChunkIndicesRight`.

## `%` prefix convention

Internal helpers outside the public API are `%`-prefixed (e.g. `%remf-tail`).

## Built-in function wrappers

`BuiltinFunctionWrappers` synthesizes `(setq name (lambda ...))` defuns so
`#'+`/`#'car` work as first-class values -- internal encoding, not a real user
definition (Lisp-2).

**The catalog is the answer on ALL FOUR backends, not just the compile paths.**
`BuiltinFunctionWrappers.lambdaFor(name)` hands the bare `(lambda ...)` to
`LispEvaluator.resolveFunction`, which evaluates it on the first `#'name` /
`(symbol-function 'name)` of a built-in `evalCons` lowers but `Environment` never
binds as a `LispFunction` -- the `isCarCdrComposition` synthesis two lines below
it, generalized. It runs BEFORE the special-operator guard, for the same reason
the registered-function lookup above it does: a name in the catalog IS a
function, whatever the operator table calls it (`typep` is the CL FUNCTION
rontolisp implements as a special form). It cannot recurse, because every
wrapped operator has a real lowering. Before that, the interpreter kept a
SECOND list -- the Java builtins in `Environment.createGlobal` -- and the two
drifted: `#'/=` worked compiled and was undefined interpreted.

**A CL FUNCTION with an operator-position case and no wrapper is a bug, not a
choice.** `#'coerce` / `#'elt` answered "The function COERCE is undefined" on
every backend, and the consumer is not only `mapcar` -- rove's `form-inspect`
rewrites every non-macro form inside an `ok` into `(apply #'op args)`, so an
assertion merely MENTIONING one died as a recorded error, or (`#'vector`, which
only BUILDS an argument) as a silent false assertion with the function under
test never called. The sweep is closed and PINNED:
`BuiltinFunctionWrapperCatalogTest` walks `PackageRegistry.clFunctionNames()`
and fails on any name with no function value. Its one exclusion is the four
standard GENERICS with no built-in definition of their own -- `print-object`,
`initialize-instance`, `reinitialize-instance`, `shared-initialize` -- whose
value appears when the program's own `defmethod` generates the dispatcher defun,
exactly as in CL; a wrapper there would be a lambda whose body resolves back to
itself. Cross-backend pins: `LoweredBuiltinValues` (one program, identical
expectation in `LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest`) and the `lowered-builtin-function-values`
`ci-spec.yaml` case for the component backend.

**A wrapper whose body reaches a GATED runtime must be reference-gated too.**
The gates scan the SOURCE program and the wrappers are injected after them, so
an ungated wrapper is a call into a defun the gate never emitted -- which shows
up as an `is undefined; compiled as a call-time error` warning on EVERY
compiled program, not only the ones that use the name. `#'typep` (its specifier
is a parameter, so the body is `%typep-runtime`) and `#'map-into` (it stores
through `(setf (elt ...))`, whose string arm is `%schar-set-runtime`) are in
`REFERENCE_GATED_FUNCTIONS` for exactly that, and each gate was widened to see
the `(function name)` spelling: `LispMacroExpander.needsRuntimeTypep` and
`reachesScharSet`. The latter also names `map-into` itself -- its CALL-position
expansion stores through the same place, which the scan never used to predict.
`#'map` / `#'map-into` additionally force the JVM's array gate
(`JvmLispCompiler.programUsesAnyArrayOp`), the `#'concatenate` precedent: map's
result type is a runtime value here, so its conversion goes through the computed
`coerce`, which always carries the vector-building arm.

**The invariant a wrapper must not break: a wrapper's arity is the OPERATOR's
arity, not the shape that was convenient to write.** The body puts the operator
in call position, where the backends inline it -- so anything the operator
decides STATICALLY (an argument count, a result type, a file mode) is fixed in
the wrapper while the caller's real arguments are runtime values. A wrapper
narrower than its operator does not signal: the surplus arguments simply go
nowhere, which is a wrong answer on the compile backends and a correct one on
the interpreter (the same call reaches a real variadic built-in there). Three
worked answers to that, in ascending cost: a `&rest` fold
(`variadicIdentity`/`variadicNonEmpty` for `+`/`min`), a `&rest` dispatch onto
the literal shapes the operator needs (`openWrapper`'s direction/element-type
plist, `concatenateWrapper`'s result-type family --
[concatenate-result-families.md](concatenate-result-families.md)), and the
wrapper implementing the general case itself (`mapFamilyWrapper` walks the
list-of-lists in a `do` loop for all six of `mapcar`/`mapc`/`mapcan`/`maplist`/
`mapcon`/`mapl`, because their list COUNT is static in call position but a
runtime property here; `alexandria:mappend` is `(apply #'mapcar f lists)` and
used to get every list but the first dropped --
[map-family.md](map-family.md)). When adding a wrapper, check the operator's CL
lambda list first: a wrapper narrower than the operator is a silent wrong answer
waiting for the first caller that needs the wide form.

## JVM method name mangling

`JvmLispCompiler.mangleMethodName()` maps `/ < > : .` to
`$div`/`$lt`/`$gt`/`$le`/`$ge`/`$colon`/`$dot`, plus `%` -> `$pct`. `%` is legal in
a JVM method name, but OpenJDK's JVMCI uses the method name as a *format string*, so
a hot `%`-prefixed defun aborts its JIT compilation and prints a warning into the
program's stdout.

## Template-class embedding is a last resort

Prefer (1) macro expansion, (2) a hand-assembled `Jvm/Wasm<Name>RuntimeBuilder`,
only then (3) an embedded Java template class (used by `java:` interop).

See [template-class-embedding.md](template-class-embedding.md) for the full detail.
