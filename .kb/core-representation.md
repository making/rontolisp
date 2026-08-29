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

### What the `Object[]` shape actually costs (measured, 2026-08-28)

The shape is regularly blamed for the JVM list walk, because every `cdr` step
reads through a `checkcast [Ljava/lang/Object;` and an array bounds check where
a two-field class would read a field. Priced against hand-written Java on the
same machine, 10^9 dependent `cdr` steps over a 1000-cell list:

| walk | ns/step |
| --- | --- |
| `Object[]{car, cdr}`, cast per step (what we emit) | 2.62 |
| a two-field class with an `Object` cdr, cast per step | 2.51 |
| a two-field class with a `Cons`-typed cdr, no cast | 2.05 |

A Lisp cons cannot have the typed cdr -- an improper list's cdr is any object --
so the reachable saving from swapping the representation is the first two rows:
**4%**, against 55 cons-creation sites, ~265 element reads, ~237 writes, the
`consp`/`functionp`/`atom`/`listp`/`_equal`/`_hash`/printer discriminations, the
three copies of `properListElements` in the embedded Java templates, and a new
class in the travelling runtime (`.kb/jvm-export.md`). It is not worth it, and
the cast is not separately removable: `aaload` yields `Object`, so the verifier
requires the cast on every step whatever Pass 2 knows. The 30% that WAS on that
row is `.kb/jvm-int-fusion.md`'s counted-loop step -- the list's cache footprint,
not the cell's shape.

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

### One owner decides "this name needs a cell", and every BINDER asks it (todo-561)

