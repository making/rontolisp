# JSON (`rontolisp:json-parse` / `rontolisp:json-stringify`)

`rontolisp`-package functions (not CL standard), modeled on JavaScript's
`JSON.parse`/`JSON.stringify`. User-facing behavior (value mapping, plist vs
`:hash-table` representations, limitations) lives in
`doc/*/reference/functions/rontolisp-json-{parse,stringify}.md`.

**Single Lisp-source implementation.** The parser/serializer is hand-written
in rontolisp itself: `src/main/resources/am/ik/rontolisp/eval/json.lisp`
(fixed-arity `rontolisp::%json-*` helper defuns — double colon, internal
symbols — written in already-canonical package shape; `PackageResolverTest.
jsonLibraryFormsAreAResolverFixedPoint` pins that resolving the library is a
no-op, which is what makes splicing it both before and after resolution
sound). One implementation runs on every backend:

- **Interpreter** — `LispEvaluator` registers variadic dispatcher
  `LispFunction`s for the public names and lazily evaluates
  `JsonLibrary.forms()` into the global environment on the first call.
- **Compile path** — `JsonLibrary.process(program)` runs after
  `LoadInliner`/`UserMacroExpander` in `RontoLispCli.compileToFile` (and in
  the web playground's `RontoPlayground.compileJvm/Wasm`; compiler unit tests
  call it explicitly, like `UserMacroExpander`). When the program references
  the public names (qualified anywhere — either colon spelling — bare under
  `(in-package rontolisp)`, quoted mentions and `#'` count as usage), it
  rewrites call sites to the
  fixed-arity helpers — `(rontolisp:json-parse s)` gains a trailing `nil`;
  a wrong arity is a compile-time error — and prepends the library defuns
  plus one-argument `#'` wrapper defuns
  (`(defun rontolisp:json-parse (s) ...)`, reached only via
  `function`/`symbol-function`). A program without JSON is returned unchanged.

**Why not plain defuns/&optional**: user lambda lists support required
parameters only (`.todo/31`), hence the dispatcher/rewrite split. The `%json-`
helper names are excluded from `cl-user` introspection by the existing
`PackageIntrospection.userFunctionNames` filter (`%` prefix / `:` qualified).

**Portability rules inside json.lisp** (why it runs unchanged on WASM):
only ASCII structural characters are compared via `char-code`; any unit >= 128
(UTF-16 unit on interpreter/JVM, UTF-8 byte on WASM) is copied verbatim with
`subseq`. `\uXXXX` decoding detects the representation at runtime
(`(= (length "あ") 3)` — byte-indexed) and emits UTF-8 bytes or UTF-16 units
(surrogate pairs combined). Integers wider than 9 digits parse as floats
everywhere (WASM i31 range); WASM cannot *print* floats >= 2^31
(`.todo/46`). plist keywords are interned via `read-from-string` (round-trip
guarded, so non-symbol-friendly keys error toward `:hash-table`), which pulls
the runtime reader into compiled output. The one closure (maphash callback in
`%json-out-hash`) uses `%json-`-prefixed capture variable names, a leftover
workaround from when compiled closures resolved captured names against
same-named top-level globals (fixed 2026-07-03 in `Jvm/WasmLambdaCompiler`;
the rename stays because it is harmless).

**WASM `equal`/`_hash` fix (shipped with this feature)**: `_equal` compared
strings by interned offset, so runtime-built strings (concatenate/subseq/JSON
keys) were never `equal` to literals and unusable as hash-table keys. Now
`_equal`'s string branch calls `_string_eq` (byte-wise content) and `_hash`'s
string branch folds the content bytes (`h = h*31 + byte`), preserving the
"equal keys hash equal" invariant (`WasmRuntimeBuilder.buildEqualBody`/
`buildHashBody`). `eql` still compares strings by identity
(`WasmEqGeneralCompiler`), matching the interpreter/JVM.

**Tests**: `LispEvaluatorTest` (json* cases), `JvmLispCompilerTest`
(`compileAndRunJson*`, incl. cl-user introspection non-pollution),
`WasmLispCompilerTest.jsonOpsCompileInEveryMode`,
`WasmLispCompilerIntegrationTest` (`jsonOpsWorkInPreview1Mode`,
`equalAndHashTablesAcceptRuntimeBuiltStringKeys`), ci-spec cases
`json-parse-and-stringify` + `string-equal-content-and-hash-keys` (all four
backends), DocExamplesTest via the reference pages. The native binary embeds
`json.lisp` via `resource-config.json` (typeReachable `JsonLibrary`).
