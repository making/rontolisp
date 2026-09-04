# JSON (`rontolisp:json-parse` / `rontolisp:json-stringify`)

`rontolisp`-package functions (not CL) whose value mapping matches `com.inuoe.jzon`'s defaults, so a program can switch to jzon unchanged. `JzonE2eTest` is the oracle: `json.lisp` is validated byte-for-byte against `com.inuoe.jzon:parse`/`stringify` both directions. User docs: `doc/*/reference/functions/rontolisp-json-{parse,stringify}.md`.

## Value mapping
- In: object -> hash table with string keys (`equal`), array -> vector, `true`/`false`/`null` -> `t`/`nil`/the symbol `null`.
- Out: `nil` -> `false`, symbol `null` -> `null`, vector or list -> array, hash table or CLOS instance -> object. A symbol/slot-name key is down-cased unless it already holds a lower-case letter (jzon's `coerce-key`).
- **Instance -> object** (`%json-out-instance`): `(typep v 'standard-object)` / `(typep v 'structure-object)`; slots in definition order via the bare `%class-slot-defs` (interpreter builtin / the compilers' macro-expanded registry dispatch — the primitive `closer-mop:class-slots` is built on), each `(slot-value v name)` recursively serialized. A class-free program still splices this dead code and compiles cleanly (`typep 'standard-object` is nil, dispatch folds to `(cond (t nil))`). Divergence from jzon: a `defstruct` instance serializes as an OBJECT here (`%class-slot-defs` answers for a struct designator), whereas jzon's `structure-object` method is feature-gated off under rontolisp.

## Object-building helpers (prelude defuns in `LispPreludeLibrary`)
- `rontolisp:plist-hash-table` / `hash-table-plist`, `rontolisp:alist-hash-table` / `hash-table-alist` — subsets of the same-named `alexandria` utilities; `json-stringify` down-cases keyword keys. `alist-hash-table` is first-key-wins (`(nth-value 1 (gethash ...))`, a recognized multi-value producer on every backend) and default-`eql`, like alexandria.
- `rontolisp:alist-plist` / `plist-alist` are the hash-table-free leg: ORDER-PRESERVING both ways and duplicate-key-keeping, so their multi-key output is pinned in ci-spec (`alist-plist-conversions`) while the hash-table ones pin only single-key cases.

## Single Lisp-source implementation
`src/main/resources/am/ik/rontolisp/eval/json.lisp` — fixed-arity `rontolisp::%json-*` helper defuns (double colon, internal symbols) in already-canonical package shape. `PackageResolverTest.jsonLibraryFormsAreAResolverFixedPoint` pins that resolving the library is a no-op, which is what makes splicing it both before and after resolution sound.

- **Interpreter**: `LispEvaluator` registers variadic dispatcher `LispFunction`s for the public names and lazily evaluates `JsonLibrary.forms()` into the global environment on first call.
- **Compile path**: `JsonLibrary.process(program)` runs after `LoadInliner`/`UserMacroExpander` in `RontoLispCli.compileToFile` (and in `RontoPlayground.compileJvm/Wasm`; compiler unit tests call it explicitly). When the program references the public names (qualified anywhere with either colon spelling; bare under `(in-package rontolisp)`; quoted mentions and `#'` count), it rewrites call sites to the fixed-arity helpers — both `%json-parse` and `%json-stringify` take exactly one argument, so a wrong arity is a compile-time error — and prepends the library defuns plus one-argument `#'` wrapper defuns (`(defun rontolisp:json-parse (s) (rontolisp::%json-parse s))`, reached only via `function`/`symbol-function`). A program without JSON is returned unchanged.
- The dispatcher/wrapper indirection predates the lambda-list extensions and both public functions are single-arity now; collapsing it is open cleanup, not a constraint.

## Portability rules inside json.lisp
- Only ASCII structural characters are compared via `char-code`; any non-ASCII character is copied verbatim with `subseq`.
- `\uXXXX` decoding emits one CHARACTER per decoded code point via `%json-encode-char` -> `code-char` (source surrogate pairs combine first); every backend indexes strings by code point (`.kb/characters-code-points.md`), so no per-backend branch.
- Integers up to 18 digits parse exactly everywhere (the WASM GC backend's boxed exact integers carry the signed 64-bit range, `.kb/wasm-bignum.md`); wider parses as a float everywhere.
- Float OUTPUT is byte-identical on all four backends: the Schubfach shortest round-trip decimal with the lowercase exponent marker (`1.5e12`) — `.kb/format.md`, "The float printer".
- Objects build `(make-hash-table :test 'equal)` with verbatim string keys; arrays build a simple vector (`%json-list->vector`: reverse-accumulated list filled into a `make-array`). So `json.lisp` does **not** call `read-from-string` and does not pull the runtime reader into compiled output.
- `null` is the symbol `'null` (`cl:null`, name `"null"`). Stringify detects it with `(eq v 'null)`, and tests `stringp` *before* `vectorp` (a string is a vector) and `(eq v 'null)`/`(null v)` before the general `symbolp` branch.
- The one closure (maphash callback in `%json-out-hash`) uses `%json-`-prefixed capture variable names — a harmless leftover from when compiled closures resolved captured names against same-named top-level globals.

## WASM `equal`/`_hash` (shipped with this feature)
`_equal` compared strings by interned offset, so runtime-built strings (concatenate/subseq/JSON keys) were never `equal` to literals and unusable as hash keys. Its string branch now calls `_string_eq` (byte-wise) and `_hash`'s string branch folds content bytes (`h = h*31 + byte`), preserving "equal keys hash equal" (`WasmRuntimeBuilder.buildEqualBody`/`buildHashBody`). `eql` still compares strings by identity (`WasmEqGeneralCompiler`), matching interpreter/JVM.

## Tests
`LispEvaluatorTest` (json* cases); `JvmLispCompilerTest` (`compileAndRunJson*`, incl. cl-user introspection non-pollution); `WasmLispCompilerTest.jsonOpsCompileInEveryMode`; `WasmLispCompilerIntegrationTest` (`jsonOpsWorkInPreview1Mode`, `equalAndHashTablesAcceptRuntimeBuiltStringKeys`); ci-spec `json-parse-and-stringify`, `string-equal-content-and-hash-keys`, `alist-plist-conversions`; `DocExamplesTest` via the reference pages. The native binary embeds `json.lisp` via `resource-config.json` (typeReachable `JsonLibrary`).