Two questions have to give the same answer for a capture to work, and they are
asked by different code: the closure EMITTER (`JvmLambdaCompiler.compile` /
`WasmLambdaCompiler.compileValue`) decides what to capture from
`findFreeVars`, while the BINDER that created the variable decides whether its
slot holds the value or an `Object[1]` / `$cell`. The binder's owner is
`FreeVarAnalyzer.findCapturedVars` -- and a binder that does not ask it hands
the closure a FRESH cell holding a COPY, which is not a crash: the two sides
then mutate different cells and the compiled program answers the value the
binding started with, for good. Every binder consults it:
`Jvm`/`WasmLetCompiler` (the `let` bindings), the defun/lambda prologues in
`Jvm`/`WasmLispCompiler` (captured parameters), and
`Jvm`/`WasmLambdaCompiler.compileCall` (an inline `((lambda (p) ...) a)` binds
`p` in the CALLER's frame).

Two of those did not, and both were silent wrong answers found in 2026-08:

- **A `defun` nested in a `let` or a function body.** The capture walk SKIPPED
  `defun`, on the reading that a defun is a definition rather than a closure.
  It is not one here: both backends lower a non-top-level `defun` to
  `(setq name (lambda ...))` and call it through the variable
  (`LispMacroExpander.expandCallThroughVariable`), so every nested definition
  got its own copy of the binding. The CL closure-over-`let` idiom -- how
  cl-ppcre spells its scanner caches -- answered the INITIAL value from every
  definition: `(let ((counter 0)) (defun bump () (setq counter (+ counter 1)))
  (defun peek () counter))` printed 0 after two `bump`s on both compile paths,
  2 in the interpreter and in SBCL. The walk now descends a nested `defun`'s
  body with its own parameters removed, exactly as it descends a `lambda`.
- **An inline `((lambda (n) ...) 0)` call**, whose parameters
  `compileCall` bound as plain locals with no capture analysis at all, so a
  closure in the body wrote a snapshot: 0 where the interpreter and SBCL say 2.

The JVM emitter no longer has a fallback for the disagreement: a free variable
whose binding left it unboxed is an `IllegalStateException` naming the name,
not a fresh cell. Measured over all 219 `examples/**.lisp` compiles, the
fallback fired 0 times after the two binders were fixed (11 distinct names
before, all of them cl-ppcre / cl-postgres / uax-15 closure-over-`let`
bindings). Pinned by
`JvmLispCompilerTest#nestedDefunsShareTheEnclosingLetsBindingRatherThanEachTakingACopy`
/ `#anInlineLambdaCallBoxesAParameterItsBodyClosesOver`, their
`WasmLispCompilerIntegrationTest` twins, and the `closure-binders-share-one-cell`
ci-spec case (all four backends).

### The NAME half: every non-top-level `defun` is a global variable (todo-571)

The capture half above makes the nested definition share the right cell; the
call site still has to find it. Both backends lower a non-top-level `defun` to
`(setq name (lambda ...))` and resolve a call to a name that is not a compiled
function but IS a known global VARIABLE through
`LispMacroExpander.expandCallThroughVariable` -> `(funcall name ...)`, so the
whole mechanism hangs on the name reaching the globals set --
`GlobalVarCollector`, which mints the JVM static field / WASM module global.

`collect` walks the TOP-LEVEL forms, and Pass 1 removes every top-level `defun`
from that list before it runs (a defun declares a function, not a variable). So
a `defun` nested in a `let`, a `when`, a `progn` -- any top-level non-defun form
-- was collected, and a `defun` nested in a `defun` BODY was the one spelling
nothing could see: `(defun install (seed) (defun read-seed () seed) ...)` gave
`warning: the function READ-SEED is undefined` and then the run-time
undefined-function error, where the interpreter and SBCL answer. The bodies are
walked by `GlobalVarCollector.collectNestedInDefunBodies(program)`, unioned into
the globals set by both `JvmLispCompiler` and `WasmLispCompiler` right after
`collect` -- the same names, one seam, both spellings.

The shape's own semantics are unchanged and are CL's: the definition does not
exist until the enclosing function is CALLED, and calling it twice rebinds the
name. `CompileTimeBoundp` already models that -- a `defun` head is in its
`DEFERRING` set, so a name defined inside a function body is POISONED (no
`(boundp 'name)` fold) rather than asserted bound.

### A nested `defun` that REDEFINES a top-level one (todo-574)

A name with BOTH spellings had two resolution rules and the wrong one won:
every by-name call site resolved the compiled function first and only fell
through to the global variable when there was none, so the nested definition
was written to a store nothing read. The fix gives such a name ONE rule --
`compiler/NestedDefunRedefinition`, a front-end rewrite both compilers run
right after `ShadowedBuiltins.process` (after every pass that can mint a
top-level defun of its own, before Pass 1):

```lisp
(defun over () 'top)          ->  (defun %top-defun$over () 'top)
                                  (setq over (function %top-defun$over))
```

The name is then a global variable and nothing else, so every mechanism that
already serves a nested `defun` serves it -- the call sites dispatch through
the variable, `#'over` reads it, and the LAST assignment executed wins, which
is the interpreter's and SBCL's answer (`TOP DONE NESTED`). The assignment sits
where the `defun` sat, so a top-level call before it is as undefined as it is in
the interpreter, and the two definitions need not share an arity (the call is a
`funcall`, not a fixed direct call). A program where the two spellings never
meet is returned unchanged, so the indirect call is paid only where it buys the
right answer.

Three cases it deliberately does NOT paper over:

- The name is also declared by a top-level `defvar`/`defparameter`/
  `defconstant`. One cell cannot hold both the function value and the
  variable's, so the compile REFUSES and names the function and the
  declaration. Silently picking one is what this whole item was about.
- The name is exported (`rontolisp:jvm-export` / `rontolisp:wasm-export`). An
  export binds ONE static definition -- the host calls the typed wrapper beside
  the defun method, and there is no defun method once the name resolves through
  a variable. The compile refuses and says so; without the guard the directive
  itself failed with "names an unknown function (must be a top-level defun)"
  about a name that IS one.
- The `(setq name (lambda ...))` a non-top-level `defun` lowers to assigns the
  global VARIABLE, and `--dynamic`'s late-binding fallback resolves the runtime
  FUNCTION namespace -- which that definition never enters, so a `--dynamic`
  build answered nil for the plain nested case too. Both call sites now ask the
  variable first for exactly the names in `Ctx.nestedDefunNames`
  (`GlobalVarCollector.collectAllNestedDefunNames`, copied by
  `WasmAsyncEmit.freshCtx`), which is BEFORE the dynamic branch;
  `--dynamic` for any OTHER global is untouched.

Pinned by `JvmLispCompilerTest#aDefunNestedInADefunBodyIsReachableByName`,
`#aDefunNestedInADefunBodyRedefinesAnExistingTopLevelDefun`,
`#aRedefinedDefunIsAlsoRedefinedForFunctionReferencesAndCallers`,
`#aNestedDefunIsReachableByNameUnderDynamicMode`,
`#aTopLevelDefunRedefinedByANestedOneMayNotAlsoBeAGlobalVariable`,
`#aTopLevelDefunRedefinedByANestedOneMayNotBeExported`, the
`WasmLispCompilerIntegrationTest` twins of the first three, and the second half
of the `closure-binders-share-one-cell` ci-spec case (all four backends).

Note the boundary with the section below: two TOP-LEVEL definitions of one name
still bind every call to the LAST one (whole-program static resolution). Only a
non-top-level redefinition is late-bound, because only that one can run after a
call has already been made.

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
