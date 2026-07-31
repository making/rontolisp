# JSON (`rontolisp:json-parse` / `rontolisp:json-stringify`)

`rontolisp`-package functions (not CL standard) whose value mapping matches
`com.inuoe.jzon`'s defaults, so they are a lightweight, forward-compatible
subset of jzon (a program can start on `rontolisp:json-*` and switch to jzon
unchanged). Object -> hash table with string keys (`equal`), array -> vector,
`true`/`false`/`null` -> `t`/`nil`/the symbol `null`; on the way out `nil` ->
`false`, the symbol `null` -> `null`, a vector or list -> array, a hash table or
a CLOS instance -> object (a symbol/slot-name key is down-cased unless it already
holds a lower-case letter, like jzon's `coerce-key`). This is *the same* value
shape the real jzon produces under rontolisp -- `JzonE2eTest` is the oracle, and
`json.lisp` is validated byte-for-byte against `com.inuoe.jzon:parse`/`stringify`
(both directions). User-facing behavior lives in
`doc/*/reference/functions/rontolisp-json-{parse,stringify}.md`.

**Instance -> object** (`%json-out-instance`): detected with
`(typep v 'standard-object)` for a CLOS instance and `(typep v 'structure-object)`
for a `defstruct` one, its slots enumerated in definition order via the
bare `%class-slot-defs` (the interpreter builtin / the compilers' macro-expanded
registry dispatch, the same primitive `closer-mop:class-slots` is built on) and
each `(slot-value v name)` recursively serialized -- so a hash-table slot nests
as an object, a list/vector slot as an array. A class-free program still splices
this (dead) code and compiles cleanly (`typep 'standard-object` is nil, the
dispatch folds to `(cond (t nil))`). jzon serializes a `standard-object` the same
way. A `defstruct` instance also serializes as an OBJECT here: it is no longer a
list (so the array branch cannot claim it) and `%class-slot-defs` answers for a
struct designator, which makes the CLOS walk work verbatim -- a divergence from
jzon, whose `structure-object` MOP method is feature-gated off under rontolisp.

**Building objects ergonomically** without breaking the drop-in-jzon promise:
`rontolisp:plist-hash-table` / `rontolisp:hash-table-plist` and
`rontolisp:alist-hash-table` / `rontolisp:hash-table-alist` (prelude defuns in
`LispPreludeLibrary`, subsets of the same-named `alexandria` utilities) turn a
keyword plist or an association list into a string-keyed hash table and back;
`json-stringify` down-cases the keyword keys. `alist-hash-table` is first-key-wins
(`(nth-value 1 (gethash ...))`, a recognized multi-value producer on every
backend) and default-`eql` like alexandria, which is exactly what the httpbin
examples' request headers / `query-params` alists want -- so the hand-rolled
`args-table`/`headers-table` helpers are gone. The alexandria names (not a
bespoke helper) keep the switch-to-alexandria path clean, mirroring the
json-*/jzon relationship. `rontolisp:alist-plist` / `rontolisp:plist-alist`
complete the quartet with the hash-table-free leg of the same alexandria API:
they convert between the two list shapes directly, so unlike the four above they
are ORDER-PRESERVING in both directions and keep duplicate keys -- which is why
their multi-key output is pinned in `ci-spec.yaml`
(`alist-plist-conversions`) while the hash-table ones only pin single-key cases.

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
  rewrites call sites to the fixed-arity helpers — both `%json-parse` and
  `%json-stringify` take exactly one argument, so a wrong arity is a
  compile-time error — and prepends the library defuns plus one-argument `#'`
  wrapper defuns (`(defun rontolisp:json-parse (s) (rontolisp::%json-parse s))`,
  reached only via `function`/`symbol-function`). A program without JSON is
  returned unchanged.

**Why the dispatcher/rewrite split at all**: it predates the lambda-list
extensions and both public functions are single-arity now (jzon's `parse`
takes just the JSON string), so the dispatcher/wrapper indirection is a thin
arity/first-class-value shim rather than an `&optional` desugaring; collapsing
it is open cleanup, not a constraint. The `%json-`
helper names are excluded from `cl-user` introspection by the existing
`PackageIntrospection.userFunctionNames` filter (`%` prefix / `:` qualified).

**Portability rules inside json.lisp** (why it runs unchanged on WASM):
only ASCII structural characters are compared via `char-code`; any non-ASCII
character is copied verbatim with `subseq`. `\uXXXX` decoding always emits
one CHARACTER per decoded code point via `%json-encode-char` -> `code-char`
(surrogate pairs in the SOURCE `😀` combine into a single
supplementary code point first); every backend indexes strings by code
point after todo 153 (see [[characters-code-points]]) so no per-backend
representation branch is needed. Integers up to 18 digits parse exactly
everywhere (the WASM GC backend's boxed exact integers carry the signed 64-bit
range, `.kb/wasm-bignum.md`; `parse-integer` expands to arithmetic that rides
the same path); wider than 18 digits parses as a float on every backend. For
the float cases WASM *prints* magnitudes since todo-108 group C fixed the
float printer (exact integer part up to 2^63), but between 10^7 and 2^63 the
SHAPE differs: WASM emits all digits (`1500000000000.0`) where the
interpreter/JVM use `1.5E12` (residual of `.todo/046`). Objects build a
`(make-hash-table :test 'equal)` with the verbatim string keys and arrays a
simple vector (`%json-list->vector`: reverse-accumulated list filled into a
`make-array`), so — unlike the former keyword-plist mode — `json.lisp` no
longer calls `read-from-string` and does **not** pull the runtime reader into
compiled output. `null` is the symbol `'null` (`cl:null`, name `"null"`);
stringify detects it with `(eq v 'null)` and detects the empty-string /
symbol-key cases with `stringp` *before* `vectorp` (a string is a vector) and
`(eq v 'null)`/`(null v)` before the general `symbolp` branch. The one closure
(maphash callback in `%json-out-hash`) uses `%json-`-prefixed capture variable
names, a leftover
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
